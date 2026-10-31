package io.github.mangi.eta.agent.phone.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Root 助手进程入口，经
 * `su -c "CLASSPATH=<eta.apk> app_process /system/bin io.github.mangi.eta.agent.phone.display.PhoneShellHelperMain <appUid> <apkPath> <processName>"`
 * 拉起。自建 ActivityThread 取系统上下文，创建 [PhoneShellService] 后经 Eta 的
 * [PhoneShellHandoffProvider] 把 Binder 交给 App 进程；App 进程死亡时经 linkToDeath
 * 撤掉所有虚拟屏。
 */
internal object PhoneShellHelperMain {
    private const val TAG = "EtaPhoneShellMain"
    private const val AUTHORITY = "io.github.mangi.eta.phone.handoff"
    private const val METHOD_ATTACH = "attach"

    @JvmStatic
    fun main(arguments: Array<String>) {
        if (Process.myUid() != 0) {
            Log.e(TAG, "not root; exiting")
            exitProcess(1)
        }
        if (arguments.size < 3) {
            Log.e(TAG, "usage: <appUid> <apkPath> <processName>")
            exitProcess(1)
        }
        val appUid = arguments[0].toIntOrNull()?.also { if (it <= 0) exitProcess(1) } ?: exitProcess(1)
        val apkPath = arguments[1]
        val processName = arguments[2]
        if (System.getProperty("java.class.path") != apkPath) {
            Log.e(TAG, "class path mismatch; exiting")
            exitProcess(1)
        }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val worker = HandlerThread("phone-shell-helper").apply { start() }
        val handler = Handler(worker.looper)
        handler.post { run(appUid, apkPath, processName, handler) }
        Looper.loop()
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun run(appUid: Int, apkPath: String, processName: String, handler: Handler) {
        val service = try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            PhoneShellService(appUid, PhoneShellService.ShellPackageContext(system))
        } catch (t: Throwable) {
            Log.e(TAG, "cannot create service", t)
            exitProcess(1)
        }
        when (deliver(service, appUid, apkPath)) {
            Outcome.Accepted -> PhoneShellService.killOtherInstances(processName)
            Outcome.Rejected -> {
                runCatching { service.releaseAll() }
                exitProcess(0)
            }
            Outcome.Failed -> exitProcess(1)
        }
    }

    private enum class Outcome { Accepted, Rejected, Failed }

    /**
     * 经 ActivityManager 拿 Eta 的 provider（同 `content call` 的做法），把 Binder 交给 App。
     * 交不出去（App 不在前 / provider 拒绝）时按 Rejected 撤屏退出；真正的死亡监听由
     * App 在 accepted 回执里带回的 token Binder（linkToDeath）完成。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun deliver(service: PhoneShellService, appUid: Int, apkPath: String): Outcome {
        return try {
            val userId = appUid / 100_000
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            val token = object : Binder() {}
            val holder = am.javaClass.getMethod(
                "getContentProviderExternal",
                String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java,
            ).invoke(am, AUTHORITY, userId, token, TAG) ?: return Outcome.Failed
            try {
                val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
                    ?: return Outcome.Rejected
                // 包名校验：同名 authority 必须仍属于 Eta 自己的包
                if (!info.packageName.startsWith("io.github.mangi.eta")) return Outcome.Rejected
                val provider = holder.javaClass.getField("provider").get(holder)!!
                val extras = Bundle().apply {
                    putBinder(PhoneShellHandoffProvider.KEY_BINDER, service)
                    putInt(PhoneShellHandoffProvider.KEY_UID, Process.myUid())
                    putInt(PhoneShellHandoffProvider.KEY_PID, Process.myPid())
                    putString(PhoneShellHandoffProvider.KEY_APK, apkPath)
                }
                val call = provider.javaClass.methods.first {
                    it.name == "call" && it.parameterTypes.size == 5 &&
                        it.parameterTypes[0] == android.content.AttributionSource::class.java
                }
                val caller = android.content.AttributionSource.Builder(Process.myUid())
                    .setPackageName("com.android.shell").build()
                val result = call.invoke(
                    provider, caller, AUTHORITY, METHOD_ATTACH, null, extras,
                ) as Bundle?
                when {
                    result?.getBoolean(PhoneShellHandoffProvider.KEY_ACCEPTED) != true -> {
                        Log.w(TAG, "handoff rejected: ${result?.getString(PhoneShellHandoffProvider.KEY_REASON)}")
                        Outcome.Rejected
                    }
                    else -> {
                        // 收下 App 的死亡令牌：App 被杀后撤掉所有虚拟屏
                        val appBinder = result.getBinder(PhoneShellHandoffProvider.KEY_TOKEN)
                        if (appBinder != null) {
                            try {
                                appBinder.linkToDeath({
                                    runCatching { service.releaseAll() }
                                }, 0)
                            } catch (_: Throwable) {
                                runCatching { service.releaseAll() }
                            }
                        }
                        Log.i(TAG, "handed off to io.github.mangi.eta (uid $appUid)")
                        Outcome.Accepted
                    }
                }
            } finally {
                runCatching {
                    am.javaClass.getMethod(
                        "removeContentProviderExternalAsUser",
                        String::class.java, IBinder::class.java, Int::class.javaPrimitiveType,
                    ).invoke(am, AUTHORITY, token, userId)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "handoff failed", t)
            Outcome.Failed
        }
    }
}
