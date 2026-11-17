package io.github.mangi.eta.ui.screens.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.termux.view.TerminalView
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.TerminalModifiers
import io.github.mangi.eta.ui.app.ConsoleStore
import io.github.mangi.eta.ui.app.UserTerminalStore
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 终端入口：PTY 可用时默认进入交互式控制台，块式终端作为“跑一条命令看结果”的简洁模式。 */
@Composable
internal fun TerminalEntryScreen(
    terminalStore: UserTerminalStore,
    consoleStore: ConsoleStore,
    onOpenEnvironment: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val consoleState by consoleStore.uiState.collectAsState()
    var consoleMode by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        terminalStore.refreshLinuxReady()
        consoleStore.refreshLinuxEnvironment()
        consoleStore.probePtySupport()
    }
    when {
        consoleState.ptySupported == null -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface),
        )
        consoleMode && consoleState.ptySupported == true -> ConsoleScreen(
            store = consoleStore,
            terminalStore = terminalStore,
            onExitConsole = { consoleMode = false },
            onOpenEnvironment = onOpenEnvironment,
            onBack = onBack,
        )
        else -> UserTerminalScreen(
            store = terminalStore,
            onOpenEnvironment = onOpenEnvironment,
            onOpenConsole = if (consoleState.ptySupported == true) {
                { consoleMode = true }
            } else {
                null
            },
            onBack = onBack,
        )
    }
}

/**
 * 控制台：继承 Eta 原生 Miuix 视觉风格与顶部工具栏/底部按键栏结构，
 * 采用 Termux 核心渲染引擎强化终端核心功能：
 * 完整支持移动端中文输入法交互、原生文本框选、拖拽选区手柄、剪贴板复制粘贴与双指缩放。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConsoleScreen(
    store: ConsoleStore,
    terminalStore: UserTerminalStore,
    onExitConsole: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val state by store.uiState.collectAsState()
    val terminalState by terminalStore.uiState.collectAsState()
    val selectingText by store.selectingText.collectAsState()
    val fullscreen by store.fullscreen.collectAsState()
    val twoRowsExtraKeys by store.twoRowsExtraKeys.collectAsState()
    val fontSizeSp by store.fontSizeSp.collectAsState()

    var showTasks by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    val modifierKeys = remember { ConsoleModifierKeys() }
    val keyboardVisible = WindowInsets.isImeVisible
    val context = LocalContext.current
    var currentTerminalView by remember { mutableStateOf<TerminalView?>(null) }

    val themeForeground = MiuixTheme.colorScheme.onSurface
    val themeBackground = MiuixTheme.colorScheme.surface
    val themePrimary = MiuixTheme.colorScheme.primary

    LaunchedEffect(themeForeground, themeBackground) {
        store.setThemeColors(
            themeForeground.toArgb().toLong() and 0xFFFFFFFFL,
            themeBackground.toArgb().toLong() and 0xFFFFFFFFL,
        )
    }

    // 首次进入或无活动会话时自动启动会话
    LaunchedEffect(state.activeSessionId, state.sessions.isEmpty()) {
        if (state.activeSessionId == null || state.sessions.isEmpty()) {
            store.open(state.environment)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        if (!fullscreen) {
            TerminalToolbar(
                environment = state.environment,
                linuxEnvironment = state.linuxEnvironment,
                onSwitchEnvironment = store::switchEnvironment,
                sessionCount = state.sessions.size,
                daemonRunning = terminalState.daemonTasks.any { it.running },
                onOpenSessions = { showSessions = true },
                onOpenTasks = {
                    terminalStore.refreshDaemonTasks()
                    showTasks = true
                },
                modeTarget = TerminalModeTarget.BLOCKS,
                onSwitchMode = onExitConsole,
                onBack = onBack,
            )
        } else {
            Spacer(modifier = Modifier.statusBarsPadding())
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (state.activeSessionId != null) {
                TermuxTerminalView(
                    store = store,
                    activeSessionId = state.activeSessionId,
                    modifierKeys = modifierKeys,
                    backgroundColor = themeBackground,
                    foregroundColor = themeForeground,
                    cursorColor = themePrimary,
                    modifier = Modifier.fillMaxSize(),
                    onTerminalViewCreated = { currentTerminalView = it },
                )
            }

            if (state.exited || state.failMessage != null) {
                TerminalPlaceholder(
                    icon = Icons.Rounded.Terminal,
                    message = state.failMessage ?: stringResource(R.string.terminal_session_closed),
                    actionText = stringResource(R.string.terminal_reconnect),
                    onAction = store::reconnect,
                    secondaryActionText = state.failMessage?.let { stringResource(R.string.terminal_open_environment) },
                    onSecondaryAction = onOpenEnvironment,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(themeBackground),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.dividerLine),
        )

        ConsoleKeyBar(
            modifierKeys = modifierKeys,
            keyboardVisible = keyboardVisible,
            twoRows = twoRowsExtraKeys,
            isFullscreen = fullscreen,
            isSelecting = selectingText,
            onKey = { key ->
                val modifiers = modifierKeys.consume(TerminalModifiers.NONE)
                store.sendKey(key, modifiers)
            },
            onText = { text ->
                val modifiers = modifierKeys.consume(TerminalModifiers.NONE)
                store.sendText(text, modifiers)
            },
            onSelect = {
                currentTerminalView?.toggleTextSelectionMode()
            },
            onCopy = {
                currentTerminalView?.let { view ->
                    val selected = view.storedSelectedText ?: view.selectedText
                    if (!selected.isNullOrEmpty()) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("terminal", selected))
                        Toast.makeText(context, R.string.terminal_copied, Toast.LENGTH_SHORT).show()
                        view.stopTextSelectionMode()
                    } else {
                        view.startTextSelectionMode(null)
                        Toast.makeText(context, R.string.terminal_select_hint, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSelectAll = {
                currentTerminalView?.let { view ->
                    if (!view.isSelectingText) {
                        view.startTextSelectionMode(null)
                    }
                }
            },
            onCopyAll = {
                currentTerminalView?.let { view ->
                    val transcript = view.currentSession?.emulator?.screen?.transcriptText
                    if (!transcript.isNullOrEmpty()) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("terminal", transcript))
                        Toast.makeText(context, R.string.terminal_all_copied, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onPaste = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!clip.isNullOrEmpty()) {
                    store.paste(clip)
                    Toast.makeText(context, R.string.terminal_pasted, Toast.LENGTH_SHORT).show()
                }
            },
            onToggleKeyboard = {
                currentTerminalView?.let { view ->
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    if (keyboardVisible) {
                        imm?.hideSoftInputFromWindow(view.windowToken, 0)
                    } else {
                        view.requestFocus()
                        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                        val act = context.findActivity()
                        if (act != null) {
                            WindowInsetsControllerCompat(act.window, view).show(WindowInsetsCompat.Type.ime())
                        }
                    }
                }
            },
            onToggleFullscreen = store::toggleFullscreen,
            onOpenManage = { showManage = true },
        )
    }

    if (showManage) {
        TerminalManageDialog(
            show = true,
            fullscreen = fullscreen,
            twoRowsExtraKeys = twoRowsExtraKeys,
            fontSizeSp = fontSizeSp,
            onDismiss = { showManage = false },
            onToggleFullscreen = store::toggleFullscreen,
            onToggleTwoRows = store::toggleTwoRowsExtraKeys,
            onIncreaseFontSize = store::increaseFontSize,
            onDecreaseFontSize = store::decreaseFontSize,
            onOpenSessions = { showSessions = true },
            onOpenTasks = {
                terminalStore.refreshDaemonTasks()
                showTasks = true
            },
            onReconnect = store::reconnect,
        )
    }

    if (showTasks) {
        DaemonTasksDialog(
            tasks = terminalState.daemonTasks,
            onDismiss = { showTasks = false },
            onStop = terminalStore::stopDaemonTask,
            onLoadLogs = terminalStore::daemonLogs,
        )
    }

    if (showSessions) {
        SessionListDialog(
            rows = state.sessions.map { session ->
                SessionDialogRow(
                    id = session.id,
                    environment = session.environment,
                    subtitle = "",
                    active = session.id == state.activeSessionId,
                    running = !session.exited,
                    alive = !session.exited,
                )
            },
            onDismiss = { showSessions = false },
            onSelect = store::switchSession,
            onRestart = store::restartSession,
            onClose = store::closeSession,
            onNew = store::newSession,
        )
    }
}
