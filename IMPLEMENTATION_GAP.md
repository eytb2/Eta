# IMPLEMENTATION_GAP.md — Spec V2.0 需求与 Eta 现状差距

> 对照：`eta_rooted_agent_integration_spec_v2.md`（V2.0）↔ 工作区 `eytb2/Eta`（main @ 90e0669 + 11 文件未提交改动 v2026103109）
> 时间：2026-11-01 | 分级：**已实现+已实测** / **已实现（未实测）** / **部分实现** / **未实现** / **冲突**
> 配套文档：`ETA_FORK_DIFF.md`、`VIRTUAL_DISPLAY_AUDIT.md`、`SHARED_MOUNT_AUDIT.md`

---

## A. 状态总表

| Spec 章节 | 需求 | Eta 现状 | 状态 |
|---|---|---|---|
| 4.1 AI 对话多模态 (P0) | 文本/图片/文件/语音/搜索/多模型/会话管理 | Agent Runtime + Provider/BYOK + 图片/语音 + 停止/重试/编辑 | **已实现+已实测** |
| 4.2 Share to Agent (P0) | Sharesheet 接收文本/URL/图片/文件 | 未审计 Manifest（`android.intent.action.SEND` 入口待查） | **待验证** |
| 4.3 全局屏幕理解 (P0) | 悬浮球/Tile 呼出 + 屏幕上下文结构化 | Accessibility 树 + 按需截图 + observe_screen；悬浮球入口为**虚拟屏控制球**（非屏幕理解入口）；缺 ScreenContext 统一结构与敏感 App 规则 | **部分实现** |
| 4.4 通用 UI 自动化 (P0/P1) | 节点查找/点击/输入/滚动/验证/中断 | observe_screen / tap_element / type_text / 手势 / wait；缺执行计划展示、动作上限 | **部分实现** |
| 4.5 Root Broker (P0) | 独立进程、固定 API、UID 校验、审计、dry-run、路径安全 | **PhoneShellService 已是同构实现**（root app_process 独立进程、13-17 固定方法、onTransact UID 校验、shellQuote、白名单） | **部分实现**（缺：结构化审计实体、dry-run、参数 schema、敏感目录拒绝列表） |
| 4.6 系统诊断 (P0) | 电池/性能/存储/网络 + 证据 | device_status / top_memory_apps / top_storage_apps / network_info | **部分实现**（缺采样窗口、BatteryStats 摘要、趋势） |
| 4.7 文件管理 (P0) | 浏览/搜索/大文件/重复检测/删除隔离 | read/write/edit/glob/grep + Linux 文件浏览器 | **部分实现**（缺重复检测、大文件分析、删除隔离目录） |
| 4.8 事件引擎 (P1) | 规范化→过滤→去重→规则→任务 | 通知监听+历史（7 天 1000 条） | **部分实现**（缺规则匹配、限频、暂停入口） |
| 4.9 自动化中心 (P1) | Trigger→Action→Verify | 无 | **未实现** |
| 4.10 LSPosed Adapter SDK (P1) | 声明式 Adapter + 兼容矩阵 | 无（仅 hook/system 骨架 + voice 系统助手入口适配） | **未实现** |
| 4.11 技能中心 (P2) | Skill 包格式/签名/依赖 | skills 已有（self-improving-agent 等） | **部分实现**（缺包签名/依赖声明） |
| **4.12 Virtual Display (P1, Fork 重点)** | 隔离式 GUI 执行环境 | **完整实现并多轮真机验证**：create/start/截图/tap/swipe/key/迁移/驻留/回迁/悬浮球/通知/present_to_user(新) | **已实现**（无损路径与旋转**待实测**） |
| **4.13 Shared Folder Mount (P1, Fork 重点)** | 统一 Mount Manager + 双后端 + 安全边界 | **RootBindMounts + SharedFolderMounts 双后端已实现**（chroot bind + PRoot bind；FUSE 实测）；缺默认只读/symlink 检查/敏感目录列表/重启重挂 | **部分实现** |
| 5.2 模块边界 | Runtime/Registry/Broker/Bridge/Engine 分层 | Runtime ✓；Registry/Broker ≈ 现有结构；Bridge/Engine 缺 | **部分实现** |
| 6. Tool 统一协议 | requestId/status/audit 统一结构 | 工具协议有 ok/error；缺 requestId/auditId/idempotencyKey | **部分实现** |
| 7. 权限与安全 | R0-R4 分级、确认 UI、审计 | 敏感工具脱敏 + 确认（部分）；缺显式分级与确认 UI 规范 | **部分实现** |
| 8. 兼容性 | 环境探测/兼容矩阵/降级 | 探测有（root/helper）；API 33 签名未对齐；兼容矩阵无 | **部分实现** |
| 9. 数据模型 | Task/TaskStep/Capability/PermissionGrant/AuditEvent 实体 | eta.db 有会话/消息/checkpoint；缺 Task/Audit/Capability 实体 | **部分实现** |
| 10.1-10.4 非功能 | 稳定性/耗电/可观测/可测试 | 实践中有（linkToDeath/后台任务/通知）；缺结构化 trace 与诊断导出 | **部分实现** |

---

## B. 冲突项（spec 与现有实现的直接矛盾，需决策）

| # | 冲突 | 建议 |
|---|---|---|
| C1 | spec 0.1-4："**首版不开放任意 Shell 工具**，命令必须走统一 Terminal 执行器" ↔ Eta 已有 `terminal` 任意 Shell 工具（root 可用） | Eta 的 terminal 已具备执行身份/工作目录/超时/取消/后台/审计脱敏——**建议 spec 给 terminal 开例外**（视为"统一 Terminal 执行器"的实现），而非砍掉该能力 |
| C2 | spec 4.13.4"共享挂载默认只读" ↔ fork 全局 ROOT_BIND 为**读写** | 在 SharedFoldersScreen 增加读写开关，默认只读，写授权显式开启 |
| C3 | spec 4.8"默认不长期保存通知正文" ↔ Eta 通知历史 7 天/1000 条 | 增加默认关闭开关与保留期设置 |
| C4 | spec 9 数据模型新增 Task/Audit 实体 ↔ eta.db 现有会话模型 | 以新增表方式落地，不迁移会话模型 |
| C5 | **上游 v3.4.0 删除了 fork 的 Shared Mount（-447 行）并转向 rootless**；两条虚拟屏实现类名冲突 | rebase 策略决策：fork 保留 root bind + `phone/display`；上游 `virtualdisplay` 包作为**第二引擎**按需引入（见 GAP-D） |

## C-D. 虚拟屏双引擎并存（spec 4.12 ↔ 实测结论）

- **引擎 A（fork，已验收）**：task 迁移式——App 迁到 MIUI 原生小窗（原生交互/IME），`present_to_user` 无损交还主屏，watch 自动回迁。
- **引擎 B（上游 v3.4.0，未引入）**：画布驻留式——App 永不离虚拟屏，胶囊/装裱视窗展示+触摸路由，`handoffToMainDisplay` 全屏交还。
- **实测对比**：A 的交互保真（原生 IME/触摸）与 B 的零重建各有优势；B 的"真屏可见性"在本次用户改版中实测**未达成**（真屏无小窗）。
- **建议**：保留 A 为默认；B 的 `moveRootTaskToDisplay` 无损迁移已被 A 吸收（relocateTask）。胶囊取景器 UI 与两档节流作为后续 UI 迭代单独评估。

---

## C. 缺口明细（按优先级）

### P0（阻塞 spec 第一轮验收）

| # | 缺口 | 涉及 | 工作量估计 |
|---|---|---|---|
| G1 | Capability Registry 基础：CapabilityDescriptor 数据结构 + 8 态可用性模型 + 现有工具注册迁移 | 新模块 `agent/capability/` | 中 |
| G2 | Tool 统一协议：requestId/status/audit 结构 + 现有工具响应包裹 | `agent/tool/` 协议层 | 中 |
| G3 | 审计实体与记录：AuditEvent 落库（eta.db 新表）+ 敏感工具已有脱敏对接 | `data/` 层 | 中 |
| G4 | Capabilities 页面：能力状态展示（Native/Root/Shizuku/LSPosed/Accessibility 探测） | `ui/` | 中 |
| G5 | Share to Agent 审计确认（Manifest SEND 入口存在性与 URI 读取路径） | Manifest + `ui/` | 小 |
| G6 | 构建验证：工作区 11 文件改动的 assembleDebug 通过（**进行中**） | CI/本地 | 小 |

### P1

| # | 缺口 | 涉及 |
|---|---|---|
| G7 | Root Broker 收敛：审计结构化、dry-run、路径规范化白名单、敏感目录默认拒绝（对齐 SHARED_MOUNT_AUDIT §7-2/5） | PhoneShellService + Mount |
| G8 | 共享挂载默认只读 + 读写开关 + 重复挂载防护 + 重启重挂提示 | SharedFoldersScreen + RootBindMounts |
| G9 | API 33 createVirtualDisplay 签名对齐（上游 6dcc665） | AgentDisplay |
| G10 | 事件引擎最小版：规范化 + 去重限频 + 暂停入口（通知监听已有） | 新模块 |
| G11 | 屏幕理解 ScreenContext 结构 + 敏感 App 规则 | 屏幕理解模块 |
| G12 | 虚拟屏：无损迁移真机验证 + 旋转测试 + API 33 验证 | 真机 |

### P2

| # | 缺口 |
|---|---|
| G13 | 自动化中心（Trigger→Action→Verify） |
| G14 | LSPosed Adapter SDK 评估（基于现有 hook/system 骨架） |
| G15 | 悬浮球→胶囊取景器 UI 演进（官方审美路线） |
| G16 | ImageReader 两档节流 + 零拷贝 |
| G17 | 诊断包导出与脱敏 |

---

## D. 第一轮已完成 / 进行中

- ✅ 仓库与上游差异审计：`ETA_FORK_DIFF.md`
- ✅ Virtual Display 机制审计：`VIRTUAL_DISPLAY_AUDIT.md`（含多轮真机证据）
- ✅ Shared Mount 机制审计：`SHARED_MOUNT_AUDIT.md`（含 /proc/mounts 实测）
- 🔄 构建尝试：后台进行中（assembleDebug，chroot JDK21+SDK）
- ⏳ `ETA_BASELINE_AUDIT.md` / `DEVICE_COMPATIBILITY.md`：待本轮构建结果后补全
- ⏳ Capability Registry / 统一协议 / Capabilities 页面：待第一轮实施批准后启动
