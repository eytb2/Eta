package io.github.mangi.eta.agent.phone.display
import kotlin.system.exitProcess

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 助手进程里的虚拟屏服务，自 mika 的 ShellService 移植并按 Root 简化：
 * 只保留主屏占用检查、建屏 / 输入注入 / 定屏启动（不做剪贴板、静音等能力）。
 * 隐藏接口一律反射调用，失败退回等效命令（am start --display / input -d）。
 *
 * @param allowedUid 只接受这个 uid（Eta App）的调用；Root 助手本身 uid 为 0。
 */
internal class PhoneShellService(
    private val allowedUid: Int,
    private val context: Context,
) : IPhoneShell.Stub() {
    private val displays = HashMap<Int, VirtualDisplay>()

    // 不能用 getSystemService：那样拿到的 DisplayManager 绑定的是系统上下文，
    // 包名与调用 uid 对不上；按 mika 的做法反射构造并配 Root 的上下文。
    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int, name: String?): Int = try {
        if (width <= 0 || height <= 0 || dpi <= 0) return -1
        val vd = displayManager.createVirtualDisplay(
            name ?: "eta-vdisplay", width, height, dpi, surface, displayFlags(),
        )
        val id = vd.display.displayId
        if (id < 0) return -1
        synchronized(displays) { displays[id] = vd }
        setImePolicy(id)
        id
    } catch (t: Throwable) {
        Log.e(TAG, "createDisplay failed", t)
        -1
    }

    /** 只操作这个服务自己建的屏；调用方传错屏号（比如主屏）不会打扰用户。 */
    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    override fun setDisplaySurface(displayId: Int, surface: Surface?) {
        val vd = synchronized(displays) { displays[displayId] } ?: return
        runCatching {
            VirtualDisplay::class.java.getMethod("setSurface", Surface::class.java).invoke(vd, surface)
        }.onFailure { Log.w(TAG, "setSurface failed", it) }
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displays) { displays.remove(displayId) }?.release()
    }

    override fun startApp(displayId: Int, packageName: String?, activity: String?): Int {
        if (!ownDisplay(displayId) || packageName.isNullOrBlank()) return START_ERROR
        // 主屏占用保护：目标 App 正被用户在主屏看着时不启动，免得把用户的界面挪走或打断；
        // 看不清主屏状态时宁可不启动，也不冒险打扰用户（自 mika 的 inUseOnMainScreen 移植）
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        return try {
            val intent = if (activity.isNullOrBlank()) {
                context.packageManager.getLaunchIntentForPackage(packageName) ?: return START_NOT_FOUND
            } else {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(packageName, activity)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
            val ok = try {
                val start = taskManager.javaClass.methods.first {
                    it.name == "startActivityAsUser" && it.parameterTypes.size == 12
                }
                // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
                //  requestCode, flags, profilerInfo, options, userId)
                val result = start.invoke(
                    taskManager, null, SHELL_PACKAGE, null, intent, null, null, null,
                    0, 0, null, options, USER_CURRENT,
                ) as Int
                if (result < 0) Log.w(TAG, "startActivityAsUser($packageName) -> $result")
                result >= 0
            } catch (t: Throwable) {
                Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
                val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
                exec("am", "start", "--display", "$displayId", "-n", component) == 0
            }
            if (ok) START_OK else START_ERROR
        } catch (t: Throwable) {
            Log.e(TAG, "startApp failed", t)
            START_ERROR
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        // 白名单：导航与编辑键。即便调用方出错，也不从这里发电源、音量等整机按键。
        if (keyCode !in ALLOWED_KEYS) {
            Log.w(TAG, "refused key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0 }
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0 }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    override fun injectSwipe(
        displayId: Int,
        startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int,
    ): Boolean {
        if (!ownDisplay(displayId)) return false
        val duration = durationMs.coerceIn(SWIPE_MIN_MS, SWIPE_MAX_MS)
        return try {
            val steps = (duration / SWIPE_STEP_MS).coerceAtLeast(2)
            val downAt = SystemClock.uptimeMillis()
            fun event(offset: Long, x: Float, y: Float, action: Int): MotionEvent {
                val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
                return MotionEvent.obtain(
                    downAt, downAt + offset, action, 1, arrayOf(props), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
            }
            if (!inject(event(0, startX.toFloat(), startY.toFloat(), MotionEvent.ACTION_DOWN), displayId)) {
                return exec(
                    "input", "-d", "$displayId", "swipe",
                    "$startX", "$startY", "$endX", "$endY", "$duration",
                ) == 0
            }
            for (step in 1 until steps) {
                val t = step.toFloat() / steps
                SystemClock.sleep(SWIPE_STEP_MS)
                inject(
                    event(
                        step * SWIPE_STEP_MS.toLong(),
                        startX + (endX - startX) * t, startY + (endY - startY) * t,
                        MotionEvent.ACTION_MOVE,
                    ),
                    displayId,
                )
            }
            SystemClock.sleep(SWIPE_STEP_MS)
            val up = event(steps * SWIPE_STEP_MS.toLong(), endX.toFloat(), endY.toFloat(), MotionEvent.ACTION_UP)
            inject(up, displayId) || inject(up, displayId)
        } catch (t: Throwable) {
            Log.w(TAG, "injectSwipe failed", t)
            false
        }
    }

    /**
     * 一根手指的一笔：按下、按 [times] 逐点移动、在最后一点抬起；每个事件到了它的时刻
     * 才注入（事件时间就是那一刻），App 看到的速度就是调用方排好的快慢。只发到本服务
     * 自己建的屏。按下没注入成功返回 false（调用方改走别的路）；按下之后中途哪个点没
     * 注入成功也照样往下走，最后一定补发抬起，不让手指一直按着。
     */
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
            times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }
        ) return false
        val down = SystemClock.uptimeMillis()
        fun event(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
            return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    /**
     * 以 MIUI 小窗启动真屏组件。Root 身份手动构造 ActivityOptions
     * （windowingMode=5 + 居中 bounds + setMiuiConfigFlag(2)，不查任何 provider），
     * 再经 IActivityTaskManager.startActivityAsUser 以 launcher 包名启动，绕过后台限制。
     */
    override fun startSmallWindow(packageName: String?, activity: String?): Int {
        if (packageName.isNullOrBlank()) return START_ERROR
        if (packageName in miuiFreeformBlacklist()) return START_BLACKLISTED
        return try {
            val intent = if (activity.isNullOrBlank()) {
                context.packageManager.getLaunchIntentForPackage(packageName) ?: return START_NOT_FOUND
            } else {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(packageName, activity)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.component == null) return START_NOT_FOUND
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val dm = context.resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            val fw = (w * 0.94f).toInt()
            val fh = (h * 0.62f).toInt()
            val top = (h * 0.28f).toInt()
            val bounds = android.graphics.Rect((w - fw) / 2, top, (w - fw) / 2 + fw, top + fh)
            val options = ActivityOptions.makeBasic()
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, 5)
            options.setLaunchBounds(bounds)
            runCatching {
                val flag = ActivityOptions::class.java.getDeclaredMethod("setMiuiConfigFlag", Int::class.javaPrimitiveType)
                flag.isAccessible = true
                flag.invoke(options, 2)
            }
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            val start = atm.javaClass.methods.firstOrNull {
                it.name == "startActivityAsUser" && it.parameterCount == 12
            } ?: return START_ERROR
            val result = start.invoke(
                atm, null, MIUI_LAUNCHER, null, intent, null, null, null,
                0, 0, null, options.toBundle(), -2,
            ) as Int
            if (result >= 0) START_OK else START_ERROR
        } catch (t: Throwable) {
            Log.w(TAG, "startSmallWindow failed", t)
            START_ERROR
        }
    }

    /**
     * 把这块屏上的 App 迁移为 MIUI 原生小窗（真屏）：MIUI 小窗 options 只对新建任务
     * 生效，所以先结束目标在虚拟屏与主屏后台的任务（保留进程，推送不断），正在主屏
     * 被用户看着时拒绝迁移；随后复用 [startSmallWindow] 新建小窗任务。
     * packageName 为空时取该屏最上面的任务。
     */
    override fun migrateDisplayAppToSmallWindow(displayId: Int, packageName: String?, activity: String?): Int {
        if (!ownDisplay(displayId)) return START_ERROR
        val target = packageName?.takeIf { it.isNotBlank() }
            ?: tasksOn(displayId).firstOrNull()?.let { task -> packagesOf(task).firstOrNull() }
            ?: return START_NOT_FOUND
        if (target in miuiFreeformBlacklist()) return START_BLACKLISTED
        // 先确认启动入口存在，避免任务已结束却启动不了、App 卡在后台的中间态
        val launch = context.packageManager.getLaunchIntentForPackage(target)?.component
            ?: return START_NOT_FOUND
        if (inUseOnMainScreen(target)) return START_IN_USE_ON_MAIN
        // 移除目标在虚拟屏与主屏后台的旧任务；小窗 options 只有新建任务才会应用 bounds
        val mine = (tasksOn(Display.DEFAULT_DISPLAY) + tasksOn(displayId))
            .filter { target in packagesOf(it) }
        val taskIds = mine.mapNotNull { runCatching { TASK_INFO.getField("taskId").getInt(it) }.getOrNull() }
        var ready = taskIds.isEmpty() // 本就没有旧任务，直接新建即可
        for (taskId in taskIds) {
            if (removeTask(taskId, target)) ready = true
        }
        if (!ready) return START_ERROR
        return startSmallWindow(target, activity ?: launch.className)
    }

    override fun releaseAll() {
        synchronized(displays) { displays.values.toList().also { displays.clear() } }.forEach { vd ->
            runCatching { vd.release() }
        }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Binder 可能被别的进程拿到：接口方法只允许 Eta App 调用。
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION &&
            Binder.getCallingUid() != allowedUid
        ) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        return super.onTransact(code, data, reply, flags)
    }

    /** 任务 [task] 的 baseActivity / topActivity 所属包名 */
    private fun packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
        (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
    }

    /** 该包在主屏有可见任务，或者就是主屏最上面的任务 */
    private fun inUseOnMainScreen(packageName: String): Boolean {
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
            packageName in packagesOf(task) && (visibleField?.getBoolean(task) ?: (index == 0))
        }
    }

    /**
     * 反射 IActivityTaskManager.removeTask 结束任务：任务消失、进程保留（推送不断）。
     * 反射失败退回 force-stop 整包（后台服务会重启，代价可接受）。
     */
    private fun removeTask(taskId: Int, fallbackPackage: String): Boolean {
        val ok = runCatching {
            taskManager.javaClass.methods.firstOrNull {
                it.name == "removeTask" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
            }?.let { m ->
                if (m.parameterTypes.size == 1) {
                    m.invoke(taskManager, taskId) as Boolean
                } else {
                    m.invoke(taskManager, taskId, true) as Boolean
                }
            }
        }.onFailure { Log.w(TAG, "removeTask($taskId) failed", it) }.getOrNull()
        if (ok == true) return true
        return exec("am", "force-stop", fallbackPackage) == 0
    }

    /**
     * IActivityTaskManager.getTasks：按最近使用排序，Root 助手能拿到全部任务。
     * API 33+ 有带 displayId 的重载，优先用它；更早的版本取全部再按 TaskInfo.displayId 过滤。
     */
    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    /** 只有 DOWN 注入失败才退回命令；DOWN 已送达后退回会造成一次多余的完整动作。 */
    private inline fun injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean {
        if (!inject(down, displayId)) return fallback()
        return inject(up, displayId) || inject(up, displayId)
    }

    private fun displayFlags(): Int {
        var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        // OWN_FOCUS 会让虚拟屏成为焦点屏、清空主屏焦点；STEAL_TOP_FOCUS_DISABLED 抑制这一点
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }

    /** IWindowManager.setDisplayImePolicy：输入法弹在虚拟屏上（LOCAL），不打扰主屏。 */
    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
                .invoke(wm, displayId, IME_POLICY_LOCAL)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        // API 34 起注入入口在 InputManagerGlobal，更早在 InputManager
        val manager = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        manager!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, INJECT_MODE_ASYNC) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    private fun exec(vararg cmd: String): Int {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1
        }
        val reader = Thread { runCatching { p.inputStream.readBytes() } }.apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return -1
        }
        reader.join(1_000)
        return p.exitValue()
    }

    /** Root 助手对外报 shell 包名（uid 0 具备全部权限，包名仅用于系统侧一致性核对）。 */
    internal class ShellPackageContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
    }

    companion object {
        private const val TAG = "EtaPhoneShell"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val MIUI_LAUNCHER = "com.miui.home"
        private const val MAX_TASKS = 100
        private const val TAP_MS = 50L
        // injectStroke 一笔的上限：点数、从按下到抬起的毫秒数
        private const val STROKE_MAX_POINTS = 1_024
        private const val STROKE_MAX_MS = 10_000L
        private const val SWIPE_MIN_MS = 100
        private const val SWIPE_MAX_MS = 5_000
        private const val SWIPE_STEP_MS = 16L
        private const val CMD_TIMEOUT_S = 10L
        private const val USER_CURRENT = -2 // UserHandle.USER_CURRENT
        private const val INJECT_MODE_ASYNC = 0
        private const val VIRTUAL_KEYBOARD = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD

        const val START_OK = 0
        const val START_NOT_FOUND = 1
        const val START_ERROR = 2
        const val START_BLACKLISTED = 3

        /** 目标 App 正在主屏（display 0）可见使用中，为不打扰用户拒绝拉到虚拟屏 */
        const val START_IN_USE_ON_MAIN = 4

        /** MIUI 官方小窗黑名单（framework 静态 FREEFORM_BLACK_LIST + AMS 云控名单），读失败视为空。 */
        private fun miuiFreeformBlacklist(): Set<String> = runCatching {
            val adapter = Class.forName("android.util.MiuiMultiWindowAdapter")
            @Suppress("UNCHECKED_CAST")
            val staticList = adapter.getField("FREEFORM_BLACK_LIST").get(null) as? List<String> ?: emptyList()
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
            val cloudList = runCatching {
                @Suppress("UNCHECKED_CAST")
                am.javaClass.methods.firstOrNull { it.name == "getFreeformBlackList" && it.parameterCount == 0 }
                    ?.invoke(am) as? List<String> ?: emptyList()
            }.getOrDefault(emptyList())
            staticList + cloudList
        }.getOrDefault(emptyList()).toSet()

        private val ALLOWED_KEYS = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*（部分为隐藏常量）
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0
        private const val IME_POLICY_LOCAL = 0

        /** android.app.TaskInfo，反射取字段用 */
        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }

        /** 找出以 argv0 为进程名的其它进程，避免残留多个助手。 */
        internal fun killOtherInstances(argv0: String) {
            val me = Process.myPid()
            File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (pid == me) return@forEach
                val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@forEach
                val name = String(cmdline.takeWhile { it != 0.toByte() }.toByteArray())
                if (name == argv0) runCatching { Process.killProcess(pid) }
            }
        }
    }
}
