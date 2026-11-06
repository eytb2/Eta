package io.github.mangi.eta.agent.phone.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R

/**
 * 虚拟屏常驻通知（自 mika TaskService 的 progressNotification 移植简化）：
 * 虚拟屏存续期间挂在通知栏，标题固定、正文为最近经 startApp 成功启动的 App 显示名，
 * 提供"打开接管窗"与"释放虚拟屏"两个动作。渠道为 [NotificationManager.IMPORTANCE_LOW]，
 * setOngoing + setOnlyAlertOnce，不响铃、不弹横幅。创建虚拟屏成功、启动 App 成功时
 * 由 [VirtualDisplayController] 发布/刷新，释放时撤掉。
 */
internal object VirtualDisplayNotifier {
    /** 通知渠道 id；与 agent/terminal 的后台命令渠道（eta_background_commands）相互独立 */
    const val CHANNEL_ID = "virtual_display"

    /** "释放虚拟屏"按钮的广播 action；只发给本应用的 [VirtualDisplayActionReceiver] */
    const val ACTION_RELEASE = "io.github.mangi.eta.agent.phone.display.action.RELEASE"

    /** "迁移到小窗"按钮的广播 action；与 RELEASE 同 receiver */
    const val ACTION_MIGRATE = "io.github.mangi.eta.agent.phone.display.action.MIGRATE"

    /** 固定通知编号；与 AgentExecutionService(1107)、BackgroundCommandNotification(1124)、语音(4203/4204) 错开 */
    private const val NOTIFICATION_ID = 1125

    /** 当前持有虚拟屏的控制器；App 内可能存在多个控制器实例，通知按钮只释放发布通知的这个 */
    @Volatile
    var owner: VirtualDisplayController? = null
        private set

    /** 发布或刷新常驻通知；[startedPackage] 为最近经 startApp 成功启动的包名，未启动过时为 null */
    fun show(context: Context, controller: VirtualDisplayController, startedPackage: String?, migrated: Boolean = false) {
        owner = controller
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.virtual_display_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        if (!manager.areNotificationsEnabled()) return
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.virtual_display_notif_title))
            .setContentText(
                appLabel(context, startedPackage) +
                    if (migrated) context.getString(R.string.virtual_display_notif_in_small_window) else "",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.virtual_display_notif_action_takeover),
                    migratePendingIntent(context),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.virtual_display_notif_action_release),
                    releasePendingIntent(context),
                ).build(),
            )
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** 撤掉常驻通知并清空 owner 记录；重复调用没有副作用 */
    fun cancel(context: Context) {
        owner = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** "迁移到小窗"：发显式广播给 [VirtualDisplayActionReceiver]（后台执行迁移） */
    private fun migratePendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, VirtualDisplayActionReceiver::class.java).setAction(ACTION_MIGRATE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** "释放虚拟屏"：发给本应用 receiver 的显式广播 */
    private fun releasePendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, VirtualDisplayActionReceiver::class.java).setAction(ACTION_RELEASE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 最近启动 App 的显示名；查不到 label 时退回包名，没启动过任何 App 时用占位文案 */
    private fun appLabel(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return context.getString(R.string.virtual_display_notif_no_app)
        return runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0),
            ).toString()
        }.getOrNull() ?: packageName
    }
}

/**
 * 常驻通知"释放虚拟屏"按钮的落点（Manifest 注册，不导出，只收本应用的显式广播）：
 * 撤掉通知并释放发布通知的那个控制器持有的虚拟屏。
 */
internal class VirtualDisplayActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = VirtualDisplayNotifier.owner ?: PhoneDisplayRegistry.controller(context)
        val action = intent.action ?: return
        // 迁移含 Binder 调用与任务落定等待（最长约 2.6s），goAsync 转后台线程执行
        val pending = goAsync()
        Thread {
            try {
                val appContext = context.applicationContext
                when (action) {
                    VirtualDisplayNotifier.ACTION_MIGRATE -> {
                        val err = runCatching { controller.migrateToSmallWindow() }
                            .getOrElse { "迁移失败：${it.message}" }
                        if (err != null) {
                            android.widget.Toast.makeText(appContext, err, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    VirtualDisplayNotifier.ACTION_RELEASE -> {
                        VirtualDisplayNotifier.cancel(appContext)
                        controller.release()
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
