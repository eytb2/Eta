package io.github.mangi.eta.agent.phone.display

import android.view.KeyEvent
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject

/**
 * Agent 的虚拟屏工具执行器：模型可见的 virtual_display_* 工具集合。
 * 默认关闭，需在设置里启用；全部依赖 Root。
 */
internal class VirtualDisplayTools(
    context: android.content.Context,
    logger: AgentLogger,
    rootExecutor: BoundedRootCommandExecutor,
) : AutoCloseable {
    private val controller = VirtualDisplayController(context.applicationContext, logger, rootExecutor)

    val names = setOf(
        "virtual_display_create", "virtual_display_start_app", "virtual_display_screenshot",
        "virtual_display_tap", "virtual_display_swipe", "virtual_display_key",
        "virtual_display_release", "virtual_display_status",
    )

    fun execute(name: String, arguments: JSONObject): JSONObject {
        if (name !in names) return error("UNKNOWN_TOOL", "未知虚拟屏工具")
        return try {
            when (name) {
                "virtual_display_create" -> create(arguments)
                "virtual_display_start_app" -> startApp(arguments)
                "virtual_display_screenshot" -> screenshot()
                "virtual_display_tap" -> tap(arguments)
                "virtual_display_swipe" -> swipe(arguments)
                "virtual_display_key" -> key(arguments)
                "virtual_display_release" -> release()
                else -> status()
            }
        } catch (t: Throwable) {
            error("TOOL_ERROR", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun create(args: JSONObject): JSONObject {
        val width = args.optInt("width", DEFAULT_WIDTH).coerceIn(MIN_SIDE, MAX_SIDE)
        val height = args.optInt("height", DEFAULT_HEIGHT).coerceIn(MIN_SIDE, MAX_SIDE)
        val dpi = args.optInt("dpi", DEFAULT_DPI).coerceIn(MIN_DPI, MAX_DPI)
        val result = controller.create(width, height, dpi)
        return if (result.error != null) error(result.error!!, result.message ?: "创建失败")
        else JSONObject()
            .put("ok", true)
            .put("display_id", result.displayId)
            .put("width", result.width)
            .put("height", result.height)
            .put("existing", result.existing)
            .put(
                "note",
                if (result.existing) "虚拟屏已存在，沿用现有尺寸" else "已创建；用 virtual_display_start_app 启动 App 后再截图或注入",
            )
    }

    private fun startApp(args: JSONObject): JSONObject {
        val packageName = args.optString("package_name").ifBlank { return error("INVALID_ARGUMENT", "缺少 package_name") }
        val activity = args.optString("activity").ifBlank { null }
        return when (val code = controller.startApp(packageName, activity)) {
            PhoneShellService.START_OK -> JSONObject().put("ok", true).put("started", true)
            PhoneShellService.START_NOT_FOUND -> error("START_NOT_FOUND", "找不到该包的可启动入口")
            else -> error("START_FAILED", "启动失败（code=$code；屏未创建或助手不可用）")
        }
    }

    private fun screenshot(): JSONObject {
        val file = controller.screenshot()
            ?: return error("NO_FRAME", "还没有可用画面；先启动 App 并稍等，或虚拟屏正被接管小窗占用")
        return JSONObject()
            .put("ok", true)
            .put("path", file.absolutePath)
            .put("note", "PNG 已保存到应用缓存；用 read_image 读取内容")
    }

    private fun tap(args: JSONObject): JSONObject {
        val x = args.optInt("x", -1)
        val y = args.optInt("y", -1)
        if (x < 0 || y < 0) return error("INVALID_ARGUMENT", "坐标无效")
        return injectResult(controller.injectTap(x, y), "tap ($x,$y)")
    }

    private fun swipe(args: JSONObject): JSONObject {
        val start = args.optJSONObject("start") ?: return error("INVALID_ARGUMENT", "缺少 start")
        val end = args.optJSONObject("end") ?: return error("INVALID_ARGUMENT", "缺少 end")
        val duration = args.optInt("duration_ms", 300)
        return injectResult(
            controller.injectSwipe(
                start.optInt("x"), start.optInt("y"), end.optInt("x"), end.optInt("y"), duration,
            ),
            "swipe",
        )
    }

    private fun key(args: JSONObject): JSONObject {
        val button = args.optString("button").ifBlank { return error("INVALID_ARGUMENT", "缺少 button") }
        val keyCode = KEY_BUTTONS[button.lowercase()]
            ?: return error("INVALID_ARGUMENT", "不支持按键 $button；可用：${KEY_BUTTONS.keys.joinToString()} ")
        return injectResult(controller.injectKey(keyCode), "key $button")
    }

    private fun release(): JSONObject {
        controller.release()
        return JSONObject().put("ok", true).put("released", true)
    }

    private fun status(): JSONObject {
        val s = controller.status()
        return JSONObject()
            .put("created", s.created)
            .put("display_id", s.displayId)
            .put("width", s.width)
            .put("height", s.height)
            .put("takeover_active", s.takeover)
            .put("helper_alive", s.helperAlive)
    }

    private fun injectResult(ok: Boolean, what: String): JSONObject =
        if (ok) JSONObject().put("ok", true).put("action", what)
        else error("INJECT_FAILED", "$what 未注入成功（屏未创建或注入被系统拒绝）")

    override fun close() {
        controller.close()
    }

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("error", code).put("message", message)

    companion object {
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 1600
        const val DEFAULT_DPI = 320
        private const val MIN_SIDE = 320
        private const val MAX_SIDE = 2560
        private const val MIN_DPI = 120
        private const val MAX_DPI = 640

        /** 模型侧按钮名 → keyCode，与助手侧白名单一致。 */
        val KEY_BUTTONS: Map<String, Int> = mapOf(
            "back" to KeyEvent.KEYCODE_BACK,
            "home" to KeyEvent.KEYCODE_HOME,
            "menu" to KeyEvent.KEYCODE_MENU,
            "enter" to KeyEvent.KEYCODE_ENTER,
            "delete" to KeyEvent.KEYCODE_DEL,
            "tab" to KeyEvent.KEYCODE_TAB,
            "escape" to KeyEvent.KEYCODE_ESCAPE,
            "space" to KeyEvent.KEYCODE_SPACE,
            "dpad_up" to KeyEvent.KEYCODE_DPAD_UP,
            "dpad_down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "dpad_left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "dpad_right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "dpad_center" to KeyEvent.KEYCODE_DPAD_CENTER,
            "page_up" to KeyEvent.KEYCODE_PAGE_UP,
            "page_down" to KeyEvent.KEYCODE_PAGE_DOWN,
        )
    }
}
