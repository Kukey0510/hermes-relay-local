# 块30 · Bridge 两项开关 + tab1 三组筛选

> 2026-11-14 立。用户口径：A1「像设备和应用信息那样，加『检查屏幕和界面』『控制应用和导航』两项，默认关」，
> A4「tab1 筛选项分三组：第 0 组全部、第一组来源（qq/手机/网页桌面/其他）、第二组标签，点全部清空」。
> 执行方：Codex。基线：`main` 的 `ba63b47`。两块文件不相交，但**一个任务内先后做完**，别拆成两次提交。

## A1 · Bridge 核心加两个长期开关

### 现状（已核）

- `BridgeCapability.SCREEN_INSPECTION` / `SCREEN_CONTROL` 在枚举里都是 `timed = true`（按次授权）
- `BridgeCapabilityPolicy.allows()`（`bridge/BridgeCapabilities.kt:118`）对 timed 能力**只看 `timedExpiriesMs`**，
  所以即使写进 `permanentGrants` 也不生效
- 卡片 `BridgeCoreAccessCard`（`ui/screens/BridgeScreen.kt` 约 850 行起）已有四行：无障碍 / 屏幕共享 / 悬浮窗 / 设备信息开关
- 仓库里已有 `BridgeSafetyManager.setPermanentCapability(connectionId, capability, allowed)`

### 要改

1. `BridgeCapabilityPolicy.allows(capability, nowMs)`：timed 能力改为
   `capability in permanentGrants || (timedExpiriesMs[capability] ?: 0L) > nowMs`。
   **不改** `expiryFor` / `isUnlimited` / 非 timed 分支的语义。
2. `BridgeSafetyManager.authorizeCapability` 的错误码：被拒时，timed 能力若**没有**永久授权仍回
   `bridge_capability_expired`（保持兼容），永久授权被撤的情况回 `bridge_capability_denied`。
3. `BridgeCommandHandler` 里「只有 TIMED 授权才 `rescheduleAutoDisable()`」那段：
   当该能力**存在永久授权**时不要刷新自动禁用计时器（否则永久开关会被按次计时器拖着走）。
4. `BridgeCoreAccessCard` 加两行 Switch，位置放在「设备信息」**上面**：
   - 检查屏幕和界面 → `SCREEN_INSPECTION`
   - 控制应用和导航 → `SCREEN_CONTROL`
   - 文案：新加 4 个 string（标题 + 说明各一条，en 与 zh 都要）；状态文案复用现有
     `bridge_core_allowed` / `bridge_core_not_allowed`
   - testTag：`bridge.core.inspection` / `bridge.core.control`
   - `masterEnabled=false` 时两个 Switch 一起 disabled（和设备信息开关一致）
   - **默认值必须是关**：不给任何默认授权，也不要在启动时自动 setPermanent(true)
5. 打开「控制应用和导航」时，如果 `SCREEN_INSPECTION` 没开，**不要自动一起开**（各自独立，用户自己勾）。

### A1 的测试

- `BridgeCapabilitiesTest`（或新建 `BridgeCapabilityPolicyTest`）：permanent 授权对 timed 能力生效；
  无永久授权且过期仍拒；`isUnlimited` 语义不变。
- UI 层不写截图测试。

## A4 · tab1 三组筛选

### 现状（已核）

- 会话列表在 `ui/components/SessionDrawer.kt::SessionListBody`（约 394 行起，80+ 参数）
- 已有 `organization.tags`（用户标签）、`onSaveSessionTags`、`SessionTagsEditor`、`SessionTagsManager`
- 已有 `source` 字段（`ChatSession.source`：`tui`/`api_server`/`phone`/`discord`…）与 `hiddenSources`（隐藏式过滤）

### 要改

1. 在 `SessionListBody` **内部**加状态（**不要动 80 个参数的签名**）：
   `selectedSources: Set<String>`、`selectedTags: Set<String>`。
2. 列表区域顶部加三行筛选（横滑，样式参考 tab2 的 `ScriptTagFilters`）：
   - 第 1 行：`全部`（testTag `sessions.filters.all`）——点击清空上面两组选择
   - 第 2 行：`来源`（testTag `sessions.filters.sources`），四个固定项：
     `qq` / `手机` / `网页·桌面` / `其他`。映射规则写死在代码里：
     `qq → source == "qqbot"`；`手机 → source == "phone"`；
     `网页·桌面 → source in {"tui","api_server","web","desktop"}`；`其他 → 不属于以上任何一组`
     （含 null）。来源行**只显示实际存在的分组**，一个都没有就整行隐藏。
   - 第 3 行：`标签`（testTag `sessions.filters.tags`），取 `organization.tags`；没有标签就整行隐藏。
3. 过滤语义：来源组内取**或**、标签组内取**或**、两组之间取**与**；两组都空 = 全部。
   过滤在传给 `LazyColumn` 的列表上做，不改 `hiddenSources` 的既有行为（两者叠加）。
4. 现有的 Threads 芯片、`hiddenSources`、置顶分组保持不动，新筛选栏放在它们**下面**。
5. 新建纯函数便于单测（放 `ui/components/SessionFilters.kt`）：
   ```kotlin
   internal enum class SessionSourceGroup { QQ, PHONE, WEB_DESKTOP, OTHER }
   internal fun sourceGroupOf(source: String?): SessionSourceGroup
   internal fun filterSessions(sessions: List<ChatSession>, sources: Set<SessionSourceGroup>, tags: Set<String>, tagsOf: (String) -> List<String>): List<ChatSession>
   ```
   `tagsOf` 传 sessionId → 标签列表，避免把 organization 的类型拖进纯函数。

### A4 的测试

- `sourceGroupOf`：`qqbot`/`phone`/`tui`/`api_server`/`discord`/null 各一例。
- `filterSessions`：只选来源、只选标签、两者都选（与）、都空（全量）、来源多选（或）。

## 共同约束

1. 只许改：`bridge/BridgeCapabilities.kt`、`bridge/BridgeSafetyManager.kt`、
   `network/relay/BridgeCommandHandler.kt`（只动 reschedule 那一小段）、
   `ui/screens/BridgeScreen.kt`、`ui/components/SessionDrawer.kt`、
   新建 `ui/components/SessionFilters.kt`、`res/values/strings.xml`、`res/values-b+zh+Hans/strings.xml`、测试。
2. **不许碰** `accessibility/`、`script/`、`localvision/`、`assets/`、`gradle/`、`app/build.gradle.kts`。
3. 新增中文只许进两份 `strings.xml`；Kotlin 源码里零新增中文字面量（测试数据除外）。
4. 现有任何 route 定义、`BridgeCommandRegistry` 的注册表内容都不许改。
5. 不新增第三方依赖。

## 先落盘再编译

```
JAVA_HOME=/opt/jdk17
ANDROID_HOME=/opt/android-sdk
PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:compileSideloadDebugKotlin
./gradlew :app:testSideloadDebugUnitTest --tests 'com.hermesandroid.relay.bridge.*' --tests 'com.hermesandroid.relay.ui.components.*'
```

已知 3 个与本次无关的 flaky（`ChangelogHistoryScreenshotTest`、`GitStateWriteViewModelTest`、
`ProfileControllerLockTest`）——不要修，也不要算进本块。

## 交付

报告 ≤20 行：改的文件、两个开关各自的授权路径（点开之后哪些路由会放行）、四组来源的映射、
`./gradlew` 尾行，以及没做到的项。**不许**顺手改别的东西。
