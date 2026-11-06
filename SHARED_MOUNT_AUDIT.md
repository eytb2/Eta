# SHARED_MOUNT_AUDIT.md — Fork 共享目录挂载机制审计

> 对象：`agent/terminal/` 的 `RootBindMounts.kt`（140 行）、`SharedFolderMounts.kt`（151 行）、`ShellProcessSupervisor.kt`（payload 构建）、`SharedFoldersScreen.kt`（UI）
> 审计时间：2026-11-01 | 实测设备：小米（HyperOS，Android 16，KernelSU，f2fs userdata dm-46）
> 证据等级：**[代码]** / **[实测]** / **[推断]**

---

## 1. 数据模型

```kotlin
enum class SharedFolderKind { PROOT_BIND, ROOT_BIND }
data class SharedFolderMount(
    val name: String,                       // 白名单 [A-Za-z0-9._-]
    val sourcePath: String,                 // 经 normalizeSourcePath 归一化（无换行/NUL）
    val kind: SharedFolderKind = PROOT_BIND,
)
const val ANDROID_MOUNTS_ROOT = "/data/local/tmp/eta/mounts"      // Android 全局挂载根
const val LINUX_MOUNTS_ROOT = "/workspace/mounts"                 // chroot 内挂载根（rootfs 相对）
```

- 持久化：JSON（`kind` 记为 `KIND_ROOT`/`KIND_PROOT`），每条共享项跨会话保留。
- 两个挂载根：Android 侧 `/data/local/tmp/eta/mounts/<name>`（ROOT_BIND 全局挂载）；Linux/chroot 侧 `/workspace/mounts/<name>`（会话 namespace 内 bind）。

## 2. 真实机制（[实测] /proc/mounts 证据）

| 挂载 | /proc/mounts 证据 | 机制 |
|---|---|---|
| Linux 工作区 | `/dev/block/dm-46 /workspace f2fs rw,...` | **chroot 根** `/data/local/tmp/eta` 的 bind（KernelSU/chroot 环境），`/workspace` 即同一 f2fs 分区路径 |
| 共享项 `sdcard` | `/dev/fuse /workspace/mounts/sdcard fuse rw,nosuid,noexec,noatime,user_id=0,group_id=0,allow_other` | **sdcard 共享项 = `/storage/emulated/0` 的 FUSE 视图被 bind 进 chroot**——不是块设备直挂，是 FUSE (sdcardfs 上层) 的 `allow_other` 挂载 |
| 会话隔离 | chroot `/proc/self/ns/mnt → mnt:[4026535932]` | **独立 mount namespace**：会话内 bind 不泄漏到 Android 全局；会话结束随 namespace 销毁 |
| Android 全局 | （未挂载时无条目；挂载后为 `none /data/local/tmp/eta/mounts/<name> ... bind`） | `mount -o bind source target`（ROOT_BIND 手动全局挂载） |

**结论（回答 spec 14.2"识别真实机制"）**：不是 PRoot 用户态虚拟、也不是 overlay——**chroot 后端 = 真内核 bind mount（mount namespace 隔离）**；共享源如果是 `/storage/emulated/0` 子目录，底层经过 **FUSE**（`/dev/fuse`，`allow_other`）。PRoot 后端则为用户态 `-b` 路径映射（无内核挂载）。

## 3. 生命周期

### 3.1 chroot 会话挂载（自动）
```text
ShellProcessSupervisor 启动 Linux 会话
  → 新建独立 mount namespace（unshare）
  → payload 内 eta_mount_optional/eta_mount_required()：
      busybox mount -o <options> <source> "$eta_rootfs/workspace/mounts/<name>"
  → 会话结束 → namespace 销毁 → 挂载自动消失（无需显式 umount）
```
- `eta_mount_optional`：失败不阻断会话（exit 不致命）；`eta_mount_required`：失败 exit 125。
- 选项：`mount -o "$eta_options"`（默认 bind；PROOT_BIND 后端在 chroot 内同样走 bind）。

### 3.2 全局 ROOT_BIND 挂载（手动，RootBindMounts）
```text
mount:    root mkdir -p /data/local/tmp/eta/mounts/<name> && mount -o bind <source> <target>
probe:    grep -qsF " <target> " /proc/mounts → 逐项回读状态（StateFlow mountedNames）
umount:   umount <target> || umount -l <target>（lazy 兜底），目标未挂载视为成功
unmountAll: 批量卸载，单个失败不中断，聚合失败清单
```
- 每步 probe 回读验证（不信任 exit code 单一信号）。

### 3.3 重启恢复
- **重启后全局挂载消失**（内核 mount 不持久）——[推断]：无开机自动重挂逻辑（未发现 BootReceiver 引用 RootBindMounts），用户需在 SharedFoldersScreen 手动重挂。**[待验证]**：重启后 chroot 会话内共享项同样需重新启动会话。

### 3.4 异常清理
- `unmountAll` 在 releaseAll/会话清理路径调用；lazy umount 兜底 busy 目标。
- 挂载点目录（mounts/<name>）残留空目录——无害，未清理（[代码]）。

## 4. 与 Agent File Tool / Terminal 的集成

- **chroot/Terminal**：`/workspace`（= `/data/local/tmp/eta`）内 `mounts/<name>` 即共享目录——Terminal 的 cwd 工作区与 File Tool 的 workspace 路径**同一映射**，无双份路径配置（[代码] `TerminalExecutionContext` 统一 workspace；[实测] 本会话 /workspace 即该目录）。
- **Android 侧 File Tool**：全局 ROOT_BIND 挂载后 `/data/local/tmp/eta/mounts/<name>` 对 root File Tool 可见（RootBindMounts 注释明示此用途）。
- **双份配置风险**：低（同一 SharedFolderMount 列表驱动两侧）；**但 Android 侧路径（/data/local/tmp/eta/mounts）与 Linux 侧路径（/workspace/mounts）是同一物理目录的两个挂载点**——Agent 需知道这一映射（[实测] 本会话反复使用）。

## 5. 权限与安全

- 归一化 + 白名单 + 全转义：命令注入面已封闭（[代码]）。
- 读写：**全局 bind 默认读写**（spec 4.13.4 要求默认只读——**差距**）。
- 符号链接：未做显式检查（sourcePath 内 symlink 可指向任意路径——root bind 会照挂；spec 4.5 路径穿越要求——**差距**）。
- 目标目录覆盖：`mkdir -p` + bind 覆盖空目录；若目标已挂载再次 mount 会叠加（probe 可发现，未阻止）——**小问题**。
- 敏感目录：无默认拒绝列表（spec 4.5 要求凭证/私有目录默认拒绝——**差距**）。

## 6. 与上游的分叉

- **上游 v3.4.0 删除了 fork 的三个 Mount 文件（-447 行）与 SharedFoldersScreen（-177 行）**，新增 `ROOTLESS_SUPPORT.md`——上游共享目录方案转向 **rootless**（不再依赖 root bind）。
- **fork 方案的价值**：chroot + 独立 namespace + root bind 是**实测可用**的强隔离方案（Linux 工具链完整依赖它）；上游 rootless 方案在本设备（KernelSU + Debian chroot 已深度使用）上**无法等价替代**（chroot 本身需要 root）。
- **建议**：fork 保留 root bind 路线，吸收 spec 4.13 的 Mount Manager 收敛（默认只读、白名单敏感目录、重启重挂提示）。

## 7. 问题清单

| # | 项 | 等级 | 说明 |
|---|---|---|---|
| 1 | 全局 bind 默认读写 | 中 | spec 4.13.4 要求默认只读，用户显式开读写 |
| 2 | sourcePath symlink 未检查 | 中 | root bind 可挂载任意可达路径 |
| 3 | 重启后无自动重挂 | 低 | 需手动；可加 BootReceiver 或启动时探测提示 |
| 4 | 重复 mount 叠加 | 低 | probe 可发现但 mount() 未先检查已挂载 |
| 5 | 敏感目录默认拒绝列表缺失 | 中 | spec 4.5/4.13.4 要求 |
| 6 | mounts/<name> 空目录残留 | 低 | 卸载后未 rmdir |
