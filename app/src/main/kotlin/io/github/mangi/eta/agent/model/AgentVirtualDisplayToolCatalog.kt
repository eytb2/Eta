package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.phone.display.VirtualDisplayTools
import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明模型可见的虚拟屏工具及其 JSON Schema；执行在
 * [io.github.mangi.eta.agent.tool.AgentLocalTools] 侧完成。默认整组关闭。
 */
internal object AgentVirtualDisplayToolCatalog {
    val names: Set<String> = setOf(
        "virtual_display_create", "virtual_display_start_app", "virtual_display_screenshot",
        "virtual_display_tap", "virtual_display_swipe", "virtual_display_key",
        "virtual_display_release", "virtual_display_status", "virtual_display_takeover",
    )

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "virtual_display_create",
                description = "创建一块虚拟屏（默认 720×1600、320dpi）。已存在时返回现有屏信息。需要 Root；默认关闭，需在设置里启用。",
                parameters = parameters(
                    JSONObject()
                        .put("width", integer("虚拟屏宽，默认 720，范围 320–2560"))
                        .put("height", integer("虚拟屏高，默认 1600，范围 320–2560"))
                        .put("dpi", integer("密度，默认 320，范围 120–640")),
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "virtual_display_start_app",
                description = "在虚拟屏上启动指定 App，不打扰真屏当前界面。先 virtual_display_create。",
                parameters = parameters(
                    JSONObject()
                        .put("package_name", text("目标包名"))
                        .put("activity", text("可选的 Activity 完整类名；缺省用 launcher 入口")),
                    required = arrayOf("package_name"),
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "virtual_display_screenshot",
                description = "截取虚拟屏当前画面，PNG 保存到应用缓存并返回路径；用 read_image 查看内容。画面被接管小窗占用时不可用。",
                parameters = parameters(JSONObject()),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "virtual_display_tap",
                description = "在虚拟屏上注入一次点击（坐标为虚拟屏逻辑坐标）。",
                parameters = parameters(
                    JSONObject()
                        .put("x", integer("虚拟屏 X 坐标"))
                        .put("y", integer("虚拟屏 Y 坐标")),
                    required = arrayOf("x", "y"),
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "virtual_display_swipe",
                description = "在虚拟屏上注入一次滑动。",
                parameters = parameters(
                    JSONObject()
                        .put("start", point("起点 {x,y}"))
                        .put("end", point("终点 {x,y}"))
                        .put("duration_ms", integer("滑动时长，默认 300 毫秒")),
                    required = arrayOf("start", "end"),
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "virtual_display_key",
                description = "向虚拟屏注入按键。可用按钮：${VirtualDisplayTools.KEY_BUTTONS.keys.joinToString(", ")}。",
                parameters = parameters(
                    JSONObject().put("button", text("按钮名，如 back、home、enter")),
                    required = arrayOf("button"),
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "virtual_display_takeover",
                description = "把虚拟屏上正在运行的 App 迁移为 MIUI 原生小窗（真屏）：原生渲染与触摸、无缩放裁切，会话状态保留；成功后虚拟屏释放。App 正在主屏使用中、或在小窗黑名单时拒绝迁移。需要 Root。",
                parameters = parameters(JSONObject()),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "virtual_display_present_to_user",
                description = "present_to_user：把驻留在 MIUI 小窗里的任务无损移交回物理主屏全屏前台（页面状态保留，不重启）。用于到达付款确认、扫码、验证码或 FLAG_SECURE 安全页等需要本人操作的临门一脚场景。要求任务当前处于小窗驻留状态。需要 Root。",
                parameters = parameters(JSONObject()),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "virtual_display_release",
                description = "释放虚拟屏；其上的 App 一并结束。",
                parameters = parameters(JSONObject()),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "virtual_display_status",
                description = "查询虚拟屏状态：是否创建、尺寸、是否被接管小窗占用、Root 助手是否存活。",
                parameters = parameters(JSONObject()),
            ),
        )
    }

    private fun parameters(properties: JSONObject, vararg required: String): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required.toList()))
        .put("additionalProperties", false)

    private fun point(description: String): JSONObject = JSONObject()
        .put("type", "object")
        .put("description", description)
        .put("properties", JSONObject()
            .put("x", JSONObject().put("type", "integer"))
            .put("y", JSONObject().put("type", "integer")))
        .put("required", JSONArray(listOf("x", "y")))

    private fun text(description: String): JSONObject = JSONObject().put("type", "string").put("description", description)
    private fun integer(description: String): JSONObject = JSONObject().put("type", "integer").put("description", description)
}
