package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AskQuestionItem
import io.github.mangi.eta.agent.tool.AskQuestionTool
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * `ask_question` 的人机决策卡片。
 *
 * 模型抛出结构化问题后本轮结束，用户在这里选择，选择结果作为一条普通用户消息回到会话，
 * Agent 因此无缝继续——卡片本身不与 Runtime 直接通信。
 *
 * 只在 Agent 空闲时可交互：运行中提交会进入排队而不是回答本次提问，语义不符。
 * 已提交的卡片折叠为只读回执，避免重复提交把同一份答案再发一遍。
 */
@Composable
internal fun AskQuestionCard(
    items: List<AskQuestionItem>,
    canSubmit: Boolean,
    onSubmitAnswer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val haptic = LocalHapticFeedback.current

    // 选中状态按位编码进单个字符串：Set 不是 Bundle 可保存类型，而位串既紧凑又能在
    // 列表滚动销毁条目、配置变更后原样恢复，不会出现“刚选完滚回来就丢了”。
    var encoded by rememberSaveable { mutableStateOf("") }
    val selections = remember(encoded, items.size) { decodeSelections(encoded, items.size) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    // 至少答一题即可提交：多问题时用户可能只想回答其中几个，
    // 未作答的问题由 formatAnswer 显式标注为“（未选择）”，不让模型误当成默认同意。
    val anyAnswered = selections.any { it.isNotEmpty() }

    fun select(questionIndex: Int, optionIndex: Int) {
        val item = items.getOrNull(questionIndex) ?: return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val current = selections.getOrNull(questionIndex).orEmpty()
        val next = if (item.isMultiSelect) {
            if (optionIndex in current) current - optionIndex else current + optionIndex
        } else {
            setOf(optionIndex)
        }
        encoded = encodeSelections(selections.mapIndexed { index, value ->
            if (index == questionIndex) next else value
        })
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(color = colors.surface, cornerRadius = ChatCardCorner)
            .squircleBorder(
                width = 0.5.dp,
                color = colors.outline.copy(alpha = ChatCardOutlineAlpha),
                cornerRadius = ChatCardCorner,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colors.primary.copy(alpha = if (isDark) 0.20f else 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.primary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (submitted) R.string.ask_question_submitted else R.string.ask_question_awaiting,
                ),
                style = MiuixTheme.textStyles.body2,
                color = if (submitted) colors.onSurfaceVariantSummary else colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                visible = submitted,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = StatusSuccess,
                )
            }
        }

        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEachIndexed { questionIndex, item ->
                AskQuestionSection(
                    item = item,
                    index = questionIndex,
                    total = items.size,
                    selected = selections.getOrNull(questionIndex).orEmpty(),
                    enabled = canSubmit && !submitted,
                    onSelect = { optionIndex -> select(questionIndex, optionIndex) },
                )
            }
        }

        if (!submitted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                    CouiButton(
                        text = stringResource(R.string.ask_question_submit),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            submitted = true
                            onSubmitAnswer(AskQuestionTool.formatAnswer(items, selections))
                        },
                        enabled = canSubmit && anyAnswered,
                        role = CouiButtonRole.Primary,
                        minHeight = 34.dp,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    )
                } else {
                    TextButton(
                        text = stringResource(R.string.ask_question_submit),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            submitted = true
                            onSubmitAnswer(AskQuestionTool.formatAnswer(items, selections))
                        },
                        enabled = canSubmit && anyAnswered,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        minHeight = 34.dp,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        textStyle = MiuixTheme.textStyles.body2,
                    )
                }
            }
        }
    }
}

@Composable
private fun AskQuestionSection(
    item: AskQuestionItem,
    index: Int,
    total: Int,
    selected: Set<Int>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.question,
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (total > 1) {
                Text(
                    text = stringResource(R.string.ask_question_question_index, index + 1, total),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            text = stringResource(
                if (item.isMultiSelect) R.string.ask_question_multi_select else R.string.ask_question_single_select,
            ),
            style = MiuixTheme.textStyles.footnote2,
            color = colors.onSurfaceVariantSummary.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(
            modifier = Modifier.padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item.options.forEachIndexed { optionIndex, option ->
                val isSelected = optionIndex in selected
                AskQuestionOptionRow(
                    text = option,
                    selected = isSelected,
                    multiSelect = item.isMultiSelect,
                    enabled = enabled,
                    onClick = { onSelect(optionIndex) },
                )
            }
        }
    }
}

/** 选项整行可点，勾选控件只作为状态指示；触控目标不小于 40dp。 */
@Composable
private fun AskQuestionOptionRow(
    text: String,
    selected: Boolean,
    multiSelect: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(
                if (selected) colors.primary.copy(alpha = 0.10f) else colors.surfaceContainer,
            )
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) {
                    colors.primary.copy(alpha = 0.45f)
                } else {
                    colors.outline.copy(alpha = 0.5f)
                },
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 整行已经带 enabled 控制的 clickable，这里不再重复判断，
        // 避免 if 分支里两个 lambda 推不出共同类型。
        if (multiSelect) {
            Checkbox(
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = onClick,
                enabled = enabled,
            )
        } else {
            RadioButton(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = if (enabled) colors.onSurface else colors.disabledOnSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 把每题的选中项编码为 `题目位串;题目位串`，例如两题分别选中第 0、2 项与第 1 项
 * 得到 `101;010`。位串长度随选项数变化，解码时以实际选项数为准，越位忽略。
 */
private fun encodeSelections(selections: List<Set<Int>>): String =
    selections.joinToString(SELECTION_SEPARATOR) { question ->
        if (question.isEmpty()) {
            ""
        } else {
            val width = question.max() + 1
            buildString(width) {
                for (bit in 0 until width) append(if (bit in question) '1' else '0')
            }
        }
    }

private fun decodeSelections(encoded: String, questionCount: Int): List<Set<Int>> {
    val parts = encoded.split(SELECTION_SEPARATOR)
    return List(questionCount) { index ->
        parts.getOrNull(index).orEmpty()
            .mapIndexedNotNull { bit, char -> if (char == '1') bit else null }
            .toSet()
    }
}

private const val SELECTION_SEPARATOR = ";"
