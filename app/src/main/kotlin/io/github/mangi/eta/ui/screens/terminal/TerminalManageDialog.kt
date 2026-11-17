package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 终端设置与快捷管理面板：
 * 提供全屏终端切换、双行快捷键切换、字体字号微调以及会话/后台任务/重连快捷入口。
 */
@Composable
internal fun TerminalManageDialog(
    show: Boolean,
    fullscreen: Boolean,
    twoRowsExtraKeys: Boolean,
    fontSizeSp: Int,
    onDismiss: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleTwoRows: () -> Unit,
    onIncreaseFontSize: () -> Unit,
    onDecreaseFontSize: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenTasks: () -> Unit,
    onReconnect: () -> Unit,
) {
    if (!show) return
    val colors = MiuixTheme.colorScheme

    WindowDialog(
        show = true,
        title = stringResource(R.string.terminal_manage_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 全屏终端沉浸模式开关
            SettingCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.terminal_fullscreen),
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.terminal_fullscreen_summary),
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Switch(
                        checked = fullscreen,
                        onCheckedChange = { onToggleFullscreen() },
                    )
                }
            }

            // 双行快捷按键栏开关
            SettingCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.terminal_two_rows_keys),
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.terminal_two_rows_keys_summary),
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Switch(
                        checked = twoRowsExtraKeys,
                        onCheckedChange = { onToggleTwoRows() },
                    )
                }
            }

            // 终端字体大小调节
            SettingCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.terminal_font_size),
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$fontSizeSp sp · ${stringResource(R.string.terminal_font_size_summary)}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StepButton(text = "-", onClick = onDecreaseFontSize)
                        Text(
                            text = "$fontSizeSp",
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                        StepButton(text = "+", onClick = onIncreaseFontSize)
                    }
                }
            }

            // 快捷入口：会话列表、后台任务、重启会话
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.terminal_sessions),
                    onClick = {
                        onDismiss()
                        onOpenSessions()
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.terminal_daemon_tasks),
                    onClick = {
                        onDismiss()
                        onOpenTasks()
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.terminal_restart_session),
                    onClick = {
                        onDismiss()
                        onReconnect()
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StepButton(
    text: String,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(shape)
            .background(colors.surfaceContainerHighest, shape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleBackground(colors.surfaceContainer, 12.dp),
    ) {
        content()
    }
}
