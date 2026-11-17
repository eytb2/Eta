package io.github.mangi.eta.ui.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Immutable
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.ProotCommandBuilder
import io.github.mangi.eta.agent.terminal.SharedFolderMount
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalKey
import io.github.mangi.eta.agent.terminal.TerminalKeyEncoder
import io.github.mangi.eta.agent.terminal.TerminalModifiers
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
internal data class ConsoleSessionUi(
    val id: String,
    val environment: TerminalEnvironment,
    val exited: Boolean = false,
)

@Immutable
internal data class ConsoleUiState(
    val sessions: List<ConsoleSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    val environment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val linuxEnvironment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val connected: Boolean = false,
    val exited: Boolean = false,
    val ptySupported: Boolean? = true,
    val failMessage: String? = null,
)

/**
 * 控制台状态所有者：管理基于 Termux 核心引擎的高性能 PTY 终端会话。
 * 支持多会话切换、Android 与各类 Linux 环境自适应引导、前台保活与状态自愈。
 */
internal class ConsoleStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    interface SessionListener {
        fun onTextChanged(session: TerminalSession)
        fun onTitleChanged(session: TerminalSession)
        fun onTerminalCursorStateChange(state: Boolean)
        fun onUserInput()
    }

    private val appContext = context.applicationContext
    private val sessionLeases = ConcurrentHashMap<String, TerminalSessionLease>()
    private val terminalSessions = ConcurrentHashMap<String, TerminalSession>()
    private val sessionListeners = ConcurrentHashMap<String, SessionListener>()
    private val processSupervisor = ShellProcessSupervisor()
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { SharedFolderMounts.current() }

    private var nextSessionNumber = 0
    private val initialLinuxEnvironment =
        LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment

    private val _uiState = MutableStateFlow(
        ConsoleUiState(
            environment = initialLinuxEnvironment,
            linuxEnvironment = initialLinuxEnvironment,
            ptySupported = true,
        ),
    )
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    private val prefs = appContext.getSharedPreferences("eta_terminal_prefs", Context.MODE_PRIVATE)

    private val _fontSizeSp = MutableStateFlow(prefs.getInt("font_size_sp", 13))
    val fontSizeSp: StateFlow<Int> = _fontSizeSp.asStateFlow()

    private val _ctrlActive = MutableStateFlow(false)
    val ctrlActive: StateFlow<Boolean> = _ctrlActive.asStateFlow()

    private val _altActive = MutableStateFlow(false)
    val altActive: StateFlow<Boolean> = _altActive.asStateFlow()

    private val _selectingText = MutableStateFlow(false)
    val selectingText: StateFlow<Boolean> = _selectingText.asStateFlow()

    private val _fullscreen = MutableStateFlow(prefs.getBoolean("fullscreen_terminal", false))
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    private val _twoRowsExtraKeys = MutableStateFlow(prefs.getBoolean("two_rows_keys", true))
    val twoRowsExtraKeys: StateFlow<Boolean> = _twoRowsExtraKeys.asStateFlow()

    fun getSession(sessionId: String): TerminalSession? = terminalSessions[sessionId]

    fun setSessionListener(sessionId: String, listener: SessionListener?) {
        if (listener != null) {
            sessionListeners[sessionId] = listener
        } else {
            sessionListeners.remove(sessionId)
        }
    }

    fun increaseFontSize() {
        _fontSizeSp.update { cur ->
            (cur + 1).coerceAtMost(28).also {
                prefs.edit().putInt("font_size_sp", it).apply()
            }
        }
    }

    fun decreaseFontSize() {
        _fontSizeSp.update { cur ->
            (cur - 1).coerceAtLeast(8).also {
                prefs.edit().putInt("font_size_sp", it).apply()
            }
        }
    }

    fun toggleCtrl() {
        _ctrlActive.update { !it }
    }

    fun toggleAlt() {
        _altActive.update { !it }
    }

    fun consumeCtrl(): Boolean {
        val cur = _ctrlActive.value
        if (cur) _ctrlActive.value = false
        return cur
    }

    fun consumeAlt(): Boolean {
        val cur = _altActive.value
        if (cur) _altActive.value = false
        return cur
    }

    fun setSelectingText(selecting: Boolean) {
        _selectingText.value = selecting
    }

    fun toggleFullscreen() {
        _fullscreen.update { cur ->
            (!cur).also { prefs.edit().putBoolean("fullscreen_terminal", it).apply() }
        }
    }

    fun toggleTwoRowsExtraKeys() {
        _twoRowsExtraKeys.update { cur ->
            (!cur).also { prefs.edit().putBoolean("two_rows_keys", it).apply() }
        }
    }

    fun refreshLinuxEnvironment() {
        val configured = LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment
        _uiState.update { state ->
            val updateCurrent = state.environment.isLinux && state.sessions.isEmpty()
            state.copy(
                environment = if (updateCurrent) configured else state.environment,
                linuxEnvironment = configured,
            )
        }
    }

    fun probePtySupport() {
        _uiState.update { it.copy(ptySupported = true) }
    }

    fun switchEnvironment(environment: TerminalEnvironment) {
        if (environment == _uiState.value.environment) return
        val existing = _uiState.value.sessions.firstOrNull { it.environment == environment && !it.exited }
        if (existing != null) {
            switchSession(existing.id)
        } else {
            open(environment)
        }
    }

    fun switchSession(sessionId: String) {
        val session = terminalSessions[sessionId] ?: return
        val sessionUi = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        _uiState.update {
            it.copy(
                activeSessionId = sessionId,
                environment = sessionUi.environment,
                connected = session.isRunning,
                exited = !session.isRunning,
                failMessage = null,
            )
        }
    }

    fun newSession() {
        val env = _uiState.value.environment
        open(env)
    }

    fun closeSession(sessionId: String) {
        val session = terminalSessions.remove(sessionId)
        session?.finishIfRunning()
        sessionLeases.remove(sessionId)?.release()
        sessionListeners.remove(sessionId)
        _uiState.update { state ->
            val remaining = state.sessions.filterNot { it.id == sessionId }
            val nextActive = if (state.activeSessionId == sessionId) remaining.lastOrNull()?.id else state.activeSessionId
            state.copy(
                sessions = remaining,
                activeSessionId = nextActive,
                connected = nextActive?.let { terminalSessions[it]?.isRunning } == true,
                exited = nextActive?.let { terminalSessions[it]?.isRunning == false } ?: false,
            )
        }
    }

    fun restartSession(sessionId: String) {
        val current = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        closeSession(sessionId)
        open(current.environment)
    }

    fun reconnect() {
        val active = _uiState.value.activeSessionId
        if (active != null) {
            restartSession(active)
        } else {
            open(_uiState.value.environment)
        }
    }

    fun open(environment: TerminalEnvironment, cols: Int = 80, rows: Int = 24) {
        val activeId = _uiState.value.activeSessionId
        if (activeId != null) {
            val current = _uiState.value.sessions.firstOrNull { it.id == activeId }
            val activeSession = terminalSessions[activeId]
            if (current?.environment == environment && activeSession != null && activeSession.isRunning) {
                return
            }
        }
        val existing = _uiState.value.sessions.firstOrNull { it.environment == environment && !it.exited }
        if (existing != null && terminalSessions[existing.id]?.isRunning == true) {
            switchSession(existing.id)
            return
        }

        if (environment.isLinux && !isReady(environment)) {
            _uiState.update {
                it.copy(
                    connected = false,
                    failMessage = appContext.getString(R.string.terminal_linux_not_ready),
                )
            }
            return
        }

        val sessionId = "c${++nextSessionNumber}"
        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                sessionListeners[sessionId]?.onTextChanged(changedSession)
            }

            override fun onTitleChanged(changedSession: TerminalSession) {
                sessionListeners[sessionId]?.onTitleChanged(changedSession)
            }

            override fun onSessionFinished(finishedSession: TerminalSession) {
                sessionLeases.remove(sessionId)?.release()
                _uiState.update { state ->
                    val updated = state.sessions.map {
                        if (it.id == sessionId) it.copy(exited = true) else it
                    }
                    if (state.activeSessionId == sessionId) {
                        state.copy(sessions = updated, connected = false, exited = true)
                    } else {
                        state.copy(sessions = updated)
                    }
                }
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString()
                if (!clip.isNullOrEmpty()) {
                    session?.write(clip)
                }
            }

            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {
                sessionListeners[sessionId]?.onTerminalCursorStateChange(state)
            }
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
            override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

            override fun logError(tag: String, message: String) { AndroidAgentLogger.error("$tag: $message") }
            override fun logWarn(tag: String, message: String) { AndroidAgentLogger.warn("$tag: $message") }
            override fun logInfo(tag: String, message: String) { AndroidAgentLogger.info("$tag: $message") }
            override fun logDebug(tag: String, message: String) { AndroidAgentLogger.debug { "$tag: $message" } }
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { AndroidAgentLogger.error("$tag: $message", e) }
            override fun logStackTrace(tag: String, e: Exception) { AndroidAgentLogger.error("$tag: stacktrace", e) }
        }

        val lease = TerminalSessionLease.acquire(appContext) {
            closeSession(sessionId)
        }

        val session = try {
            createTerminalSession(sessionId, environment, client)
        } catch (e: Exception) {
            lease?.release()
            _uiState.update { it.copy(connected = false, failMessage = e.message ?: "创建终端会话失败") }
            return
        }

        if (session == null) {
            lease?.release()
            _uiState.update { it.copy(connected = false, failMessage = "无法启动终端，请检查 Linux 环境是否已安装并就绪") }
            return
        }

        if (lease != null) {
            sessionLeases[sessionId] = lease
            lease.attach(sessionId)
        }
        terminalSessions[sessionId] = session

        _uiState.update { state ->
            state.copy(
                sessions = state.sessions + ConsoleSessionUi(sessionId, environment),
                activeSessionId = sessionId,
                environment = environment,
                connected = true,
                exited = false,
                failMessage = null,
            )
        }
    }

    private fun createTerminalSession(
        sessionId: String,
        environment: TerminalEnvironment,
        client: TerminalSessionClient,
    ): TerminalSession? {
        val rootfsPath = rootfsPath(environment)
        val identity = if (environment.isLinux) {
            rootfsPath?.let { TerminalRuntime.defaultIdentity(environment, it) } ?: "root"
        } else if (TerminalRuntime.rootAvailable) "root" else "user"

        val commonEnv = arrayOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
        )

        val shellPath: String
        val cwd: String
        val args: Array<String>
        val envVars: Array<String>

        if (environment == TerminalEnvironment.ANDROID) {
            if (identity == "root") {
                val workspace = TerminalRuntime.workspace("root")
                val launcher = "export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH\"; " +
                    "mkdir -p '$workspace' 2>/dev/null; cd '$workspace' 2>/dev/null || cd /; exec sh"
                shellPath = "su"
                cwd = "/"
                args = arrayOf("su", "-c", launcher)
                envVars = commonEnv + arrayOf("HOME=$workspace")
            } else {
                val workspace = TerminalRuntime.userWorkspacePath
                shellPath = "/system/bin/sh"
                cwd = workspace
                args = arrayOf("/system/bin/sh", "-l")
                envVars = commonEnv + arrayOf("HOME=$workspace", "PATH=/system/bin:/system/xbin")
            }
        } else {
            if (rootfsPath == null || !LinuxEnvironmentPaths.rootfsReady(rootfsPath)) {
                return null
            }
            val sharedMounts = linuxSharedMountsProvider()
            val backend = LinuxEnvironmentPaths.backendOf(rootfsPath)

            if (backend == LinuxExecutionBackend.PROOT) {
                val payload = ProotCommandBuilder.payload(rootfsPath, null, sharedMounts, "xterm-256color")
                shellPath = "/system/bin/sh"
                cwd = "/"
                args = arrayOf("/system/bin/sh", "-c", payload)
                envVars = commonEnv + arrayOf("HOME=/root")
            } else {
                val payload = processSupervisor.buildLinuxPayload(rootfsPath, null, sharedMounts, "xterm-256color")
                shellPath = "su"
                cwd = "/"
                args = arrayOf("su", "-c", payload)
                envVars = commonEnv + arrayOf("HOME=/root")
            }
        }

        return TerminalSession(
            shellPath,
            cwd,
            args,
            envVars,
            10000,
            client,
        )
    }

    fun sendTerminalInput(sessionId: String, data: String) {
        if (data.isEmpty()) return
        val session = terminalSessions[sessionId] ?: return
        if (!session.isRunning && (data == "\r" || data == "\n")) {
            restartSession(sessionId)
            return
        }
        var payload = data
        if (_ctrlActive.value && payload.length == 1) {
            val c = payload[0]
            payload = TerminalKeyEncoder.controlCode(c)?.toString() ?: payload
            _ctrlActive.value = false
        }
        if (_altActive.value && payload.isNotEmpty()) {
            payload = "\u001b$payload"
            _altActive.value = false
        }
        sessionListeners[sessionId]?.onUserInput()
        session.write(payload)
    }

    fun sendSpecialKey(sessionId: String, key: TerminalKey) {
        val session = terminalSessions[sessionId] ?: return
        val ctrl = consumeCtrl()
        val alt = consumeAlt()
        val modifiers = TerminalModifiers(ctrl = ctrl, alt = alt)
        val seq = TerminalKeyEncoder.key(key, modifiers)
        if (key != TerminalKey.PAGE_UP && key != TerminalKey.PAGE_DOWN) {
            sessionListeners[sessionId]?.onUserInput()
        }
        session.write(seq)
    }

    fun paste(text: String) {
        val sessionId = _uiState.value.activeSessionId ?: return
        val session = terminalSessions[sessionId] ?: return
        sessionListeners[sessionId]?.onUserInput()
        session.write(text)
    }

    fun sendText(text: String, modifiers: TerminalModifiers = TerminalModifiers.NONE) {
        val sessionId = _uiState.value.activeSessionId ?: return
        val session = terminalSessions[sessionId] ?: return
        val encoded = TerminalKeyEncoder.text(text, modifiers)
        sessionListeners[sessionId]?.onUserInput()
        session.write(encoded)
    }

    fun sendKey(key: TerminalKey, modifiers: TerminalModifiers = TerminalModifiers.NONE) {
        val sessionId = _uiState.value.activeSessionId ?: return
        val session = terminalSessions[sessionId] ?: return
        val seq = TerminalKeyEncoder.key(key, modifiers)
        if (key != TerminalKey.PAGE_UP && key != TerminalKey.PAGE_DOWN) {
            sessionListeners[sessionId]?.onUserInput()
        }
        session.write(seq)
    }

    fun setThemeColors(foreground: Long, background: Long) {
        val fg = foreground.toInt()
        val bg = background.toInt()
        terminalSessions.values.forEach { session ->
            val emulator = session.emulator ?: return@forEach
            emulator.mColors.mCurrentColors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = fg
            emulator.mColors.mCurrentColors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = bg
            emulator.mColors.mCurrentColors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = fg
        }
    }

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        environment.linuxDistribution?.let { distribution ->
            LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
        }

    private fun isReady(environment: TerminalEnvironment): Boolean =
        environment.linuxDistribution?.let { distribution ->
            LinuxEnvironmentPaths.rootfsReady(
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath,
            )
        } ?: false
}
