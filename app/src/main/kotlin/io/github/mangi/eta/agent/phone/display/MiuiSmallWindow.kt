package io.github.mangi.eta.agent.phone.display

import android.content.Context
import android.content.Intent

/**
 * MIUI 小窗启动工具：MIUI 的 [android.util.MiuiMultiWindowUtils.getActivityOptions]
 * 要求调用方在小米云控的授权列表里（第三方一律拒绝），且其内部 Settings/云数据
 * provider 查询在裸 app_process 里会失败。因此不走该 API，改为由 Root 助手
 * （[PhoneShellService]，系统 context，授权检查天然通过）手动构造 ActivityOptions：
 * windowingMode=5 + 居中 bounds + setMiuiConfigFlag(2)，再反射
 * IActivityTaskManager.startActivityAsUser 以 launcher 包名启动（绕过后台启动限制）。
 * 已在 HyperOS 3.0.303 真机验证（微信以原生小窗运行）。
 */
internal object MiuiSmallWindow {

    /**
     * 以 MIUI 原生小窗在真屏启动 [intent] 指向的组件（仅用其 component）。
     *
     * 注意：小窗参数只对“新建任务”生效——目标已有后台任务时会复用并全屏打开。
     * 调用方负责先结束目标任务。
     *
     * @return 成功返回 null；失败返回用户可读的错误描述。
     */
    fun start(context: Context, intent: Intent, targetPackage: String? = null): String? {
        val component = intent.component ?: return "intent 未指定组件"
        val pkg = targetPackage ?: component.packageName
        val shell = PhoneShellHub.shell ?: PhoneShellHub.ensureStarted(context) { command ->
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
        } ?: return "Root 助手未就绪"
        return try {
            when (shell.startSmallWindow(pkg, component.className)) {
                PhoneShellService.START_OK -> null
                PhoneShellService.START_NOT_FOUND -> "未找到 $pkg 的启动入口"
                PhoneShellService.START_BLACKLISTED -> "$pkg 在 MIUI 小窗黑名单中（小米官方标注不适合小窗，如相机/支付/游戏类）"
                else -> "小窗启动失败"
            }
        } catch (t: Throwable) {
            "小窗启动失败：$t"
        }
    }
}
