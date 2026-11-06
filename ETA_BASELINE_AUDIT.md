# ETA_BASELINE_AUDIT.md — Eta 工作区基线审计

> 对象：`/workspace/Eta`（eytb2/Eta fork，main @ 90e0669 + 11 文件未提交）| 审计时间：2026-11-01
> 配套：`ETA_FORK_DIFF.md` / `VIRTUAL_DISPLAY_AUDIT.md` / `SHARED_MOUNT_AUDIT.md` / `IMPLEMENTATION_GAP.md`

---

## 1. 工程结构

| 项 | 值 | 证据 |
|---|---|---|
| 构建 | Gradle（Kotlin DSL），versionCode 2026103109（工作区），versionName 3.3.5，minSdk 33 / targetSdk 37，**arm64-v8a only**，R8 + resource shrink（release），desugaring | **[代码]** app/build.gradle.kts |
| 语言 | Kotlin（app/src/main/kotlin）+ AIDL（IPhoneShell）+ Compose（ui） | **[代码]** |
| 测试 | app/src/test（JUnit：AgentPromptBuilderTest、CanvasRoutingPolicyTest*、SpeechLifecycleTest 等；*为上游新增） | **[代码]** |
| 模块 | 单模块 app + LSPosed hook 包（hook/system）+ chroot Linux 环境（terminal） | **[代码]** |
| 签名 | release 走 `ETA_RELEASE_*` 环境变量（keystore 不入库）；debug 用默认 debug.keystore | **[代码]** |
| 本地编译环境 | chroot Debian：JDK 21、Android SDK（platforms 35/37.0、build-tools 34/35/36）、Gradle 8.14.3、代理 127.0.0.1:7890、阿里云 Maven 镜像 | **[实测]**（HelloApp 验证记录 + 本轮构建进行中） |

## 2. 主要运行链路（与 spec 14.1.4 映射）

| Spec 要求的链路 | Eta 代码路径 |
|---|---|
| Agent Runtime / Loop | `agent/runtime/AgentRuntimeService.kt`、`AgentRuntimeRunExecutor.kt`、`AgentRuntimePolicy.kt` |
| 模型 Provider | `agent/model/AgentModelClient.kt`、Provider/BYOK 配置（Android Keystore 加密，见 docs/AGENT_RUNTIME.md） |
| Tool Registry | `agent/model/AgentToolCatalog.kt` + 各域 Catalog（AgentVirtualDisplayToolCatalog、AgentContextAppToolCatalog、AgentFileToolCatalog、AgentTerminalToolCatalog…）+ `agent/tool/AgentLocalTools.kt` |
| 会话/消息 | `data/`：eta.db（conversation_messages / conversation_state / conversation_context_checkpoints / runtime_archive_events，Room） |
| GUI Agent | Accessibility（`AgentAccessibilityService.kt`）+ 按需截图 + 工具注入；**新增**：`agent/phone/display/`（虚拟屏，见专项审计） |
| Terminal | `agent/terminal/`：ShellProcessSupervisor（独立 mnt namespace 会话）、BackgroundTerminalJobs/DetachedTaskSupervisor（后台任务）、Debian/Alpine 安装器、**RootBindMounts/SharedFolderMounts（共享挂载）** |
| File Tool | `agent/terminal/FileTool*.kt`（Reader/Glob/Search/Dispatcher）+ LinuxFileExplorer |
| Memory | `MEMORY.md`（files/）+ memory_write/memory_get 工具 |
| Skills / Subagent | `files/skills/`（self-improving-agent、skill-creator、skill-installer）+ subagent 工具 |
| Voice / 系统助手 | `agent/voice/`（EtaVoicePanel、EtaRecognitionService、SpeechInputController）+ VoiceInteractionService 入口 |
| Root 通道 | `su -c` 一次性 shell（BoundedRootCommandExecutor）+ **PhoneShellHelper root app_process（Binder）** |
| 通知 | NotificationListener（历史 7 天/1000 条）+ 各前台服务通知 |
| 虚拟屏 | `agent/phone/display/`（见 VIRTUAL_DISPLAY_AUDIT.md） |

## 3. 设备环境（DEVICE_COMPATIBILITY 摘要）

| 项 | 实测值 |
|---|---|
| 设备 | 小米（peach_v2），HyperOS（版本串 3.0.303 出现于 helper 日志语境），Android 16 |
| 分辨率/密度 | 1200×2608 物理=逻辑，480dpi，f2fs userdata（dm-46） |
| Root | KernelSU（ksu_driver_su，busybox /data/adb/ksu/bin/busybox） |
| LSPosed | 入口适配存在（hook/system），本会话未验证 LSPosed 激活状态 |
| 多轮真机验证 | 虚拟屏全链路、悬浮球、迁移/驻留/回迁、mount FUSE 证据、熄屏运行、FLAG_SECURE 行为 |

## 4. 可复用功能（spec 2.2.1 禁止重写清单核对）

全部确认存在且可用：Agent Loop ✓、Provider ✓、Tool Calling ✓、GUI Accessibility ✓、Terminal ✓、File Tool ✓、Memory ✓、Skills/MCP ✓、Voice ✓、通知 ✓。**spec 2.2.1 的"禁止重复建设"约束成立**——第一轮不应重写以上任何模块。

## 5. 风险与注释

1. **根会话授权波动**：本会话后期出现 root 授权临时失效（`check_root` 后恢复）——长会话依赖 root 的功能需有授权重试。
2. **工作区 11 文件未提交**：v2026103105-3109 的全部功能改动在未提交状态——**建议编译验证后立即 commit**。
3. **上游并行实现分叉**：`official-v3.4.0` 分支已保存（见 ETA_FORK_DIFF.md §4）。
4. 构建结果以 `job_ea419686` 日志为准（进行中，完成后补记）。
