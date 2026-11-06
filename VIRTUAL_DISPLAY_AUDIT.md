# VIRTUAL_DISPLAY_AUDIT.md — Fork 虚拟屏实现机制审计

> 对象：`eytb2/Eta` 工作区 `app/src/main/kotlin/io/github/mangi/eta/agent/phone/display/`（10 文件）
> 审计时间：2026-11-01 | 多轮真机验证：小米（HyperOS，peach_v2，Android 16，KernelSU，1200×2608，f2fs）
> 证据等级：**[代码]** / **[实测]**（本会话多轮真机）/ **[推断]**（需后续验证）

---

## 1. 架构与调用链

```text
Agent 工具 (virtual_display_*)
   → VirtualDisplayTools（工具执行器，JSON 协议）
      → VirtualDisplayController（App 进程单例，状态机：display / migrated / presented）
         → PhoneShellHub（Binder 连接）
            → PhoneShellHelper（root app_process 独立进程，su -c CLASSPATH=base.apk app_process 拉起，setsid 存活）
               → PhoneShellService（IPhoneShell.Stub，13~17 方法）
                  ├─ DisplayManager.createVirtualDisplay（反射构造 DisplayManager，flags 见 §2）
                  ├─ ImageReader（RGBA_8888，maxImages 2）→ latest frame → PNG
                  ├─ InputManagerGlobal.injectInputEvent（SOURCE_TOUCHSCREEN，displayId 注入）
                  ├─ IActivityTaskManager：startActivityAsUser / removeTask / moveRootTaskToDisplay /
                  │   setTaskWindowingMode / resizeTask / getTasks（全反射）
                  └─ cmd appops（音频焦点）/ cmd input / am（fallback）
```

**生命周期挂钩**：Eta App 死亡 → helper `linkToDeath` 撤屏；helper 孤儿存活（setsid）。
**UI 层**：`VirtualDisplayBubble` 悬浮球（TYPE_APPLICATION_OVERLAY）、`VirtualDisplayNotifier` 常驻通知（迁移/释放 action）、`VirtualDisplayActivity`（旧镜像接管窗，v3105 起仅保留未引用）。

## 2. 显示创建

- 入口：`virtual_display_create(width, height, dpi)`，范围钳制（320-2560 / 120-640），已存在时复用。
- flags（[代码] `displayFlags()`）：`PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL` + API 33+ `TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED` + API 34+ `OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED`。
- **实测**：多轮创建（display 2→23），尺寸 720×1600@320 与 1128×1616@500 均成功；`OWN_FOCUS`+`STEAL_TOP_FOCUS_DISABLED` 组合未抢占主屏焦点。
- **熄屏运行**：**[实测]** 多次息屏后虚拟屏任务继续运行（`ALWAYS_UNLOCKED` 生效）。
- **旋转**：`ROTATES_WITH_CONTENT` 已设，**旋转行为未实测**（spec 14.2 要求项，[待验证]）。
- **IME**：`setDisplayImePolicy(LOCAL)`——输入法弹在虚拟屏（[代码]，未单独实测）。
- **API 33 签名**：上游 6dcc665 修复了 API 33 `createVirtualDisplay` 重载差异；fork 未显式处理（**[待验证]** 当前设备 API 36 实测正常，API 33 设备风险未知）。

## 3. 截图与视觉

- 通道：ImageReader → `latestFrame` StateFlow → `latestFramePng()` → app cache（保留 8 张）。
- **实测确认**：Agent 经 `virtual_display_screenshot` + `read_image` 获得清晰画面（多轮：计算器、微信列表、freeform 窗口）。
- **已知限制**（[实测]）：
  1. 迁移驻留期间取帧通道冻结（`NO_FRAME`，"画面被接管小窗占用"）——工作区新代码改为**无损迁移不释放 display**，此问题路径已改变（待复验）。
  2. 曾出现"接管初期返回旧帧"竞态——已通过"驻留期不依赖虚拟屏截图、改用真屏 screencap"规避。
  3. Eta 主界面带 FLAG_SECURE：前台时 `screencap` 全黑（[实测] u4/u5.png），与虚拟屏无关但影响真屏观察手段。
- **标题栏重影伪影**：虚拟屏截图中标题栏偶发双行渲染（[实测] 观察到 2 次，不影响真实显示，疑 ImageReader 捕获时序）。

## 4. 输入注入

- tap/swipe/key：helper `injectInputEvent`（displayId 精确注入）→ fallback `input -d N`。
- stroke：最长 1024 点 / 10s，逐点按时刻注入（`events[0]=DOWN`…末点 `UP`），支持手写级轨迹。
- **实测确认**：计算器 `7×8=56` 序列、微信列表点击、freeform 窗口交互均正常。
- **并发保护**（工作区新增）：`activeInjections` 原子计数；`migratePackage` 前等待归零（≤11s）——防手势序列被迁移切断（K3 坑①）。

## 5. 迁移链路（Fork 核心差异点）

**v2026103109 状态（工作区）**：

```text
无损路径（默认）：
  relocateTask(pkg, display 0, freeform, bounds)
    = setTaskWindowingMode(task, 5) + moveRootTaskToDisplay(task, 0) + resizeTask(bounds)
    → Task 跨屏搬运，Activity 不重建，页面状态保留；虚拟屏保留存活（随时迁回）
回退路径（系统拒绝时）：
  migrateDisplayAppToSmallWindow = removeTask（虚拟屏内）+ startSmallWindow
    （MIUI options 降级链：MiuiFlag+bounds → bounds → bare freeform）
    → Activity 重建（页面丢失，进程/登录态保留）
回虚拟屏：
  a. 无损：relocateTask(pkg, vdisplayId, freeform, bounds)（小窗未销毁时）
  b. 重建：小窗被用户 X 掉（task 已销毁）→ create 重建虚拟屏 + startApp（Activity 重建）
present_to_user：
  relocateTask(pkg, display 0, fullscreen, no-bounds) → 用户全屏操作
    → returnToVirtualDisplay() 无损收回
```

- **实测**（v3107，销毁式路径）：迁移/关小窗回迁/驻留/收尾全链路 ✓；Activity 重建实测 = 微信回会话列表（页面丢失，登录态保留）。
- **未实测**（v3109 无损路径，[待验证]）：`moveRootTaskToDisplay` 后微信停留在原页面、`present_to_user` 往返、`resizeTask` 生效性。
- **音频焦点**：`cmd appops TAKE_AUDIO_FOCUS ignore`（[代码]；未实测——虚拟屏收语音场景）。

## 6. 状态机（v2026103109 工作区）

```text
IDLE → (create) → RUNNING(显示存活)
RUNNING → (无损迁移成功) → MIGRATED(小窗驻留；display 保留存活；watch 协程 2s 监听)
MIGRATED → (用户关小窗 & 进程活) → RESTORING → create+startApp → RUNNING
MIGRATED → (用户关小窗 & 进程死，连续 2 次确认) → 清理（球+通知+虚拟屏释放）
MIGRATED → (present_to_user) → PRESENTED(主屏全屏) ⇄ (returnToVirtualDisplay) → MIGRATED
任意态 → (release) → IDLE（watch 取消、状态清空、球/通知清理）
```

状态字段：`_display` / `migratedPackage` / `migratedScreen` / `presentedPackage` / `activeInjections`（原子）/ `watchJob`。
**已修复的竞态**：release 先 cancel watch + 清态再释放；migratePackage 状态变更进锁；重建后二次校验防"释放后复活"。

## 7. 工具面

`virtual_display_create / start_app / screenshot / tap / swipe / key / release / status / takeover / present_to_user`（10 个）+ 悬浮球交互（单击迁移/列表/收拢/拖动）+ 通知（迁移到小窗 / 释放）。

## 8. 安全与限制

- FLAG_SECURE / DRM：未绕过（[实测] Eta 自身 FLAG_SECURE 全黑佐证系统保护有效）。
- 主屏保护：`inUseOnMainScreen`（[实测] 主屏使用中的 App 拒绝迁移/启动）。
- MIUI 小窗黑名单：`FREEFORM_BLACK_LIST` + 云控（[代码] 微信不在名单 ✓）。
- root 边界：helper 只接受 Eta uid 的 Binder 调用（onTransact 校验）；命令执行经 `shellQuote`；`input`/`cmd` 白名单。

## 9. 已知问题与未验证项

| # | 项 | 等级 | 说明 |
|---|---|---|---|
| 1 | 无损迁移路径未编译验证 | 高 | `relocateTask`/`presentToUser`/注入互斥为工作区新代码（v3109），待编译 |
| 2 | 旋转行为 | 中 | spec 14.2 要求项，未实测 |
| 3 | API 33 createVirtualDisplay 签名 | 中 | 上游已修（6dcc665），fork 未对齐 |
| 4 | 首次点击球偶发不响应 | 低 | view 刷新与触摸重挂竞态，已改"刷新不重建"，复现率下降，根因未完全定位 |
| 5 | 虚拟屏截图标题栏重影 | 低 | [实测] 观察到 2 次，疑捕获时序，不影响真实显示 |
| 6 | 标题栏双行渲染 | 低 | 同上 |
| 7 | present_to_user 后的通知/球状态 | 低 | present 态通知仍显示"驻留小窗"（未区分"已交还主屏"） |
