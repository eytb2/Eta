package io.github.mangi.eta.agent.phone.display

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 虚拟屏运行指示悬浮球：虚拟屏存续期间以系统悬浮窗（TYPE_APPLICATION_OVERLAY）
 * 常驻屏幕右缘。AI 风格渐变球体 + 最近启动 App 图标；多任务时右上角标显示数量，
 * 单击弹出任务列表（可点"收拢"或点列表外收拢），选中即迁移为 MIUI 原生小窗；
 * 迁移过程球体脉冲动画反馈；任务驻留小窗期间球变绿描边"窗"角标，小窗被关闭且
 * 进程未死时任务自动回虚拟屏。按住拖动可换位，位置跨会话记忆。依赖
 * SYSTEM_ALERT_WINDOW（Manifest 已声明）；未授权等异常时静默放弃，常驻通知
 * （[VirtualDisplayNotifier]）仍是兜底入口。
 */
internal object VirtualDisplayBubble {
    private val main = Handler(Looper.getMainLooper())
    private var view: FrameLayout? = null
    private var iconView: ImageView? = null
    private var badgeView: TextView? = null
    private var popup: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var pulse: ObjectAnimator? = null

    /** 最近一次列表收拢时刻：点球时列表的 OUTSIDE-dismiss 会先于球的 onClick 触发，
     *  用它区分"用户刚收拢"和"想展开"，避免点球变成关旧开新、永远收不了 */
    @Volatile
    private var lastPopupDismiss = 0L

    /** 显示或刷新悬浮球：已存在时只更新图标/角标（不重建，避免触摸流被打断） */
    fun show(context: Context, controller: VirtualDisplayController, packageName: String?) {
        main.post { showInternal(context.applicationContext, controller, packageName) }
    }

    /** 撤掉悬浮球与任务列表；重复调用没有副作用。位置记忆保留（SharedPreferences） */
    fun hide() {
        main.post {
            hideInternal()
            hidePopup()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("virtual_display_bubble", Context.MODE_PRIVATE)

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun showInternal(appContext: Context, controller: VirtualDisplayController, packageName: String?) {
        // 已释放且无迁移驻留：不显示（防异步 show() 在 release 后复活空球）
        if (controller.display.value == null && controller.migratedPackage == null) {
            hideInternal()
            return
        }
        val wm = appContext.getSystemService(WindowManager::class.java) ?: return
        val density = appContext.resources.displayMetrics.density
        val size = (48 * density).toInt()
        val migrated = controller.migratedPackage
        val count = synchronized(controller.runningPackages) { controller.runningPackages.size }

        // 已存在：只刷新球面内容与角标，触摸监听不动（修复刷新瞬间点击丢失）
        val existing = view
        if (existing != null && iconView != null && ballParams != null) {
            refreshBall(appContext, controller, packageName, migrated, count)
            return
        }
        hideInternal()

        val frame = FrameLayout(appContext)
        val ball = ImageView(appContext).apply {
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // AI 风格渐变球底：左上亮蓝紫 → 右下深紫；运行态白描边，驻留小窗态绿描边
            background = ballBackground(density, migrated != null)
            elevation = 3f * density
            applyIcon(appContext, packageName)
        }
        frame.addView(
            ball,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        iconView = ball

        // 右上角标：驻留小窗显示"窗"（绿），多任务显示数量（红），单任务不显示
        val badge = TextView(appContext).apply {
            textSize = 9f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF5252.toInt())
            }
        }
        val badgeSize = (16 * density).toInt()
        frame.addView(
            badge,
            FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.END).apply {
                marginEnd = -(badgeSize / 4)
            },
        )
        badgeView = badge
        updateBadge(badge, migrated, count)

        val newParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = prefs(appContext)
            val sx = saved.getInt("x", Int.MIN_VALUE)
            val sy = saved.getInt("y", Int.MIN_VALUE)
            x = if (sx != Int.MIN_VALUE) sx else (appContext.resources.displayMetrics.widthPixels - size - 1)
            y = if (sy != Int.MIN_VALUE) sy else (appContext.resources.displayMetrics.heightPixels * 0.35f).toInt()
        }
        // 单击迁移/弹列表，按住拖动；位移阈值 12dp，拖动结束记忆位置
        var downX = 0f
        var downY = 0f
        var moved = false
        val touchSlop = (12 * density).toInt()
        frame.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (moved || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        val metrics = appContext.resources.displayMetrics
                        newParams.x = (newParams.x + dx.toInt()).coerceIn(0, metrics.widthPixels - v.width)
                        newParams.y = (newParams.y + dy.toInt()).coerceIn(0, metrics.heightPixels - v.height)
                        downX = ev.rawX
                        downY = ev.rawY
                        runCatching { wm.updateViewLayout(v, newParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs(appContext).edit().putInt("x", newParams.x).putInt("y", newParams.y).apply()
                    } else {
                        handleClick(appContext, controller)
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(frame, newParams) }
            .onSuccess {
                view = frame
                iconView = ball
                badgeView = badge
                ballParams = newParams
            }
            .onFailure { /* 无悬浮窗权限等：放弃悬浮球，常驻通知仍是入口 */ }
    }

    /** AI 渐变球底：左上亮蓝紫 → 右下深紫；运行态白描边，驻留小窗态绿描边 */
    private fun ballBackground(density: Float, migrated: Boolean) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            0xFFA8C6FF.toInt(), 0xFF7B6CFF.toInt(), 0xFF4A2FB8.toInt(),
        )).apply {
            shape = GradientDrawable.OVAL
            setStroke((1.5f * density).toInt(), if (migrated) 0xFF7DE89B.toInt() else 0xCCFFFFFF.toInt())
        }

    private fun applyIcon(appContext: Context, packageName: String?) {
        iconView?.let { icon ->
            if (!packageName.isNullOrBlank()) {
                runCatching { icon.setImageDrawable(appContext.packageManager.getApplicationIcon(packageName)) }
                    .onFailure { icon.setImageResource(android.R.drawable.ic_media_play) }
            } else {
                icon.setImageResource(android.R.drawable.ic_media_play)
            }
        }
    }

    private fun updateBadge(badge: TextView?, migrated: String?, count: Int) {
        badge ?: return
        val density = badge.resources.displayMetrics.density
        when {
            migrated != null -> {
                badge.text = "窗"
                badge.visibility = View.VISIBLE
                (badge.background.mutate() as? GradientDrawable)?.setColor(0xFF34B368.toInt())
            }
            count > 1 -> {
                badge.text = count.toString()
                badge.visibility = View.VISIBLE
                (badge.background.mutate() as? GradientDrawable)?.setColor(0xFFFF5252.toInt())
            }
            else -> badge.visibility = View.GONE
        }
    }

    /** 刷新已有球面：图标、角标、描边色（迁移态切换） */
    private fun refreshBall(
        appContext: Context,
        controller: VirtualDisplayController,
        packageName: String?,
        migrated: String?,
        count: Int,
    ) {
        applyIcon(appContext, packageName)
        val density = appContext.resources.displayMetrics.density
        iconView?.background = ballBackground(density, migrated != null)
        updateBadge(badgeView, migrated, count)
    }

    // ---- 迁移动画：迁移期间球体缓慢脉冲，结束恢复 ----

    private fun startPulse() {
        val v = view ?: return
        stopPulse()
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.82f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.82f),
        ).apply {
            duration = 500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        view?.scaleX = 1f
        view?.scaleY = 1f
    }

    private fun handleClick(appContext: Context, controller: VirtualDisplayController) {
        // 列表已展开（OUTSIDE 尚未触发时）：再点球 = 收拢
        if (popup != null) {
            hidePopup()
            return
        }
        // 点球时列表的 OUTSIDE-dismiss 已先行关掉列表：600ms 内视为"用户刚收拢"，不再展开
        if (android.os.SystemClock.uptimeMillis() - lastPopupDismiss < 600) {
            return
        }
        val migrated = controller.migratedPackage
        if (migrated != null) {
            Toast.makeText(
                appContext,
                "「${label(appContext, migrated)}」驻留小窗中；关闭小窗且任务未结束时自动回到虚拟屏",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val display = controller.display.value ?: return
        val candidates = synchronized(controller.runningPackages) {
            controller.runningPackages.toList()
        }
        // 本地已知唯一任务：不查助手，直接迁移（避免主线程 IPC/ANR）
        candidates.singleOrNull()?.let {
            migrateAsync(appContext, controller, it)
            return
        }
        // 其余情况（0 个或多个）：后台查真实任务列表再决定
        Thread {
            val pkgs = runCatching { controller.shellTaskPackages(display.displayId) }
                .getOrNull()
                .orEmpty()
                .filter { it != appContext.packageName }
            main.post {
                when (pkgs.size) {
                    0 -> Toast.makeText(appContext, "虚拟屏上没有可迁移的 App", Toast.LENGTH_SHORT).show()
                    1 -> migrateAsync(appContext, controller, pkgs[0])
                    else -> showList(appContext, controller, pkgs)
                }
            }
        }.start()
    }

    private fun migrateAsync(appContext: Context, controller: VirtualDisplayController, pkg: String) {
        Thread {
            main.post { startPulse() }
            val err = runCatching { controller.migratePackage(pkg) }
                .getOrElse { "迁移失败：${it.message}" }
            main.post {
                stopPulse()
                if (err != null) {
                    Toast.makeText(appContext, err, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---- 任务列表弹窗：标题栏带"收拢"，点外部收拢，点项迁移后自动收拢 ----

    @SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
    private fun showList(appContext: Context, controller: VirtualDisplayController, packages: List<String>) {
        val wm = appContext.getSystemService(WindowManager::class.java) ?: return
        hidePopup()
        val density = appContext.resources.displayMetrics.density
        val listWidth = (240 * density).toInt()
        val card = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(0xF7FFFFFF.toInt())
            }
            elevation = 8f * density
        }
        // 标题栏：任务计数 + 收拢按钮
        card.addView(
            LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                addView(
                    TextView(appContext).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "虚拟屏任务（${packages.size}）"
                        textSize = 12f
                        setTextColor(0xFF666666.toInt())
                    },
                )
                addView(
                    TextView(appContext).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = (8 * density).toInt() }
                        text = "收拢 ▴"
                        textSize = 12f
                        setTextColor(0xFF4C8DFF.toInt())
                        setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                        setOnClickListener { hidePopup() }
                    },
                )
            },
        )
        val rows = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), (6 * density).toInt())
        }
        packages.forEach { pkg ->
            val row = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * density).toInt(), (9 * density).toInt(), (14 * density).toInt(), (9 * density).toInt())
                addView(
                    ImageView(appContext).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (26 * density).toInt(), (26 * density).toInt(),
                        )
                        runCatching { setImageDrawable(appContext.packageManager.getApplicationIcon(pkg)) }
                    },
                )
                addView(
                    TextView(appContext).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                        ).apply { marginStart = (10 * density).toInt() }
                        text = label(appContext, pkg)
                        textSize = 14f
                        setTextColor(0xFF1A1A1A.toInt())
                        maxLines = 1
                    },
                )
                setOnClickListener {
                    hidePopup()
                    migrateAsync(appContext, controller, pkg)
                }
            }
            rows.addView(row)
        }
        val scroll = ScrollView(appContext).apply { addView(rows) }
        card.addView(scroll)
        val p = WindowManager.LayoutParams(
            listWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((ballParams?.x ?: appContext.resources.displayMetrics.widthPixels) - listWidth)
                .coerceAtLeast((8 * density).toInt())
            y = ballParams?.y ?: 0
        }
        // 点列表外部收拢
        card.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                hidePopup()
                true
            } else {
                false
            }
        }
        runCatching { wm.addView(card, p) }.onSuccess { popup = card }
    }

    private fun hidePopup() {
        val v = popup ?: return
        popup = null
        lastPopupDismiss = android.os.SystemClock.uptimeMillis()
        runCatching { v.context.getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    /** 撤掉悬浮球本体；ballParams 由调用方清理（hide 里用于位置记忆）。 */
    private fun hideInternal() {
        val v = view ?: return
        view = null
        runCatching { v.context.getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    private fun label(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull() ?: pkg
}
