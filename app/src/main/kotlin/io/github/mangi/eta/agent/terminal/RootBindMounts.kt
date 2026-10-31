package io.github.mangi.eta.agent.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ROOT_BIND 共享项的全局 bind mount 管理。
 *
 * chroot 会话本身已在各自的私有 mount namespace 中对全部共享项做 bind mount
 * （见 [ShellProcessSupervisor.buildLinuxPayload]），会话结束即随 namespace 回收，
 * 因此不存在需要引用计数管理的会话级全局挂载。本对象负责的是 Android 全局命名空间里的
 * “手动挂载”：让 /data/local/tmp/eta/mounts/<name> 在会话之外也可见
 * （例如供 Android 侧 root 文件工具直接访问），并提供挂载状态查询与“全部卸载”入口。
 *
 * 全部命令通过 root 身份的一次性 Shell 执行；sourcePath 已由
 * [SharedFolderMounts.normalizeSourcePath] 归一化（无换行、无 NUL），name 只含 [A-Za-z0-9._-]，
 * 并统一经 [shellQuote] 转义，不存在命令注入面。
 */
internal object RootBindMounts {

    private val mutableMountedNames = MutableStateFlow<Set<String>>(emptySet())
    val mountedNames: StateFlow<Set<String>> = mutableMountedNames.asStateFlow()

    /** Android 宿主侧挂载点：workspace 目录本身会被 chroot namespace bind 到 /workspace。 */
    fun hostTarget(name: String): String = "${SharedFolderMounts.ANDROID_MOUNTS_ROOT}/$name"

    data class OperationResult(val ok: Boolean, val message: String)

    /** 批量探测当前 Android 命名空间中的挂载状态；失败时返回空 Map，UI 按未知处理。 */
    fun probe(
        supervisor: ShellProcessSupervisor,
        mounts: List<SharedFolderMount>,
    ): Map<String, Boolean> {
        val rootBinds = mounts.filter { it.kind == SharedFolderKind.ROOT_BIND }
        if (rootBinds.isEmpty()) {
            mutableMountedNames.value = emptySet()
            return emptyMap()
        }
        val result = runOneShotShell(
            processSupervisor = supervisor,
            identity = "root",
            // name 只含安全字符，可直接拼进单引号字面量。
            command = rootBinds.joinToString("\n") { mount ->
                val target = hostTarget(mount.name)
                "if grep -qsF ${shellQuote(" $target ")} /proc/mounts 2>/dev/null; then " +
                    "echo '${mount.name} 1'; else echo '${mount.name} 0'; fi"
            },
            timeoutSeconds = 15,
        )
        if (result.exitCode != 0) return emptyMap()
        val statuses = result.output.decodeToString().lineSequence().mapNotNull { line ->
            val parts = line.trim().split(" ")
            if (parts.size == 2) parts[0] to (parts[1] == "1") else null
        }.toMap()
        mutableMountedNames.value = statuses.filterValues { it }.keys
        return statuses
    }

    /** 全局 bind mount（读写）。挂载点目录由 root mkdir -p 创建。 */
    fun mount(
        supervisor: ShellProcessSupervisor,
        mount: SharedFolderMount,
    ): OperationResult {
        require(mount.kind == SharedFolderKind.ROOT_BIND) { "仅 ROOT_BIND 条目支持全局挂载" }
        val target = hostTarget(mount.name)
        val result = runOneShotShell(
            processSupervisor = supervisor,
            identity = "root",
            command = "mkdir -p ${shellQuote(target)} && " +
                "mount -o bind ${shellQuote(mount.sourcePath)} ${shellQuote(target)}",
            timeoutSeconds = 20,
        )
        val refreshed = probe(supervisor, listOf(mount))
        return if (result.exitCode == 0 || refreshed[mount.name] == true) {
            OperationResult(true, "")
        } else {
            OperationResult(false, describe(result))
        }
    }

    /** 卸载；目标未挂载视为成功。普通卸载失败时以 lazy umount 兜底。 */
    fun umount(
        supervisor: ShellProcessSupervisor,
        mount: SharedFolderMount,
    ): OperationResult {
        require(mount.kind == SharedFolderKind.ROOT_BIND) { "仅 ROOT_BIND 条目支持全局卸载" }
        val target = hostTarget(mount.name)
        val targetArg = shellQuote(target)
        val pattern = shellQuote(" $target ")
        val result = runOneShotShell(
            processSupervisor = supervisor,
            identity = "root",
            command = "if grep -qsF $pattern /proc/mounts 2>/dev/null; then " +
                "umount $targetArg 2>/dev/null || umount -l $targetArg; fi",
            timeoutSeconds = 20,
        )
        val refreshed = probe(supervisor, listOf(mount))
        return if (result.exitCode == 0 && refreshed[mount.name] != true) {
            OperationResult(true, "")
        } else {
            OperationResult(false, describe(result))
        }
    }

    /** 卸载全部 ROOT_BIND 条目；单个失败不中断，返回聚合结果。 */
    fun unmountAll(
        supervisor: ShellProcessSupervisor,
        mounts: List<SharedFolderMount>,
    ): OperationResult {
        val rootBinds = mounts.filter { it.kind == SharedFolderKind.ROOT_BIND }
        if (rootBinds.isEmpty()) return OperationResult(true, "")
        val result = runOneShotShell(
            processSupervisor = supervisor,
            identity = "root",
            // 输出中只回显卸载失败的条目名；name 为安全字符集。
            command = rootBinds.joinToString("\n") { mount ->
                val targetArg = shellQuote(hostTarget(mount.name))
                val pattern = shellQuote(" ${hostTarget(mount.name)} ")
                "if grep -qsF $pattern /proc/mounts 2>/dev/null; then " +
                    "umount $targetArg 2>/dev/null || umount -l $targetArg || echo '${mount.name}'; fi"
            },
            timeoutSeconds = 30,
        )
        val remaining = probe(supervisor, rootBinds)
        val failed = remaining.filterValues { it }.keys + result.output.decodeToString()
            .lineSequence().map(String::trim).filter(String::isNotEmpty)
        return if (result.exitCode == 0 && failed.isEmpty()) {
            OperationResult(true, "")
        } else {
            OperationResult(false, failed.joinToString(", "))
        }
    }

    private fun describe(result: OneShotShellResult): String {
        val stderr = result.stderr.decodeToString().trim()
        val stdout = result.output.decodeToString().trim()
        return stderr.ifBlank { stdout }.ifBlank { "exit=${result.exitCode}" }
    }
}
