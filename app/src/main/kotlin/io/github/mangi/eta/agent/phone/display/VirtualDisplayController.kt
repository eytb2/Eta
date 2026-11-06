package io.github.mangi.eta.agent.phone.display

import android.content.Context
import android.view.Surface
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 虚拟屏总控制器（App 进程内单例）：拉起 / 复用 Root 助手，管理 [AgentDisplay]
 * 的创建、截图、注入与释放。Agent 工具与接管小窗共用这一份状态。
 */
internal class VirtualDisplayController(
    private val context: Context,
    private val logger: AgentLogger,
    private val rootExecutor: BoundedRootCommandExecutor,
) : AutoCloseable {
    private val lock = Any()

    private val _display = MutableStateFlow<AgentDisplay?>(null)
    /** 当前虚拟屏；未创建时为 null。 */
    val display: StateFlow<AgentDisplay?> = _display.asStateFlow()

    @Volatile
    private var lastLuma: IntArray? = null

    /** 最近一次经 startApp 成功启动到虚拟屏的包名；常驻通知正文用它查 App 显示名 */
    @Volatile
    private var lastStartedPackage: String? = null

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 进行中的输入注入数量：迁移会打断手势序列（最坏 injectStroke 10s），迁移前必须等它归零 */
    private val activeInjections = java.util.concurrent.atomic.AtomicInteger(0)
    private var watchJob: Job? = null

    /** 迁移驻留状态：虚拟屏 surface 已释放、该 App 正驻留 MIUI 小窗、监听其被关闭 */
    @Volatile
    var migratedPackage: String? = null
        private set

    /** 移交主屏状态：present_to_user 后记录被移交的包名与其原虚拟屏尺寸（return 的逆操作依据） */
    @Volatile
    var presentedPackage: String? = null
        private set

    @Volatile
    var presentedScreen: ScreenSpec? = null
        private set

    @Volatile
    private var migratedScreen: ScreenSpec? = null

    /** 虚拟屏上启动过、尚未释放的 App（悬浮球角标/列表参考；最终以助手侧任务查询为准） */
    val runningPackages = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private fun shell(): IPhoneShell? = PhoneShellHub.shell ?: PhoneShellHub.ensureStarted(context) { command ->
        val result = rootExecutor.execute(command, timeoutMillis = 15_000)
        result.ok
    }

    /** 创建虚拟屏；已存在时直接返回现有的（尺寸参数被忽略并在结果里说明）。 */
    fun create(width: Int, height: Int, dpi: Int): Result {
        synchronized(lock) {
            _display.value?.let {
                refreshNotification()
                return Result(existing = true, displayId = it.displayId, width = it.width, height = it.height)
            }
            val shell = shell() ?: return Result(error = "HELPER_UNAVAILABLE", message = "Root 助手未就绪")
            return try {
                val display = AgentDisplay(shell, width, height, dpi)
                _display.value = display
                refreshNotification()
                VirtualDisplayBubble.show(context, this, null)
                Result(displayId = display.displayId, width = width, height = height)
            } catch (t: Throwable) {
                logger.error("virtual display create failed", t)
                Result(error = "CREATE_FAILED", message = t.message ?: "创建虚拟屏失败")
            }
        }
    }

    /** 把虚拟屏输出切到接管小窗 Surface；null 切回 ImageReader。 */
    fun setTakeoverSurface(surface: Surface?) {
        _display.value?.setTakeoverSurface(surface)
    }

    fun screenshot(): File? {
        val png = _display.value?.latestFramePng() ?: return null
        val dir = File(context.cacheDir, "vdisplay").apply { mkdirs() }
        val file = File(dir, "frame_${System.currentTimeMillis()}.png")
        return try {
            file.writeBytes(png)
            // 只保留最近 8 张，避免缓存膨胀
            dir.listFiles()?.sortedByDescending { it.name }?.drop(8)?.forEach { it.delete() }
            file
        } catch (t: Throwable) {
            logger.error("virtual display screenshot save failed", t)
            null
        }
    }

    fun injectTap(x: Int, y: Int): Boolean {
        activeInjections.incrementAndGet()
        try {
            return _display.value?.let {
                runCatching { PhoneShellHub.shell?.injectTap(it.displayId, x, y) }.getOrDefault(false)
            } ?: false
        } finally {
            activeInjections.decrementAndGet()
        }
    }

    /**
     * 在虚拟屏上注入一次滑动：起止点之间线性插值 5 个中间点（含首尾共 7 个），
     * 总时长均分到各段，经助手的 [IPhoneShell.injectStroke] 逐点注入，比一次性
     * swipe 更接近真实手指轨迹。助手不可用（未连接或仍是旧接口、笔画没注入成功）
     * 时退回旧的一次性 injectSwipe。
     */
    fun injectSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean {
        val display = _display.value ?: return false
        activeInjections.incrementAndGet()
        try {
        // 与助手侧旧 swipe 路径的时长收敛保持一致；也保证插值时刻严格递增
        val duration = durationMs.coerceIn(SWIPE_MIN_MS, SWIPE_MAX_MS)
        val shell = PhoneShellHub.shell
        if (shell != null) {
            val segments = SWIPE_STROKE_POINTS - 1
            val xs = IntArray(SWIPE_STROKE_POINTS) { startX + (endX - startX) * it / segments }
            val ys = IntArray(SWIPE_STROKE_POINTS) { startY + (endY - startY) * it / segments }
            val times = LongArray(SWIPE_STROKE_POINTS) { it.toLong() * duration / segments }
            if (runCatching { shell.injectStroke(display.displayId, xs, ys, times) }.getOrDefault(false)) {
                return true
            }
        }
            return runCatching { shell?.injectSwipe(display.displayId, startX, startY, endX, endY, duration) }
                .getOrDefault(false) == true
        } finally {
            activeInjections.decrementAndGet()
        }
    }

    fun injectKey(keyCode: Int): Boolean {
        activeInjections.incrementAndGet()
        try {
            return _display.value?.let {
                runCatching { PhoneShellHub.shell?.injectKey(it.displayId, keyCode) }.getOrDefault(false)
            } ?: false
        } finally {
            activeInjections.decrementAndGet()
        }
    }

    /** 在虚拟屏上启动 App；成功时记录包名并刷新常驻通知正文 */
    fun startApp(packageName: String, activity: String?): Int {
        val display = _display.value ?: return 2
        val code = runCatching { PhoneShellHub.shell?.startApp(display.displayId, packageName, activity) }
            .getOrDefault(2) ?: 2
        if (code == PhoneShellService.START_OK) {
            lastStartedPackage = packageName
            runningPackages.add(packageName)
            if (migratedPackage == packageName) {
                // 驻留任务已回到虚拟屏：迁移态结束
                migratedPackage = null
                migratedScreen = null
                watchJob?.cancel()
                watchJob = null
            }
            refreshNotification()
            VirtualDisplayBubble.show(context, this, packageName)
            startIdleWatch()
        }
        return code
    }

    /** 创建虚拟屏成功 / 启动 App 成功后发布或刷新常驻通知；通知失败不影响工具流程 */
    private fun refreshNotification() {
        try {
            // 迁移驻留态：正文标注"驻留小窗"，通知按钮语义随之变化
            VirtualDisplayNotifier.show(context, this, lastStartedPackage, migratedPackage != null)
        } catch (t: Throwable) {
            logger.error("virtual display notification failed", t)
        }
    }

    /** 查询助手侧某屏上任务的包名列表（悬浮球任务列表用）；失败返回空列表 */
    fun shellTaskPackages(displayId: Int): List<String> =
        runCatching { shell()?.taskPackagesOnDisplay(displayId)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())

    /** 记录并返回画面相对上次调用是否变化；首次调用返回 true（基准帧）。 */
    fun frameChanged(): Boolean {
        val display = _display.value ?: return false
        val current = display.lumaSnapshot() ?: return false
        val previous = lastLuma
        lastLuma = current
        return previous == null || FrameMath.differs(previous, current)
    }

    fun status(): Status {
        val display = _display.value
        return Status(
            created = display != null,
            displayId = display?.displayId ?: -1,
            width = display?.width ?: 0,
            height = display?.height ?: 0,
            takeover = display?.takeover ?: false,
            helperAlive = PhoneShellHub.shell != null,
            migrated = migratedPackage,
            migratedWidth = migratedScreen?.width ?: 0,
            migratedHeight = migratedScreen?.height ?: 0,
        )
    }

    /**
     * 接管 = 把虚拟屏上的 App 迁移为 MIUI 原生小窗（真屏），并释放虚拟屏。
     * 相比镜像 surface 的旧方案：原生渲染与触摸、无缩放裁切、单层合成不卡，
     * 也不再占用虚拟屏取帧通道（2026-10-31 真机对比后拍板）。
     * @return 成功返回 null；失败返回用户可读错误。
     */
    fun migrateToSmallWindow(): String? {
        val display = _display.value ?: return "虚拟屏未创建"
        val shell = shell() ?: return "Root 助手未就绪"
        val target = lastStartedPackage?.takeIf { it.isNotBlank() }
            ?: runCatching { shell.taskPackagesOnDisplay(display.displayId) }
                .getOrDefault(emptyArray())
                .firstOrNull { it != context.packageName }
            ?: return "虚拟屏上没有可迁移的 App"
        return migratePackage(target)
    }

    /**
     * 把指定包迁移为 MIUI 原生小窗：成功后虚拟屏 surface 释放（不再占渲染资源），
     * 任务进入"驻留小窗"状态并由 [startMigrateWatch] 监听——用户关闭小窗且进程
     * 未结束时，按原尺寸重建虚拟屏把任务拉回去；进程已死才算任务结束。
     */
    fun migratePackage(target: String): String? {
        val display = _display.value ?: return "虚拟屏未创建"
        val shell = shell() ?: return "Root 助手未就绪"
        // 迁移前等待进行中的手势序列（injectStroke 最长 10s）执行完，
        // 避免 Agent 状态机与视觉流在迁移瞬间被切断
        val injectDeadline = android.os.SystemClock.uptimeMillis() + 11_000
        while (activeInjections.get() > 0 && android.os.SystemClock.uptimeMillis() < injectDeadline) {
            android.os.SystemClock.sleep(100)
        }
        // 无损迁移（官方 v3.4.0 handoff 思路）：setTaskWindowingMode + moveRootTaskToDisplay，
        // Task 跨屏搬运不触发 Activity 重建，页面状态保留；虚拟屏保留存活（随时无损迁回）。
        // bounds 必须是 MIUI 小窗标准尺寸（0.94w×0.62h、top 0.28h，与手动小窗启动一致），
        // 不能用虚拟屏尺寸——那只是搬运目标屏的坐标系，不是小窗外观。
        val dm = context.resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val winW = (sw * 0.94f).toInt()
        val winH = (sh * 0.62f).toInt()
        val top = (sh * 0.28f).toInt()
        val left = (sw - winW) / 2
        val bounds = "$left,$top,${left + winW},${top + winH}"
        val moved = runCatching {
            shell.relocateTask(target, android.view.Display.DEFAULT_DISPLAY, true, bounds)
        }.getOrDefault(false)
        if (moved) {
            // 无损路径：display 保留存活，任务随时可无损迁回
            synchronized(lock) {
                migratedPackage = target
                migratedScreen = ScreenSpec(display.width, display.height, display.dpi)
            }
            VirtualDisplayBubble.show(context, this, target)
            refreshNotification()
            startMigrateWatch()
            return null
        }
        // 系统拒绝跨屏搬运：退回销毁式迁移（removeTask + MIUI 小窗新建，Activity 会重建）
        val code = runCatching {
            shell.migrateDisplayAppToSmallWindow(display.displayId, target, null)
        }.getOrDefault(PhoneShellService.START_ERROR)
        if (code != PhoneShellService.START_OK) {
            return when (code) {
                PhoneShellService.START_BLACKLISTED -> "该 App 在 MIUI 小窗黑名单中（小米官方标注不适合小窗，如相机/支付/游戏类）"
                PhoneShellService.START_IN_USE_ON_MAIN -> "该 App 正在主屏使用中（可能已开着小窗），未迁移"
                else -> "迁移失败（无损与重建两种方式均被拒绝，code=$code）"
            }
        }
        synchronized(lock) {
            migratedPackage = target
            migratedScreen = ScreenSpec(display.width, display.height, display.dpi)
            runCatching { display.release() }
            _display.value = null
            lastLuma = null
        }
        VirtualDisplayBubble.show(context, this, target)
        refreshNotification()
        startMigrateWatch()
        return null
    }

    /**
     * present_to_user（官方 v3.4.0 同名能力）：把驻留小窗的任务无损移交主屏全屏前台，
     * 页面状态保留——付款确认、扫码、FLAG_SECURE 安全页等需要本人操作的临门一脚场景。
     * 用户操作完后 Agent 可用 migratePackage 把任务无损收回虚拟屏。
     */
    fun presentToUser(): String? {
        val pkg = migratedPackage ?: return "当前没有驻留小窗的任务"
        val shell = shell() ?: return "Root 助手未就绪"
        val screen = migratedScreen
        val ok = runCatching {
            shell.relocateTask(pkg, android.view.Display.DEFAULT_DISPLAY, false, "")
        }.getOrDefault(false)
        if (!ok) return "移交主屏失败（系统拒绝跨屏搬运）"
        // 恢复音频焦点：主屏全屏期间由 App 正常发声
        runCatching { shell.setPackageAudioFocus(pkg, false) }
        presentedPackage = pkg
        presentedScreen = screen
        return null
    }

    /** 把已交还主屏的任务无损收回虚拟屏（present_to_user 的逆操作） */
    fun returnToVirtualDisplay(): String? {
        val pkg = presentedPackage ?: return "当前没有已移交主屏的任务"
        val screen = presentedScreen ?: return "缺少虚拟屏参数，无法收回"
        val shell = shell() ?: return "Root 助手未就绪"
        val ok = runCatching {
            shell.relocateTask(pkg, currentVirtualDisplayId() ?: return "虚拟屏已释放，无法收回", true, "0,0,${screen.width},${screen.height}")
        }.getOrDefault(false)
        if (!ok) return "收回虚拟屏失败"
        runCatching { shell.setPackageAudioFocus(pkg, true) }
        presentedPackage = null
        presentedScreen = null
        migratedPackage = pkg
        migratedScreen = screen
        startMigrateWatch()
        return null
    }

    private fun currentVirtualDisplayId(): Int? = _display.value?.displayId

    /**
     * 空闲监听（未迁移态）：Agent 启动到虚拟屏的任务被用户从主屏入口
     * （桌面图标/侧边栏/最近任务）拉走时，视为用户主动接管——转入迁移驻留态，
     * Agent 对该任务让位；此后关闭小窗且进程未死时自动回虚拟屏（复用 startMigrateWatch）。
     * 连续两轮"不在虚拟屏且在主屏"才判定，避免 am start 建任务竞态误判。
     */
    private fun startIdleWatch() {
        watchJob?.cancel()
        watchJob = watchScope.launch {
            var pending: String? = null
            while (isActive) {
                delay(2_000)
                if (migratedPackage != null || presentedPackage != null) return@launch
                val display = _display.value ?: return@launch
                val pkgs = synchronized(runningPackages) { runningPackages.toList() }
                if (pkgs.isEmpty()) continue
                val shell = shell() ?: continue
                val onVp = runCatching { shell.taskPackagesOnDisplay(display.displayId).toList() }
                    .getOrNull() ?: continue
                val candidate = pkgs.firstOrNull { it != context.packageName && it !in onVp }
                if (candidate == null) { pending = null; continue }
                if (candidate != pending) { pending = candidate; continue }
                val onMain = runCatching { shell.taskPackagesOnDisplay(android.view.Display.DEFAULT_DISPLAY).toList() }
                    .getOrNull() ?: continue
                if (candidate !in onMain) { pending = null; continue }
                val alive = runCatching { shell.processAlive(candidate) }.getOrNull()
                if (alive != true) { pending = null; continue }
                // 用户主动接管成立：转入迁移驻留态，Agent 让位
                pending = null
                synchronized(lock) {
                    migratedPackage = candidate
                    migratedScreen = ScreenSpec(display.width, display.height, display.dpi)
                }
                refreshNotification()
                VirtualDisplayBubble.show(context, this@VirtualDisplayController, candidate)
                startMigrateWatch()
                return@launch
            }
        }
    }

    /**
     * 驻留监听：每 2s 查主屏任务，小窗被用户关闭（任务消失）且进程未死时，
     * 按迁移前的尺寸重建虚拟屏并回迁任务；进程已死则视为任务结束，收尾清理。
     */
    private fun startMigrateWatch() {
        watchJob?.cancel()
        val pkg = migratedPackage ?: return
        val screen = migratedScreen ?: return
        watchJob = watchScope.launch {
            var taskEnded = false
            var deadCount = 0
            var restoreFailures = 0
            while (isActive) {
                delay(2_000)
                val shell = shell() ?: continue
                // 查询失败（null）不等于任务消失：下轮再试
                val pkgs = runCatching {
                    shell.taskPackagesOnDisplay(android.view.Display.DEFAULT_DISPLAY).toList()
                }.getOrNull() ?: continue
                // 主屏正常不会一个任务都没有；空列表多为查询异常，视为可疑跳过
                if (pkgs.isEmpty()) continue
                if (pkg in pkgs) continue // 小窗还在
                // 小窗没了：进程未死就回虚拟屏（判活失败也保守跳过，连续 2 次确认死亡才结束）
                val alive = runCatching { shell.processAlive(pkg) }.getOrNull()
                if (alive == null) continue
                if (!alive) {
                    deadCount++
                    if (deadCount < 2) continue
                    taskEnded = true
                    break
                }
                deadCount = 0
                val result = create(screen.width, screen.height, screen.dpi)
                if (result.error != null) {
                    restoreFailures++
                    if (restoreFailures >= 5) {
                        taskEnded = true
                        break
                    }
                    continue
                }
                // 重建成功后二次校验：用户可能此刻已 release（防"释放后复活"）
                if (!isActive || migratedPackage != pkg) {
                    synchronized(lock) {
                        _display.value?.let { runCatching { it.release() } }
                        _display.value = null
                    }
                    return@launch
                }
                val startCode = startApp(pkg, null)
                if (startCode == PhoneShellService.START_OK) {
                    migratedPackage = null
                    migratedScreen = null
                    return@launch // 回迁完成，监听结束（悬浮球由 startApp 挂钩刷新为运行态）
                }
                // 回迁启动失败：释放刚建的屏避免空屏滞留，累计失败后放弃
                synchronized(lock) {
                    _display.value?.let { runCatching { it.release() } }
                    _display.value = null
                }
                restoreFailures++
                if (restoreFailures >= 5) {
                    taskEnded = true
                    break
                }
            }
            if (taskEnded) {
                synchronized(lock) {
                    migratedPackage = null
                    migratedScreen = null
                    runningPackages.remove(pkg)
                    if (lastStartedPackage == pkg) lastStartedPackage = null
                }
                VirtualDisplayBubble.hide()
                VirtualDisplayNotifier.cancel(context)
                if (_display.value != null) startIdleWatch()
            }
        }
    }

    fun release() {
        // 先停监听、清迁移态（防 watch 协程在此期间重建虚拟屏），再释放 display
        watchJob?.cancel()
        watchJob = null
        synchronized(lock) {
            presentedPackage = null
            presentedScreen = null
            migratedPackage = null
            migratedScreen = null
            runningPackages.clear()
            _display.value?.let { runCatching { it.release() } }
            _display.value = null
            lastLuma = null
            lastStartedPackage = null
        }
        VirtualDisplayNotifier.cancel(context)
        VirtualDisplayBubble.hide()
    }

    override fun close() {
        release()
        watchScope.cancel()
        PhoneShellHub.shutdown()
    }

    data class Result(
        val displayId: Int = -1,
        val width: Int = 0,
        val height: Int = 0,
        val existing: Boolean = false,
        val error: String? = null,
        val message: String? = null,
    )

    data class Status(
        val created: Boolean,
        val displayId: Int,
        val width: Int,
        val height: Int,
        val takeover: Boolean,
        val helperAlive: Boolean,
        val migrated: String?,
        val migratedWidth: Int,
        val migratedHeight: Int,
    )

    data class ScreenSpec(val width: Int, val height: Int, val dpi: Int)

    private companion object {
        /** 滑动时长收敛范围，与助手侧旧 swipe 注入的收敛一致 */
        const val SWIPE_MIN_MS = 100
        const val SWIPE_MAX_MS = 5_000

        /** 滑动笔画的采样点数（含起止点）：起止之间再线性插 5 个中间点 */
        const val SWIPE_STROKE_POINTS = 7
    }
}
