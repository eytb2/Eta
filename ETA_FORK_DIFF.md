# ETA_FORK_DIFF.md — eytb2/Eta Fork 与上游 Mangi-11/Eta 差异审计

> 审计对象：`/workspace/Eta` 工作区检出（branch `main`）
> 审计时间：2026-11-01
> 审计人：Eta Agent（深度会话，含真机操作与源码级比对）
> 证据等级标注：**[代码]** = 源码级确认；**[实测]** = 本会话真机验证；**[声明]** = 仅文档/提交信息声明，未实测

---

## 1. 仓库基线

| 项 | 值 | 证据 |
|---|---|---|
| Fork remote | `https://github.com/eytb2/Eta`（PAT 认证，见本地 remote 配置） | **[代码]** `.git/config` |
| 上游 remote | `https://github.com/Mangi-11/Eta`（本次审计 fetch 为 `official-v3.4.0` 本地分支，commit `e90922a`） | **[代码]** |
| 当前分支 / HEAD | `main` / `90e0669` "update: virtual display adjustments (user edits)" | **[代码]** |
| 工作区状态 | **11 个文件未提交**（悬浮球 + 迁移无损化 + Qwen review 修复，见 §4） | **[代码]** |
| 共同祖先（merge-base） | `3019c43` "docs(readme): 补充关闭外部 PR 与贡献方式说明" | **[代码]** `git merge-base` |
| Fork 独有提交 | **7 个**（见 §2） | **[代码]** |
| 上游领先提交 | **6 个**（voice 重构、双环境分工、**上游自己的虚拟屏实现** b0dc20e、API 33 兼容、v3.4.0 发布） | **[代码]** |
| versionCode | 工作区 `2026103109`；设备当前 `2026103108`；上游 v3.4.0 tag 对应 v3.3.5 versionName | **[实测]** dumpsys + build.gradle.kts |

## 2. Fork 独有提交（7 个，时间序）

```
0675828 feat: virtual display (root helper + takeover window) & shared folder root bind mounts
473cbb5 feat: MIUI native small window for virtual display takeover
0ca13fc fix: setsid for root app_process helper so it survives su session exit
d80896f docs: note small-window options only apply to new tasks
890d21a feat: MIUI small-window blacklist check + release build support
228ccac feat: mika-style virtual display notification + control hardening
90e0669 update: virtual display adjustments (user edits)   ← 含 2026-11-01 的无损迁移+悬浮球+review 修复
```

另有 **11 个未提交工作区文件**（`git status`）：无损迁移（`relocateTask`）、`present_to_user`、音频焦点 appops、悬浮球重写（收拢/脉冲/位置记忆）、迁移态通知文案、注入互斥等。**上游完全不知道这些改动**。

## 3. 两项 Fork 重点改动的机制确认

### 3.1 Virtual Display（Fork 独有实现，`agent/phone/display/` 10 文件）

| 机制项 | Fork 实际实现 | 等级 |
|---|---|---|
| 显示创建 | App 进程内经 root helper（`app_process` 独立进程，Binder `IPhoneShell`）调 `DisplayManager.createVirtualDisplay`，flags 含 `FLAG_TRUSTED \| OWN_DISPLAY_GROUP \| ALWAYS_UNLOCKED \| OWN_FOCUS \| STEAL_TOP_FOCUS_DISABLED` 等（API 34 分支） | **[代码+实测]** |
| 权限后端 | **Root 单路径**（无 LSPosed 路径；上游 v3.4.0 为 LSPosed system hook + root 桥双路径） | **[代码]** |
| Surface | ImageReader（`maxImages`：初版 2，工作区未改）→ latest-frame 位图缓存 | **[代码]** |
| 截图 | `latestFramePng()` 写 app cache（保留 8 张）；Agent 经 `virtual_display_screenshot` 读取 | **[实测]** |
| 输入注入 | helper 侧 `InputManagerGlobal.injectInputEvent`（`SOURCE_TOUCHSCREEN`，displayId 注入），fallback `input -d`；stroke 逐点最长 10s | **[代码+实测]** |
| Activity 启动 | `IActivityTaskManager.startActivityAsUser`（callingPackage=`com.android.shell` / `com.miui.home`）+ ActivityOptions（displayId / MIUI freeform 降级链） | **[代码+实测]** |
| 真屏接管 | 两代：①镜像式 `VirtualDisplayActivity`（Surface 透传，v3103 前默认）②**任务迁移式**（v3105 起：`relocateTask`/`migrateDisplayAppToSmallWindow` 把 Task 搬到 display 0 的 MIUI 原生小窗） | **[代码+实测]** |
| 无损迁移 | 工作区新增 `relocateTask`（`setTaskWindowingMode` + `moveRootTaskToDisplay` + `resizeTask`，自官方 v3.4.0 handoff 移植）——Activity 不重建，页面状态保留；失败自动回退销毁式 | **[代码]**（未编译验证） |
| 回虚拟屏 | 用户关闭小窗 → controller 协程每 2s 监听 → 重建虚拟屏 + `startApp`（Activity 重建）→ 或无损 `relocateTask` 回原屏 | **[实测]** |
| 主屏保护 | helper `inUseOnMainScreen`：目标包在主屏可见（或为最近任务）时拒绝拉虚拟屏 / 拒绝迁移 | **[代码+实测]** |
| 音频焦点 | 工作区新增：startApp 时 `cmd appops TAKE_AUDIO_FOCUS ignore`，releaseAll 恢复 | **[代码]** |
| 释放 | `DESTROY_CONTENT_ON_REMOVAL` + `VirtualDisplay.release()`；音频焦点恢复 | **[代码]** |
| 悬浮球 | `VirtualDisplayBubble`（TYPE_APPLICATION_OVERLAY，渐变球、任务角标、任务列表弹窗、单击迁移、拖动位置记忆 SharedPreferences、脉冲动画） | **[代码+实测]** |

### 3.2 Root Shared Folder Mount（Fork 独有实现，`agent/terminal/` 3 文件）

| 机制项 | Fork 实际实现 | 等级 |
|---|---|---|
| 双后端 | `SharedFolderKind`：`PROOT_BIND`（PRoot 用户态 `-b`）/ `ROOT_BIND`（chroot 后端 root bind mount） | **[代码]** |
| chroot 会话挂载 | `ShellProcessSupervisor.buildLinuxPayload`：Linux 环境**独立 mount namespace** 启动，启动 payload 内 `eta_mount_optional/required` 把共享项 bind 到 rootfs `/workspace/mounts/<name>`，**会话结束随 namespace 回收** | **[代码+实测]**（mnt ns `[4026535932]`） |
| 全局挂载 | `RootBindMounts`：Android 全局命名空间 `/data/local/tmp/eta/mounts/<name>` `mount -o bind`（供 Android 侧 root 文件工具会话外访问）；probe 用 `grep -qsF /proc/mounts`；umount 带 lazy 兜底；`unmountAll` 聚合 | **[代码]** |
| 真实内核机制 | **[实测]** `/proc/mounts`：`/workspace` = `/data/local/tmp`（f2fs dm-46 bind）；`/workspace/mounts/sdcard` = **`/dev/fuse`**（`allow_other`）——即 sdcard 共享项是 **FUSE 视图再 bind**，非块设备直挂 | **[实测]** |
| 路径安全 | `normalizeSourcePath`（无换行/NUL）、name 白名单 `[A-Za-z0-9._-]`、`shellQuote` 全转义、mkdir -p 由 root 完成 | **[代码]** |
| 默认读写 | 全局 bind 为**读写**（spec 4.13.4 要求默认只读——**差距**，见 GAP 文档） | **[代码]** |
| UI | `SharedFoldersScreen.kt`（挂载/卸载/状态） | **[代码]** |
| 上游动向 | **上游 v3.4.0 删除了 fork 的 RootBindMounts/SharedFolderMounts/SharedFoldersScreen（-447 行）并新增 `ROOTLESS_SUPPORT.md`**——上游转向 rootless 共享方案，与 fork 的 root bind 路线**分叉** | **[代码]** |

## 4. 上游 v3.4.0 的并行虚拟屏实现（b0dc20e，与 Fork 独立）

| 维度 | 上游实现（`agent/virtualdisplay/` + `agent/overlay/` + `hook/system/`） |
|---|---|
| 权限后端 | **LSPosed system 作用域 hook（进程内免 helper）+ root 桥**双路径（`tryCreateInProcessVirtualDisplay` / `tryCreateRootBridgedVirtualDisplay`） |
| App 位置 | **Task 永不离虚拟屏**（freeform 窗口化在虚拟屏内）；真屏用自绘胶囊取景器（18×32dp）+ 装裱视窗展示 |
| 用户接管 | `dispatchUserTapOnCanvas/dispatchUserSwipeOnCanvas`：装裱视窗触摸 → 虚拟屏坐标转发 |
| 临门一脚 | `present_to_user` 工具 + `handoffToMainDisplay`：**`moveRootTaskToDisplay` 无损迁回主屏全屏**（Activity 不重建） |
| 节流 | 收起态低频 / 展开态高帧 + `captureScreenshotBitmap(waitUpToMs=450)` |
| 音频焦点 | `cmd appops TAKE_AUDIO_FOCUS ignore`（与 Fork 工作区新代码同思路，**独立撞车**） |
| 主屏保护 | `CanvasRoutingPolicy`：launcher/SystemUI 清单 + 前台同包名不抽走 + AUTO/VIRTUAL/MAIN 三态 |

**分叉风险**：两条虚拟屏实现（fork `phone/display` vs 上游 `virtualdisplay`）**类名部分冲突**（`VirtualDisplayController` 同名不同包）、**工具目录冲突**（`AgentVirtualDisplayToolCatalog` vs 上游工具注册）、后续 rebase 上游时**必然冲突**。已在本地保存上游快照分支 `official-v3.4.0`。

## 5. 官方已实现 / Fork 待对齐清单（详见 IMPLEMENTATION_GAP.md）

1. `moveRootTaskToDisplay` 无损迁移（fork 工作区已移植 `relocateTask`，**[代码]** 待编译验证）
2. `present_to_user` 工具（fork 工作区已实现，**[代码]** 待编译验证）
3. 音频焦点 appops（双方独立实现，**[代码]**）
4. CanvasRoutingPolicy 结构化路由（fork 有等价检查 `inUseOnMainScreen`，缺结构化策略对象与 AUTO/VIRTUAL/MAIN 三态参数）
5. ImageReader 节流（fork 未做两档节流）
6. API 33 `createVirtualDisplay` 签名兼容（上游 6dcc665 修复，fork 未对齐——fork flags 组合在 API 33 的行为待验证）

## 6. 结论

Fork 的两项重点改动（Virtual Display、Root Shared Folder Mount）**均为真实、可运行的独立实现**，与上游 v3.4.0 的并行实现形成**设计分叉**：

- **Virtual Display**：fork 走"task 迁移 + MIUI 原生小窗"（原生交互、零转发代码），上游走"画布驻留 + 自绘视窗"（零重建、需自建交互层）。两条路线已各自真机验证，**不应互相替换**，建议以"引擎可切换"方式长期并存。
- **Shared Mount**：fork 的 root bind mount + 独立 namespace 方案**已实测可用**；上游删除该功能转向 rootless——fork 应保留并按 spec 4.13 补"默认只读"策略与 Mount Manager 收敛。
