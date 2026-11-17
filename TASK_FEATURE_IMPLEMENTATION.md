# Eta 核心新特性移植与实现任务 (基于 Antigravity / zcode 对比演进)

## 任务目标
为 Eta (`io.github.mangi.eta`) 引入对标顶级桌面智能体 (Antigravity / zcode / Claude Code) 的核心新特性，包含：
1. **Slash Commands 快捷指令系统** (`/plan`, `/compact`, `/clear`, `/help`)；
2. **`edit_file` Unified Diff 生成与聊天流中的代码增删对比预览卡片**；
3. **`ask_question` 结构化人机协同决策工具与交互式选项卡片 (Human-in-the-Loop)**。

所有实现必须遵循 Eta 现有的代码规范、架构分层（Miuix 视觉风格、AgentHomeAction 单向数据流、Kotlin Multiplatform / Android Compose、Room / AgentState）。

---

## 具体实现要求

### 特性一：Slash Commands 快捷指令系统
1. **触发与展示**：
   - 在 `AgentChatInputBar.kt`（或 `AgentChatBody.kt`）中，当输入框以 `/` 开头时，浮出快捷指令提示浮窗/列表；
   - 支持指令：
     - `/plan <任务>`：规划先行模式，要求 Agent 先行输出详细规划与分步设计；
     - `/compact`：立即手动触发上下文压缩（调用 `AgentHomeAction.CompactContext`）；
     - `/clear`：清空输入框草稿；
     - `/help`：展示可用快捷指令及其用法；
   - 点击浮窗指令可自动补全到输入框。
2. **提交拦截与语义增强**：
   - 在 `onSubmit`（或 `AgentAppRoot.kt` 的 `SubmitMessage` 分发处）：
     - 若输入仅为 `/compact`，直接触发 `onCompactContext()` 并清空输入框，不发起模型无意义打招呼；
     - 若输入以 `/plan ` 开头，自动拼接规划提示词前缀（例如：`[系统规划模式] 在执行任何代码或终端写操作前，请先输出结构化的分步实现方案供用户核对确认：\n...`），使后续模型能够严格先出 Plan；
     - 若输入仅为 `/help`，在界面呈现指令帮助。

---

### 特性二：`edit_file` Unified Diff 生成与卡片展示
1. **Diff 生成** (`AgentFileOperations.kt`)：
   - 在 `editFile(path, oldText, newText, ...)` 执行成功后，根据 `oldText` 与 `newText` 计算统一差异（Unified Diff，如 `@@ ... @@ \n- \n+ `）；
   - 在返回的 JSON 对象中追加 `"diff"` 字段，例如：`"diff": "@@ -15,4 +15,5 @@\n- oldLine\n+ newLine"`。
2. **UI 差异可视化** (`ChatMessageItem.kt` / `AgentToolActivity.kt`)：
   - 当解析到 `edit_file` 的工具结果且包含 `"diff"` 时，在工具卡片折叠/展开区域中专门渲染 Diff 代码块；
   - 使用等宽字体（`EtaMonospace`），红色半透明底色显示删除行（`-` 开头），绿色半透明底色显示新增行（`+` 开头），头部行（`@@`）以辅助主色显示；
   - 支持一键展开/收起完整 Diff。

---

### 特性三：`ask_question` 结构化决策工具 (HITL)
1. **工具声明与注册** (`AgentDeviceToolCatalog.kt` / `AgentStructuredDeviceTools.kt` / `AgentToolCatalog.kt`)：
   - 新增工具 `ask_question`，说明为："向用户提出结构化多选或单选问题以确认设计决策、参数或分支，等待用户选择后继续。"
   - 参数 Schema：
     - `questions`: 数组，每项包含：
       - `question`: String (问题描述)
       - `options`: 字符串数组 (候选选项，不含序号)
       - `is_multi_select`: Boolean (是否支持多选，默认 false)
2. **执行与回执**：
   - 在 `AgentStructuredDeviceTools.kt`（或 `AgentLocalTools.kt`）中实现 `ask_question` 执行器，将提问写入会话工具结果；
3. **UI 交互卡片** (`ChatMessageItem.kt`)：
   - 识别 `tool_name == "ask_question"` 的消息，渲染专门的交互式决策卡片：
     - 标题与问题文本；
     - 单选（Radio）或多选（Checkbox/Chips）选项，用户可直接点击选中；
     - 带有 Miuix 主题配色的「确认并提交」按钮；
     - 点击后将用户的选择格式化为文本（如：`已确认选择：选项A, 选项B`）作为回复提交给 Agent，Agent 从而无缝继续执行。

---

## 验证与验收标准
1. 修改完成后执行 `./build-app.sh assembleRelease`（或 `assembleDebug`）确保编译通过无任何编译错误；
2. 保持代码整洁，无未使用的引用，不破坏现有的终端引擎、语音助手与现有工具。
