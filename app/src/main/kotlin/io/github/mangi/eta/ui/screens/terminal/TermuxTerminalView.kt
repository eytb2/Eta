package io.github.mangi.eta.ui.screens.terminal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.github.mangi.eta.ui.app.ConsoleStore
import kotlin.math.roundToInt

private const val TAG = "TermuxTerminalView"

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun spToPx(context: Context, sp: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp.toFloat(),
        context.resources.displayMetrics,
    ).roundToInt().coerceAtLeast(18)
}

/**
 * 基于 Termux 原生引擎的终端视图：
 * 具备硬件加速 Canvas 渲染、完整 IME 输入法交互（拼音/候选词/退格支持）、
 * 文本长按/双击手势框选、双指缩放及与 Miuix 主题色自适应。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TermuxTerminalView(
    store: ConsoleStore,
    activeSessionId: String?,
    modifierKeys: ConsoleModifierKeys,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black,
    foregroundColor: Color = Color.White,
    cursorColor: Color = Color.White,
    onTerminalViewCreated: ((TerminalView) -> Unit)? = null,
) {
    val context = LocalContext.current
    val currentFontSizeSp by store.fontSizeSp.collectAsState()

    val terminalView = remember {
        TerminalView(context, null).apply {
            setBackgroundColor(backgroundColor.toArgb())
            setTextSize(spToPx(context, currentFontSizeSp))
            setTypeface(Typeface.MONOSPACE)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            requestFocus()

            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    v.requestFocus()
                }
                false
            }

            val client = object : TerminalViewClient {
                override fun onScale(scale: Float): Float {
                    if (scale < 0.9f || scale > 1.1f) {
                        val increase = scale > 1.0f
                        if (increase) store.increaseFontSize() else store.decreaseFontSize()
                        return 1.0f
                    }
                    return scale
                }

                override fun onSingleTapUp(e: MotionEvent) {
                    this@apply.requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
                    val act = context.findActivity()
                    if (act != null) {
                        WindowInsetsControllerCompat(act.window, this@apply).show(WindowInsetsCompat.Type.ime())
                    }
                }

                override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                override fun shouldEnforceCharBasedInput(): Boolean = true
                override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                override fun isTerminalViewSelected(): Boolean = true

                override fun copyModeChanged(copyMode: Boolean) {
                    store.setSelectingText(copyMode)
                }

                override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean {
                    return false
                }

                override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

                override fun onLongPress(event: MotionEvent): Boolean {
                    return false
                }

                override fun readControlKey(): Boolean {
                    val down = (modifierKeys.ctrl != StickyModifier.OFF) || store.consumeCtrl()
                    if (modifierKeys.ctrl == StickyModifier.ONCE) {
                        modifierKeys.tapCtrl()
                    }
                    return down
                }

                override fun readAltKey(): Boolean {
                    val down = (modifierKeys.alt != StickyModifier.OFF) || store.consumeAlt()
                    if (modifierKeys.alt == StickyModifier.ONCE) {
                        modifierKeys.tapAlt()
                    }
                    return down
                }

                override fun readShiftKey(): Boolean = false
                override fun readFnKey(): Boolean = false

                override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
                    return false
                }

                override fun onEmulatorSet() {
                    setTerminalCursorBlinkerRate(500)
                    setTerminalCursorBlinkerState(true, true)
                    onScreenUpdated()
                }

                override fun logError(tag: String, message: String) { Log.e(tag, message) }
                override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
                override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
                override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
                override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
                override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stack trace", e) }
            }

            setTerminalViewClient(client)
            onTerminalViewCreated?.invoke(this)
        }
    }

    LaunchedEffect(terminalView) {
        onTerminalViewCreated?.invoke(terminalView)
    }

    // 绑定当前活动 Tab 的文本与光标变化监听
    DisposableEffect(activeSessionId, terminalView) {
        if (activeSessionId == null) return@DisposableEffect onDispose {}
        val listener = object : ConsoleStore.SessionListener {
            override fun onTextChanged(session: TerminalSession) {
                terminalView.post {
                    terminalView.onScreenUpdated()
                }
            }

            override fun onTitleChanged(session: TerminalSession) {}

            override fun onTerminalCursorStateChange(state: Boolean) {
                terminalView.post {
                    terminalView.setTerminalCursorBlinkerState(state, true)
                }
            }

            override fun onUserInput() {
                terminalView.post {
                    if (terminalView.topRow != 0) {
                        terminalView.topRow = 0
                        terminalView.invalidate()
                    }
                }
            }
        }
        store.setSessionListener(activeSessionId, listener)
        onDispose {
            store.setSessionListener(activeSessionId, null)
        }
    }

    // 辅助函数：应用色彩到 Session 和 TerminalView
    fun applyThemeColors(view: TerminalView, session: TerminalSession?) {
        view.setBackgroundColor(backgroundColor.toArgb())
        val emulator = session?.emulator ?: return
        val fg = foregroundColor.toArgb()
        val bg = backgroundColor.toArgb()
        val cur = cursorColor.toArgb()
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = fg
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = bg
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = cur
    }

    // 会话切换时立即重置并绑定
    val session = activeSessionId?.let { store.getSession(it) }
    LaunchedEffect(activeSessionId, session, terminalView) {
        if (session != null) {
            terminalView.attachSession(session)
            terminalView.setTerminalCursorBlinkerRate(500)
            terminalView.setTerminalCursorBlinkerState(true, true)
            applyThemeColors(terminalView, session)
            terminalView.onScreenUpdated()
            terminalView.requestFocus()
        }
    }

    // 主题色彩变化响应
    LaunchedEffect(backgroundColor, foregroundColor, cursorColor, session, terminalView) {
        applyThemeColors(terminalView, session)
        terminalView.invalidate()
        terminalView.onScreenUpdated()
    }

    LaunchedEffect(currentFontSizeSp) {
        terminalView.setTextSize(spToPx(context, currentFontSizeSp))
        terminalView.onScreenUpdated()
    }

    // 软键盘显隐时实时重算尺寸向底层发送 SIGWINCH，确保光标与最新行不被遮挡
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        terminalView.post {
            terminalView.updateSize()
            terminalView.onScreenUpdated()
        }
    }

    // 会话切换或进入界面自动聚焦唤起输入法
    LaunchedEffect(activeSessionId, terminalView) {
        kotlinx.coroutines.delay(150)
        terminalView.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        val act = context.findActivity()
        if (act != null) {
            WindowInsetsControllerCompat(act.window, terminalView).show(WindowInsetsCompat.Type.ime())
        }
    }

    AndroidView(
        factory = { terminalView },
        modifier = modifier,
        update = { view ->
            val curSession = activeSessionId?.let { store.getSession(it) }
            if (curSession != null && view.mTermSession !== curSession) {
                view.attachSession(curSession)
                view.setTerminalCursorBlinkerRate(500)
                view.setTerminalCursorBlinkerState(true, true)
                applyThemeColors(view, curSession)
            }
            view.onScreenUpdated()
        },
    )
}
