package io.github.mangi.eta.agent.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * 非终端工具附带给界面渲染的结构化展示载荷。
 *
 * 复用工具消息既有的 `command` 通道：该字段对终端工具承载命令原文，对 subagent 承载活动快照，
 * 已完整穿过 Runtime IPC 与会话持久化，因此这里不需要新增事件字段或数据库列。
 *
 * 界面按工具名分派后再解码对应类型；解码失败一律退回纯文本摘要，
 * 不会让一条旧消息或一次格式变更把卡片渲染成空白。
 */
internal object AgentToolDisplayPayload {
    const val TOOL_ASK_QUESTION = "ask_question"

    fun encode(payload: EditDiffPayload): String = json.encodeToString(EditDiffPayload.serializer(), payload)

    fun encode(payload: AskQuestionPayload): String = json.encodeToString(AskQuestionPayload.serializer(), payload)

    fun decodeEditDiff(raw: String?): EditDiffPayload? =
        decode<EditDiffPayload>(raw, EditDiffPayload.serializer())?.takeIf { it.diff.isNotBlank() }

    fun decodeAskQuestion(raw: String?): AskQuestionPayload? =
        decode<AskQuestionPayload>(raw, AskQuestionPayload.serializer())?.takeIf { it.questions.isNotEmpty() }

    private fun <T> decode(
        raw: String?,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T? {
        // 终端命令原文与 subagent 快照也走这个字段；先排除明显不是本载荷的文本再尝试解析。
        if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
        return runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

/**
 * `edit_file` 的统一差异视图。
 *
 * 增删行数不入库：界面逐行着色时顺带就能数出来，另存一份只会与差异正文漂移。
 * [truncated] 无法从正文推出，因此显式携带。
 */
@Serializable
internal data class EditDiffPayload(
    val diff: String,
    val truncated: Boolean = false,
)

/** `ask_question` 的结构化提问。 */
@Serializable
internal data class AskQuestionPayload(
    val questions: List<AskQuestionItem> = emptyList(),
)

/** 单个待决策问题及其候选项。 */
@Serializable
internal data class AskQuestionItem(
    val question: String,
    val options: List<String> = emptyList(),
    val isMultiSelect: Boolean = false,
)

/**
 * `ask_question` 的参数校验与回执构造。
 *
 * 提问本身不阻塞 Agent loop：结果里明确要求模型本轮停下等待，用户的选择随后作为一条普通
 * 用户消息回到会话，Agent 因此无缝继续。相比在 Runtime 进程里挂起等待跨进程回调，
 * 这条路径不会占用工具执行线程，也不会因界面重建或进程重启而丢失提问。
 */
internal object AskQuestionTool {

    private const val MAX_QUESTIONS = 4
    private const val MAX_OPTIONS = 8
    private const val MAX_QUESTION_CHARS = 500
    private const val MAX_OPTION_CHARS = 200

    fun errorResult(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    /** 校验并归一化模型给出的问题列表；不合法时返回 null，由调用方给出明确错误回执。 */
    fun parse(args: JSONObject): List<AskQuestionItem>? {
        val array = args.optJSONArray("questions") ?: return null
        if (array.length() == 0 || array.length() > MAX_QUESTIONS) return null
        val items = ArrayList<AskQuestionItem>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: return null
            val question = entry.optString("question").trim()
            if (question.isEmpty() || question.length > MAX_QUESTION_CHARS) return null
            val rawOptions = entry.optJSONArray("options") ?: return null
            if (rawOptions.length() < 2 || rawOptions.length() > MAX_OPTIONS) return null
            val options = ArrayList<String>(rawOptions.length())
            for (optionIndex in 0 until rawOptions.length()) {
                val option = rawOptions.optString(optionIndex).trim()
                if (option.isEmpty() || option.length > MAX_OPTION_CHARS) return null
                if (option !in options) options += option
            }
            // 去重后仍需保留至少两个候选，否则“选择”没有意义。
            if (options.size < 2) return null
            items += AskQuestionItem(
                question = question,
                options = options,
                isMultiSelect = entry.optBoolean("is_multi_select", false),
            )
        }
        return items
    }

    /** 工具回执：告诉模型提问已呈现给用户，本轮必须停下等待用户选择。 */
    fun pendingResult(items: List<AskQuestionItem>): String = JSONObject()
        .put("ok", true)
        .put("tool", AgentToolDisplayPayload.TOOL_ASK_QUESTION)
        .put("status", "awaiting_user")
        .put("question_count", items.size)
        .put("questions", items.map { it.question })
        .put(
            "message",
            "问题已以交互式卡片呈现给用户，正在等待用户选择。" +
                "请立即结束本轮输出，不要自行假定答案、不要重复提问、不要继续执行后续步骤；" +
                "用户的选择会作为下一条消息送达，届时再继续。",
        )
        .toString()

    /**
     * 把用户在卡片上的选择格式化为回给 Agent 的文本。
     * 未作答的问题显式标注，避免模型把沉默当成默认同意。
     */
    fun formatAnswer(items: List<AskQuestionItem>, selections: List<Set<Int>>): String =
        items.mapIndexed { index, item ->
            val chosen = selections.getOrNull(index).orEmpty()
                .sorted()
                .mapNotNull { optionIndex -> item.options.getOrNull(optionIndex) }
            val answer = if (chosen.isEmpty()) "（未选择）" else chosen.joinToString("、")
            "${item.question}\n已确认选择：$answer"
        }.joinToString("\n\n")
}
