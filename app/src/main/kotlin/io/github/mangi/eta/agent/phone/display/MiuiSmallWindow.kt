package io.github.mangi.eta.agent.phone.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * MIUI 小窗启动工具：反射调用 HyperOS 隐藏 API
 * `android.util.MiuiMultiWindowUtils.getActivityOptions(context, pkg, true, false)`
 * （HyperCeiler 同款入口），让目标 Activity 以系统原生小窗形态打开——
 * 自带贴边、拖动、缩成小球等完整 MIUI 交互，无需 Xposed。
 * 非 MIUI 设备退回 freeform（windowingMode 5）。
 */
internal object MiuiSmallWindow {

    private const val TAG = "MiuiSmallWindow"

    /**
     * 以 MIUI 小窗启动 [intent] 指向的 Activity。
     * @return 成功返回 null；失败返回用户可读的错误描述。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun start(context: Context, intent: Intent, targetPackage: String? = null): String? {
        return try {
            val pkg = targetPackage ?: intent.component?.packageName
                ?: return "intent 未指定组件"
            val cls = Class.forName("android.util.MiuiMultiWindowUtils")
            val options = cls
                .getMethod(
                    "getActivityOptions",
                    Context::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
                .invoke(null, context, pkg, true, false)
            val bundle = options?.let {
                Class.forName("android.app.ActivityOptions").getMethod("toBundle").invoke(it) as? Bundle
            }
            context.startActivity(intent, bundle)
            null
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "getActivityOptions failed, fallback freeform: " + t)
            fallbackFreeform(context, intent)
        }
    }

    /** 非 MIUI 路径：root am start --windowingMode 5。 */
    private fun fallbackFreeform(context: Context, intent: Intent): String? {
        return try {
            val comp = intent.component ?: return "intent 未指定组件"
            val am = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "am start --windowingMode 5 -n '${comp.flattenToShortString()}'"),
            )
            am.waitFor()
            if (am.exitValue() == 0) null else "freeform 启动失败 (exit=${am.exitValue()})"
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "freeform fallback failed: " + t)
            "小窗启动失败：$t"
        }
    }
}
