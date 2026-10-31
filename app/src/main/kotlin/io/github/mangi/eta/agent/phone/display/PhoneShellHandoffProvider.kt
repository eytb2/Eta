package io.github.mangi.eta.agent.phone.display

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

/**
 * Root 助手交接 Binder 的落点（自 mika 的 HandoffProvider / HelperHub 移植简化）。
 * exported 且不加权限——普通 App 拿不到它的调用资格没有意义，真正的把关在 [offer]：
 * 只收 Root(uid 0) 助手、自报 uid/pid 与调用方一致、跑的是当前这份 APK。
 */
internal class PhoneShellHandoffProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "attach") return null
        val ctx = context ?: return null
        return PhoneShellHub.offer(ctx, extras, Binder.getCallingUid(), Binder.getCallingPid())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val KEY_BINDER = "binder"
        const val KEY_UID = "uid"
        const val KEY_PID = "pid"
        const val KEY_APK = "apk"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_REASON = "reason"
        const val KEY_TOKEN = "token"
    }
}

/**
 * App 进程侧的助手 Binder 落点与启动器。
 * [offer] 核对交接请求；[ensureStarted] 用 Root 拉起助手进程并等待交接完成。
 */
internal object PhoneShellHub {
    private const val TAG = "EtaPhoneShellHub"
    private const val PROCESS_NAME = "io.github.mangi.eta:phonehelper"
    private const val HANDOFF_TIMEOUT_MS = 10_000L

    @Volatile
    private var shellRef: IPhoneShell? = null

    @Volatile
    private var appToken: Binder? = null

    /** 当前助手接口；助手进程死亡时变回 null。 */
    val shell: IPhoneShell? get() = shellRef

    @Synchronized
    fun offer(context: Context, extras: Bundle?, callingUid: Int, callingPid: Int): Bundle {
        fun reject(why: String): Bundle = Bundle().apply {
            putBoolean(PhoneShellHandoffProvider.KEY_ACCEPTED, false)
            putString(PhoneShellHandoffProvider.KEY_REASON, why)
        }
        val binder = extras?.getBinder(PhoneShellHandoffProvider.KEY_BINDER)
            ?: return reject("no_binder")
        // 只收 Root 助手
        if (callingUid != 0) return reject("not_root")
        if (extras.getInt(PhoneShellHandoffProvider.KEY_UID, -1) != callingUid ||
            extras.getInt(PhoneShellHandoffProvider.KEY_PID, -1) != callingPid
        ) return reject("identity_mismatch")
        // 包名校验：助手必须跑在当前这份 APK 里
        if (extras.getString(PhoneShellHandoffProvider.KEY_APK) != context.applicationInfo.sourceDir) {
            return reject("apk_mismatch")
        }
        val shell = IPhoneShell.Stub.asInterface(binder)
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size < 2 || ids[0] != callingUid || ids[1] != callingPid) {
            return reject("binder_elsewhere")
        }
        try {
            binder.linkToDeath({ if (shellRef === shell) shellRef = null }, 0)
        } catch (_: Throwable) {
            return reject("no_binder")
        }
        shellRef = shell
        Log.i(TAG, "root helper pid $callingPid handed off")
        return Bundle().apply {
            putBoolean(PhoneShellHandoffProvider.KEY_ACCEPTED, true)
            val token = appToken ?: Binder().also { appToken = it }
            putBinder(PhoneShellHandoffProvider.KEY_TOKEN, token)
        }
    }

    /**
     * 确保助手在运行：没握过手时用 Root 后台拉起 app_process，等待 Binder 交接。
     * [launch] 由调用方注入 Root 执行器（避免这里依赖具体通道），返回是否启动成功。
     */
    fun ensureStarted(context: Context, launch: (command: String) -> Boolean): IPhoneShell? {
        shellRef?.let { return it }
        val apk = context.applicationInfo.sourceDir
        val command =
            "setsid sh -c \"CLASSPATH='$apk' app_process /system/bin " +
                "io.github.mangi.eta.agent.phone.display.PhoneShellHelperMain " +
                "${android.os.Process.myUid()} '$apk' '$PROCESS_NAME'\" >/dev/null 2>&1 &"
        if (!launch(command)) return null
        val deadline = SystemClock.elapsedRealtime() + HANDOFF_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            shellRef?.let { return it }
            SystemClock.sleep(100)
        }
        return shellRef
    }

    /** 通知助手撤掉所有屏并退出；供虚拟屏总释放时调用。 */
    fun shutdown() {
        val shell = shellRef ?: return
        runCatching { shell.destroy() }
        shellRef = null
    }
}
