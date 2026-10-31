package io.github.mangi.eta.agent.phone.display

import android.content.Context
import android.view.Surface
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun injectTap(x: Int, y: Int): Boolean = _display.value?.let {
        runCatching { PhoneShellHub.shell?.injectTap(it.displayId, x, y) }.getOrDefault(false)
    } ?: false

    /**
     * 在虚拟屏上注入一次滑动：起止点之间线性插值 5 个中间点（含首尾共 7 个），
     * 总时长均分到各段，经助手的 [IPhoneShell.injectStroke] 逐点注入，比一次性
     * swipe 更接近真实手指轨迹。助手不可用（未连接或仍是旧接口、笔画没注入成功）
     * 时退回旧的一次性 injectSwipe。
     */
    fun injectSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean {
        val display = _display.value ?: return false
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
    }

    fun injectKey(keyCode: Int): Boolean = _display.value?.let {
        runCatching { PhoneShellHub.shell?.injectKey(it.displayId, keyCode) }.getOrDefault(false)
    } ?: false

    /** 在虚拟屏上启动 App；成功时记录包名并刷新常驻通知正文 */
    fun startApp(packageName: String, activity: String?): Int {
        val display = _display.value ?: return 2
        val code = runCatching { PhoneShellHub.shell?.startApp(display.displayId, packageName, activity) }
            .getOrDefault(2) ?: 2
        if (code == PhoneShellService.START_OK) {
            lastStartedPackage = packageName
            refreshNotification()
        }
        return code
    }

    /** 创建虚拟屏成功 / 启动 App 成功后发布或刷新常驻通知；通知失败不影响工具流程 */
    private fun refreshNotification() {
        try {
            VirtualDisplayNotifier.show(context, this, lastStartedPackage)
        } catch (t: Throwable) {
            logger.error("virtual display notification failed", t)
        }
    }

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
        )
    }

    /** 以 MIUI 原生小窗打开真屏侧接管窗（镜像虚拟屏 + 触摸透传）。 */
    fun openTakeoverWindow(): String? {
        val intent = android.content.Intent(context, VirtualDisplayActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return MiuiSmallWindow.start(context, intent, context.packageName)
    }

    fun release() {
        synchronized(lock) {
            _display.value?.let { runCatching { it.release() } }
            _display.value = null
            lastLuma = null
            lastStartedPackage = null
        }
        VirtualDisplayNotifier.cancel(context)
    }

    override fun close() {
        release()
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
    )

    private companion object {
        /** 滑动时长收敛范围，与助手侧旧 swipe 注入的收敛一致 */
        const val SWIPE_MIN_MS = 100
        const val SWIPE_MAX_MS = 5_000

        /** 滑动笔画的采样点数（含起止点）：起止之间再线性插 5 个中间点 */
        const val SWIPE_STROKE_POINTS = 7
    }
}
