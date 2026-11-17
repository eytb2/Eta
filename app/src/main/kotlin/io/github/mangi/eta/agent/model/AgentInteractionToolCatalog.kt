package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 人机协同决策工具 schema。
 *
 * 与其他工具不同，`ask_question` 的“执行结果”来自用户本人：模型提出问题后本轮结束，
 * 用户在卡片上选择，选择结果作为下一条用户消息回到会话。
 */
internal object AgentInteractionToolCatalog {
    const val ASK_QUESTION = "ask_question"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = ASK_QUESTION,
                description = "向用户提出结构化多选或单选问题以确认设计决策、参数或分支，等待用户选择后继续。" +
                    "仅在你无法从任务描述、代码或合理默认值中确定答案，且答错会改变后续工作时使用；" +
                    "一次最多 4 个问题，每个问题 2–8 个候选项。" +
                    "调用后必须立即结束本轮输出，不要自行假定答案或继续执行后续步骤。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "questions",
                            JSONObject()
                                .put("type", "array")
                                .put("minItems", 1)
                                .put("maxItems", 4)
                                .put("description", "需要用户决策的问题列表，按重要性排序")
                                .put(
                                    "items",
                                    JSONObject()
                                        .put("type", "object")
                                        .put(
                                            "properties",
                                            JSONObject()
                                                .put(
                                                    "question",
                                                    JSONObject()
                                                        .put("type", "string")
                                                        .put("maxLength", 500)
                                                        .put("description", "完整、明确的问题描述"),
                                                )
                                                .put(
                                                    "options",
                                                    JSONObject()
                                                        .put("type", "array")
                                                        .put("minItems", 2)
                                                        .put("maxItems", 8)
                                                        .put("uniqueItems", true)
                                                        .put("description", "候选选项文本，不含序号；用户可另行补充自定义答案")
                                                        .put(
                                                            "items",
                                                            JSONObject()
                                                                .put("type", "string")
                                                                .put("maxLength", 200),
                                                        ),
                                                )
                                                .put(
                                                    "is_multi_select",
                                                    JSONObject()
                                                        .put("type", "boolean")
                                                        .put("default", false)
                                                        .put("description", "true 表示可同时选择多个选项，默认 false 为单选"),
                                                ),
                                        )
                                        .put("required", JSONArray().put("question").put("options")),
                                ),
                        ),
                    )
                    .put("required", JSONArray().put("questions")),
            ),
        )
    }
}
