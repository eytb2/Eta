package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
    private val mainToolsProvider: () -> JSONArray = { JSONArray() },
    private val mainSessionId: String = operationId,
    private val messageProjector: ((JSONArray) -> JSONArray)? = null,
    private val enableCacheReuse: Boolean = false,
) {
    private val contextWindow = config.knownContextWindow
    private var inputTokens: Int? = null
    private var compacted = false
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun observeInputTokens(tokens: Int?) {
        inputTokens = tokens?.takeIf { it >= 0 }
    }

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
        )
    }

    fun compact(force: Boolean = false, final: Boolean = false) {
        val before = inputTokens
        if (SignedToolContext.hasPendingResponse(messages)) {
            if (force) {
                throw SignedToolContext.compactionFailure(messages)
            }
            return
        }
        val triggerLimit = minOf((contextWindow ?: Int.MAX_VALUE) * TRIGGER_RATIO, MAX_TRIGGER_TOKENS.toDouble())
        if (!force && (!config.autoCompactionEnabled || contextWindow == null || before == null ||
                before < triggerLimit)
        ) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED"))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_STARTED, before))
        try {
            val outcome = AgentContextCompactor(
                config = config,
                provider = provider,
                controller = runController,
                roleplay = roleplay,
                mainTools = mainToolsProvider(),
                mainSessionId = mainSessionId,
                messageProjector = messageProjector,
                enableCacheReuse = enableCacheReuse,
            ).compactWithOutcome(
                messages, systemCount, sensitiveIds(),
            )
            val candidate = outcome.messages
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            inputTokens = null
            onEvent(
                AgentEvent.ContextCompaction(
                    operationId = operation,
                    phase = AgentEvent.ContextCompaction.PHASE_COMPLETED,
                    tokensBefore = before,
                    usage = outcome.usage,
                    archiveId = outcome.archiveId,
                ),
            )
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            if (force && !final) {
                // 紧急截断兜底：如果是在强制压缩链路（如发生 CONTEXT_OVERFLOW），模型摘要请求若依然失败，
                // 绝不能死守原始上下文抛出异常导致会话永久死锁。退化为滑动窗口裁剪旧历史工具输出。
                val emergencyCandidate = tryEmergencyPrune(before)
                if (emergencyCandidate != null) {
                    val wasCompacted = compacted
                    compacted = true
                    try {
                        publishSnapshot(emergencyCandidate)
                    } catch (e: Exception) {
                        compacted = wasCompacted
                        throw failure
                    }
                    while (messages.length() > 0) messages.remove(messages.length() - 1)
                    for (index in 0 until emergencyCandidate.length()) messages.put(emergencyCandidate.getJSONObject(index))
                    inputTokens = null
                    onEvent(
                        AgentEvent.ContextCompaction(
                            operationId = operation,
                            phase = AgentEvent.ContextCompaction.PHASE_COMPLETED,
                            tokensBefore = before,
                            archiveId = "emergency-fold",
                        ),
                    )
                    return
                }
            }
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (!final) throw failure
            // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
            committedSnapshot = createSnapshot(messages)
        }
    }

    private fun tryEmergencyPrune(tokensBefore: Int?): JSONArray? {
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_eta_observation")
        }
        val end = (history.size downTo 1).firstOrNull { AgentContextCompactor.canSplit(history, it) } ?: return null
        val protectedUser = history.getOrNull(latestUser)?.takeIf { latestUser < end }
        val foldedSlice = history.take(end)
        val covered = foldedSlice.sumOf {
            it.optInt("_eta_compacted_users") + if (it.optString("role") == "user") 1 else 0
        }
        val archiveHeader = "[Eta 紧急滑动窗口压缩 · 归档 emergency-fold：由于历史会话超出模型上下文容量上限且模型摘要未能生成，系统已自动折叠早期历史步骤（包含此前约 $covered 轮交互及历史工具原始输出）以恢复会话。]\n"

        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        result.put(
            AgentConversationCodec.toJsonObject(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = archiveHeader,
                    contextSummary = true,
                    compactedUserTurns = covered,
                    summaryThroughUserTurn = covered + if (protectedUser != null) 1 else 0,
                    compactionArchiveId = "emergency-fold",
                ),
            ),
        )
        protectedUser?.let(result::put)
        history.drop(end).forEach { message ->
            result.put(message)
        }
        return if (result.toString().length < messages.toString().length) result else null
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }

    private companion object {
        const val TRIGGER_RATIO = 0.85
        const val MAX_TRIGGER_TOKENS = 120_000
    }
}
