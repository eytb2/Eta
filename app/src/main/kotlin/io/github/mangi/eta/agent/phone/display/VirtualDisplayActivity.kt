package io.github.mangi.eta.agent.phone.display

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 真屏侧的虚拟屏"接管小窗"：用 SurfaceView 的 Surface 作为虚拟屏输出，
 * 实时镜像虚拟屏画面；触摸事件按缩放比映射回虚拟屏坐标并注入，
 * 用户可在窗口里直接操作虚拟屏。可用 `am start --windowingMode 5` 以 freeform 打开，
 * 也支持普通全屏。窗口销毁或退到后台时把输出切回 ImageReader，Agent 截图随之恢复。
 */
internal class VirtualDisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = PhoneDisplayRegistry.controller(applicationContext)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initialAppearance)
                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = { isDark ->
                        val style = if (isDark) {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                        }
                        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    },
                ) {
                    TakeoverScreen(controller = controller, onClose = ::finish)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        PhoneDisplayRegistry.controller(applicationContext).setTakeoverSurface(null)
    }
}

@Composable
private fun TakeoverScreen(controller: VirtualDisplayController, onClose: () -> Unit) {
    val display by controller.display.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.virtual_display_takeover_title) +
                    if (display == null) "（" + stringResource(R.string.virtual_display_takeover_none) + "）" else "",
            )
            Text(
                text = stringResource(R.string.virtual_display_takeover_close),
                modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
            )
        }
        DisposableEffect(controller) {
            onDispose { controller.setTakeoverSurface(null) }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> TakeoverSurfaceView(context, controller) },
        )
    }
}

/** 输出 Surface 承载 + 触摸透传：DOWN/UP 折算为 tap 注入，MOVE 走一次性 swipe 注入。 */
private class TakeoverSurfaceView(
    context: Context,
    private val controller: VirtualDisplayController,
) : SurfaceView(context) {
    private var downX = 0
    private var downY = 0
    private var downAt = 0L

    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                controller.setTakeoverSurface(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                controller.setTakeoverSurface(null)
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val display = controller.display.value ?: return false
        // 等比映射：小窗内的 View 尺寸 → 虚拟屏逻辑坐标
        val scaleX = display.width.toFloat() / width.coerceAtLeast(1)
        val scaleY = display.height.toFloat() / height.coerceAtLeast(1)
        fun vx(x: Float) = (x * scaleX).roundToInt().coerceIn(0, display.width - 1)
        fun vy(y: Float) = (y * scaleY).roundToInt().coerceIn(0, display.height - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = vx(event.x)
                downY = vy(event.y)
                downAt = android.os.SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val upX = vx(event.x)
                val upY = vy(event.y)
                val duration = (android.os.SystemClock.uptimeMillis() - downAt).toInt()
                val moved = Math.abs(upX - downX) > TAP_SLOPE || Math.abs(upY - downY) > TAP_SLOPE
                if (moved && duration > TAP_MS) {
                    controller.injectSwipe(downX, downY, upX, upY, duration.coerceAtLeast(100))
                } else {
                    controller.injectTap(upX, upY)
                }
            }
        }
        return true
    }

    private companion object {
        const val TAP_SLOPE = 8
        const val TAP_MS = 120
    }
}

/** 接管窗口与 Agent 工具共享同一个控制器实例（App 进程内单例）。 */
internal object PhoneDisplayRegistry {
    @Volatile
    private var instance: VirtualDisplayController? = null

    fun controller(context: Context): VirtualDisplayController {
        // Agent 工具侧的控制器可能已持有虚拟屏（常驻通知的 owner 就是它）：
        // 优先复用，保证接管窗镜像/释放的就是 Agent 正在用的那块屏
        VirtualDisplayNotifier.owner?.let { return it }
        return instance ?: synchronized(this) {
            instance ?: VirtualDisplayController(
                context.applicationContext,
                AndroidAgentLogger,
                io.github.mangi.eta.agent.device.BoundedRootCommandExecutor(AndroidAgentLogger),
            ).also { instance = it }
        }
    }
}
