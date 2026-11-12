# 块 5 · tab3 Bridge 一屏直达 + 状态条搬进 tab3 顶部

先读 `00-总纲-云端版.md`。基线：`feat/ui-redesign` 上块1-4 已合并，
底部 4 tab 已上线，tab1 是会话列表、聊天是二级页。

---

## 1. 本批目标

用户的原话是「**我现在每次杀后台了就要到二级菜单、三级菜单一个一个打开**」。
tab3 要做到：**常用 bridge 能力在一屏里直接够到，不用再钻 Manage**。
同时把底部那条 `RelayStatusStrip` 从全局底部搬到 tab3 页顶部（用户：「丢到 tab3 顶部，或者直接删掉，基本没啥用」→ 搬，不删）。

## 2. 现状（已核实，别重新调研）

- `ui/screens/BridgeScreen.kt` 982 行；`BridgeScreen(viewModel, connectionViewModel, onNavigateToBridgeSafety, onNavigateToChat, onNavigateToManage, onNavigateToSettings, returnTitle, returnSubtitle, returnLabel, onReturn)`。
  内部核心卡片：`BridgeAgentAccessCard` / `BridgeAndroidAccessSummaryCard` / `BridgeSelectedAndroidAccessCard` / `BridgeSafetySummaryCard` / `UnattendedAccessRow` / `TrustedActionsRow` / `OverlayPermissionNagCard`。
- `RelayStatusStrip` 现在在 `AppScaffold.kt` 的 `Scaffold.bottomBar` 里，被包在 `Column { 状态条; NavigationBar }` 的第一层，
  依赖一批局部状态：`appChatRuntimeStatus` / `activeEndpoint` / `activeConnection` / `effectiveDashboardUrl` /
  `connectionViewModel.resolveActiveStreamingEndpoint(...)` / `gatewayAvailability` / `serverCapabilities` /
  `effectiveDisplayProfile` / `gatewayCurrentModel` / `serverModelName` / `supervisedPolicy` / `parentAccessForCurrentRoute`。
- `Screen.Manage` 背后是 `DashboardManagementScreen`（5128 行），Terminal / BotMode 只能从它进 —— 这就是「钻菜单」的根因。

## 3. 必须做

### A. 状态条搬位置

1. 把 `RelayStatusStrip` 从 `AppScaffold` 的 `bottomBar` 里拿掉，`bottomBar` 只留 `NavigationBar`。
2. 在 **tab3（Bridge）页顶部**渲染它，行为和文案与现在完全一致（点一下进 Connections、安全图标、路由/模型/配置名）。
   - 状态条依赖的那批状态允许**抽成一个 composable**（如 `BridgeStatusHeader(...)`）接收必要入参，
     也允许 Bridge 页自己从已有 viewModel 取；**不许改 `RelayStatusStrip` 组件本身的签名与视觉**。
   - 其它页（tab1/tab2/tab4、聊天页）不再显示状态条 —— 这是本批的预期变化，别为此加回。
3. 上传/离线等状态文案要照旧能显示（原来状态条是全局面板，现在只在 tab3 —— 注意别让「上传腿被挡」这类告警只能在 tab3 看到就被忽略；
   如果某个告警原本靠状态条在聊天页提示，在报告里单独列出来，让它留在聊天页或改成 snackbar，**不要静默丢掉**）。

### B. tab3 一屏直达

4. 进 tab3 第一屏就要能直接够到这些（顺序自己按重要性排，但**必须都在首屏或首屏下一点点**）：
   - 截图 / 读屏（机器人控制类）
   - 点按 / 手势类入口
   - Terminal 直达（现在只能从 Manage 进）
   - Bot Mode 直达
   - 权限状态与「未授予」的一键跳转
   - 无人值守 access、信任动作、Bridge 安全摘要（现有卡片）
5. 每个入口补 `testTag`：`bridge.terminal` / `bridge.botmode` / `bridge.screenshot` / `bridge.tap` …
6. 文案走 `strings.xml`，新增 key 只补 `values/` 与 `values-b+zh+Hans/`。
7. **所有现有功能必须仍然可达**：Manage 页、Terminal、BotMode、Bridge 安全设置、权限页、Connections —— 一个都不能因为重排而消失。

## 4. 明确不要做

- ❌ 不改 `BridgeViewModel` / `BridgeScreen` 的业务逻辑，只重排布局与新增直达入口
- ❌ 不改任何 route 字符串；`Screen.Manage` / `Terminal` / `BotMode` 的路由与参数不动
- ❌ 不删 `RelayStatusStrip` 组件、不改它的签名
- ❌ 不动 tab1/tab2/tab4 内部
- ❌ 不加依赖
- ❌ 不做「脚本执行」相关功能（tab2 只展示，执行是以后的事）

## 5. 验收（必须给证据）

1. `./gradlew assembleDebug` 通过，贴最后 15 行。
2. 逐条写「怎么确认」：
   - 状态条只在 tab3 顶部出现，其它 4 个位置（tab1/tab2/tab4/聊天页）没有
   - 状态条上的连接徽章、路由、模型、配置名文案与改动前一致
   - Terminal / BotMode 从 tab3 一步直达，返回键回到 tab3
   - Manage 页仍能从原入口进，里面功能一个不少
   - 权限未授予时，tab3 的权限卡能一键跳到系统设置（与原来行为一致）
   - 首屏能看到哪些入口，逐项列出来
3. 交付说明列出：改动文件、新增 strings key、`testTag` 清单、**首屏可见入口清单**、
   **原本靠状态条在非 tab3 页提示的告警去哪了**（这条单独一节）。

## 6. 提交

一个 commit，commit message 中文：
`feat(ui): tab3 Bridge 一屏直达 + 状态条搬进 tab3 顶部`
