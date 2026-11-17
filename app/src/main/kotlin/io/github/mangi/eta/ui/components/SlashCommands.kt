package io.github.mangi.eta.ui.components

import androidx.annotation.StringRes
import io.github.mangi.eta.R

/**
 * 输入框快捷指令。
 *
 * 指令是界面层的输入语法，不是模型工具：`/compact` 直接触发既有的上下文压缩动作，
 * `/plan` 只是在用户消息前拼接规划提示词，`/clear` 与 `/help` 完全不出应用。
 */
internal enum class SlashCommand(
    /** 指令名，不含前导 `/`。 */
    val token: String,
    /** 是否接受参数；接受参数的指令点击后补全为 `/token ` 并留在输入框继续写。 */
    val takesArgument: Boolean,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
) {
    PLAN("plan", true, R.string.slash_command_plan, R.string.slash_command_plan_hint),
    COMPACT("compact", false, R.string.slash_command_compact, R.string.slash_command_compact_hint),
    CLEAR("clear", false, R.string.slash_command_clear, R.string.slash_command_clear_hint),
    HELP("help", false, R.string.slash_command_help, R.string.slash_command_help_hint),
    ;

    /** 补全到输入框的完整文本。 */
    val completion: String get() = "/$token" + if (takesArgument) " " else ""

    companion object {
        const val PREFIX = "/"

        /** 按名字精确查找，未知指令返回 null。 */
        fun byToken(token: String): SlashCommand? = entries.firstOrNull { it.token == token }
    }
}

/** 提交拦截结果。[PassThrough] 表示不是指令，按普通消息发送。 */
internal sealed interface SlashCommandEffect {
    /** 不是指令，或指令带了无法本地处理的内容，按原样发送。 */
    data class PassThrough(val text: String) : SlashCommandEffect

    /** 触发上下文压缩，不向模型发送任何消息。 */
    data object CompactContext : SlashCommandEffect

    /** 只清空输入框草稿。 */
    data object ClearDraft : SlashCommandEffect

    /** 展示指令帮助。 */
    data object ShowHelp : SlashCommandEffect

    /** 把带参数的指令补全回输入框，等待用户写完参数。 */
    data class CompleteDraft(val text: String) : SlashCommandEffect

    /** 以规划模式发送：由调用方拼接提示词前缀后再提交。 */
    data class SendPlan(val task: String) : SlashCommandEffect
}

/**
 * 快捷指令的输入解析。纯函数，便于单测，也避免把 Compose 状态带进判断逻辑。
 */
internal object SlashCommands {

    /**
     * 输入框内容对应的候选指令。
     *
     * 只在“`/` 开头且尚未输入空格”时返回结果：一旦写下参数或正文，浮窗就该让位给输入本身。
     * 不以 `/` 开头的普通文本（例如路径 `/workspace`）不参与匹配。
     */
    fun candidates(text: String): List<SlashCommand> {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith(SlashCommand.PREFIX)) return emptyList()
        if (trimmed.contains(' ') || trimmed.contains('\n')) return emptyList()
        val typed = trimmed.removePrefix(SlashCommand.PREFIX).lowercase()
        return SlashCommand.entries.filter { it.token.startsWith(typed) }
    }

    /** 浮窗是否应该显示。 */
    fun shouldShowPopup(text: String): Boolean = candidates(text).isNotEmpty()

    /**
     * 提交时的语义解析。
     *
     * 未知指令（例如 `/tmp`）按普通消息原样发送：用户可能只是在写路径或代码片段，
     * 强行拦截会把正常输入变成报错。
     */
    fun intercept(text: String): SlashCommandEffect {
        val trimmed = text.trim()
        if (!trimmed.startsWith(SlashCommand.PREFIX)) return SlashCommandEffect.PassThrough(text)

        val token = trimmed.removePrefix(SlashCommand.PREFIX)
            .substringBefore(' ')
            .substringBefore('\n')
            .lowercase()
        val command = SlashCommand.byToken(token) ?: return SlashCommandEffect.PassThrough(text)

        val argument = trimmed.removePrefix(SlashCommand.PREFIX)
            .substringAfter(' ', missingDelimiterValue = "")
            .trim()

        return when (command) {
            SlashCommand.COMPACT -> SlashCommandEffect.CompactContext
            SlashCommand.CLEAR -> SlashCommandEffect.ClearDraft
            SlashCommand.HELP -> SlashCommandEffect.ShowHelp
            SlashCommand.PLAN -> if (argument.isEmpty()) {
                // 光有 /plan 没有任务：补全回输入框，让用户把任务写完再发。
                SlashCommandEffect.CompleteDraft(command.completion)
            } else {
                SlashCommandEffect.SendPlan(argument)
            }
        }
    }
}
