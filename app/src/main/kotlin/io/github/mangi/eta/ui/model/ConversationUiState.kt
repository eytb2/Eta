package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
    /** 已归档会话（主列表隐藏，侧栏底部"已归档"区展示）。 */
    val archivedConversations: List<ConversationSummaryUi> = emptyList(),
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val updatedAtMillis: Long = 0L,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
    val runStatus: String? = null,
    /** 已归档：主列表隐藏，仅在"已归档"区展示。 */
    val isArchived: Boolean = false,
    val characterName: String? = null,
    val characterAvatarPath: String? = null,
)

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}

/** 长按"会话信息"的统计展示。 */
@Immutable
data class ConversationStatsUi(
    val messageCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
