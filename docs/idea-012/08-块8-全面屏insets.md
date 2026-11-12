# 块 8 · 全面屏 insets（状态栏 / 导航条不再被内容侵占）

先读 `00-总纲-云端版.md`（环境、硬约束、出包节奏）。基线：块1-7 已合并，底部 4 tab 已上线并真机跑过。

> 🔴 本块**不出包**（总纲 §6）：验收只认结构自检 + `assembleDebug` 编译通过。

---

## 1. 问题（真机已复现，2026-11-12，用户原话）

> 「全面屏上面会无视状态栏，下面的金刚键也是，有内容在金刚键上了」

- **顶部**：内容顶到状态栏下面，状态栏的时间/电量图标压在内容上
- **底部**：内容压到三键导航（金刚键）或手势条区域，最底下几行被挡住

用户明确说过这是**原版就有**的问题，不是这轮 UI 改造引入的。修它是本轮最后一块。

## 2. 根因（已定位，不要重新调研）

1. `app/build.gradle.kts` 里 `targetSdk = 36` → Android 15+ 对 targetSdk≥35 的 App **强制 edge-to-edge**，`setDecorFitsSystemWindows(true)` 被系统忽略。
2. `app/src/main/kotlin/com/hermesandroid/relay/ui/AppScaffold.kt:1654` 写着 **`contentWindowInsets = WindowInsets(0)`** —— Scaffold 完全不收窗口 inset，`innerPadding` 里没有状态栏/导航条高度。
3. 顶部是靠**零散组件各自补 padding** 兜的，不是全局兜底，且带互斥条件（见 `AppScaffold.kt:1593~1607`）：
   `UpdateBanner.kt:63`、`MessageBanner.kt:182`、`DemoModeBanner.kt:62`、`ConnectionHandoffBanner.kt:170`、`UpdateAvailableBanner.kt:352`、`UnattendedGlobalBanner.kt:95`、`HostResourcePressureBanner.kt:50`、`WhatsNewToast.kt:135`、`AppScaffold.kt:1605`、`AppScaffold.kt:3501` —— 全部是 `Modifier.windowInsetsPadding(WindowInsets.statusBars)`。
4. 底部**没有任何全局兜底**，只有局部零散处理：`ConnectionInfoSheet.kt` 5 处 `navigationBarsPadding()`、`BridgeAccessCards.kt:266/345`。主内容区（`Scaffold` 的 content lambda 里那个 `Column`）什么底部 inset 都没吃。
5. 佐证：`UnattendedGlobalBanner.kt:88` 的注释自己写着「app uses edge-to-edge mode (`contentWindowInsets = WindowInsets(0)`)」—— 这是历史遗留假设，现在必须推翻。

## 3. 必须做

### A. 给 Scaffold 加全局兜底

1. `AppScaffold.kt:1654` 的 `contentWindowInsets = WindowInsets(0)` 改成 `WindowInsets.safeDrawing`。
   `safeDrawing` = 状态栏 + 导航条 + 刘海 + IME 的并集，能同时盖住顶部和底部两个问题。
2. 如果 `safeDrawing` 里的 IME 部分导致聊天页键盘弹出时布局跳动，改用
   `WindowInsets.systemBars`（不含 IME），然后在输入框那一层单独加 `imePadding()`。**这属于实现选择，你编译后自己判断，并在交付说明里写清选了哪个、为什么。**

### B. 消除双 padding（这是本块最容易翻车的地方）

3. A 步之后，原来那 10 处 `windowInsetsPadding(WindowInsets.statusBars)` 会和 Scaffold 的兜底叠加，**造成顶部双倍留白**。必须逐个处理，二选一：
   - 删掉该组件的局部 `statusBars` padding（因为 Scaffold 已经兜了），或
   - 在 Scaffold 兜底处 `consumeWindowInsets(WindowInsets.statusBars)` 让下游收不到
   **每处都要在交付说明里列出你选了哪种处理，不许一句「统一处理了」带过。**
4. 底部同理：`bottomBar` 里的 `NavigationBar` 自带 `navigationBars` inset。Scaffold 兜底后要么给它 `Modifier.consumeWindowInsets(WindowInsets.navigationBars)`，要么确认它不再重复加。**必须实测（截图或布局数值）确认 tab 栏没有被抬高两次。**

### C. 聊天页输入框

5. 聊天页键盘弹出时，输入框必须完整可见、不被 IME 遮住。改完请明确写：输入框底边到屏幕底边的距离，键盘弹出/收起两种状态各是多少。

### D. 各页面自查

6. 逐个确认（改完自己在交付说明里列表）：对话列表 / 聊天页 / 脚本列表 / 脚本详情 / Bridge / 设置 / 各二级页（含 Terminal、Bot Mode、Manage）—— 顶部第一行内容是否完整可见、底部最后一行是否被导航条挡住。

## 4. 明确不要做

- ❌ 不改 `targetSdk`、不动 `compileSdk`
- ❌ 不改任何 route 字符串、不新增 route
- ❌ 不动 `network/`、`accessibility/`、`bridge/`、`data/`、`viewmodel/` 内部逻辑
- ❌ 不为了「看起来不漏」而把内容整体缩小或加死 padding（写死 dp 值是在埋下一个机型的坑）
- ❌ 不改业务行为，只改布局 inset 处理
- ❌ 不动 tab1/tab2/tab3/tab4 的功能结构

## 5. 验收（必须给证据）

1. `JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug` 通过，贴最后 15 行（两个 flavor 都要过）。
2. 交付说明里逐条写清：
   - `safeDrawing` 还是 `systemBars`，为什么
   - 10 处局部 `statusBars` padding 各自怎么处理的（删掉 / consume / 保留及原因）
   - `NavigationBar` 是否双 padding，怎么确认的
   - 聊天页输入框在键盘弹出/收起时距屏幕底边的距离
   - 各页面顶部第一行、底部最后一行是否完整可见
3. 列出改动文件清单。

## 6. 提交

一个 commit，commit message 中文：
`fix(ui): 全面屏 insets 兜底，修复内容压状态栏与导航条`
