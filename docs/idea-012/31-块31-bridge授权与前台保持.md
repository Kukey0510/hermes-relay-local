# 块31 · Bridge 授权修复 + 前台保持命令（D1 + A5）

> 2026-11-14 立。用户定：**这两条是「下一次出包前必改」**。
> 执行方：Codex Cloud（env `6af3ac3f94e081918f628968ff4ed4d5`，label `hermes-relay-android`）。
> 两份登记原文见 `29-优化项登记-2026-11-14.md` A5、`30-缺陷登记-2026-11-14.md` D1。

## 背景一句话

现在自用包有两个问题：Bridge 页那两个「长期授权」开关一点就闪退（D1）；
远程驱动手机时，Relay 自己会在 12 秒空闲后跳回前台、把游戏挤到后台（A5）。
两件事都落在 `bridge/` 这一层，所以合成一个块做，别拆两次往返。

## 任务 A · 修 D1（闪退 + 显示不符）

**根因（已核实，不用重查）**
- `BridgeCapability.SCREEN_INSPECTION` / `SCREEN_CONTROL` 是 `timed = true`（按次授权）
- 块30 给这两项加了「长期授权」开关，UI 直接调 `setPermanent(...)`
- `data/BridgeCapabilityPolicyRepository.kt:44`：
  `require(!capability.timed) { "Timed capabilities require an expiry" }` → 直接抛 → 未捕获 → 闪退
- 同一原因导致显示不符：Switch 取值只读 `permanentGrants`（写不进去，永远空），
  真正的授权在 `timedExpiriesMs` 里

**要求**
1. policy 里新增独立集合 `longLived: Set<BridgeCapability>`，**不要**塞进 `permanentGrants`，
   绕开 `require(!timed)`
2. `setPermanent(...)` 对 timed 能力走 longLived 分支；对非 timed 能力保持原行为
3. Bridge 页两个 Switch 的取值改成「有效授权」= `permanentGrants ∪ 未过期的 timedExpiriesMs ∪ longLived`；
   master 关闭时 disabled 且不写策略
4. 补单测：timed 能力走 longLived 不抛异常；master 关时开关 disabled；有效授权三来源合并正确
5. **不许**把异常继续往 UI 冒：任何 `require` 失败都改成返回失败结果 + 可读提示

## 任务 B · 加 A5 命令 `/keep_foreground`

**语义**
- `POST /keep_foreground {"seconds": 600}` → App 内部把「12 秒空闲自动回前台」压住到 N 秒后，到期**自动恢复**
- `{"seconds": 0}` → 立刻解除
- 返回 `{"ok": true, "until": <epochMs>, "requestedSeconds": N}`；参数非法返回 400 + 可读 detail

**硬约束**
1. **不接受无限期**：`seconds` 上限 3600，超过就按 3600 处理并在返回里标 `clamped: true`
2. 到期必然恢复（哪怕调用方后来失联），不允许「压住后永不恢复」的路径
3. 走 capability（timed `SCREEN_CONTROL`，或另加一个 `KEEP_FOREGROUND` 能力，二选一自己判断并在报告里说明理由）；
   master 关闭时直接拒绝，返回 403 + `required_action`
4. 路由必须挂在 `BridgeCommandHandler` 的 dispatch 上（跟 `/open_app` `/tap` 同一条路）。
   **不要**走 `/fs/*` 这种私有目录路由 —— 实测那种路由到不了 `BridgeRunTracker`，等于没用
5. 压住期间不得触发 `/return_to_hermes`；`until` 到期后如果前台仍不是 Relay，**不要**立刻补跳，
   等下一次正常 idle 周期自然发生
6. 命令本身要重置 idle 计时器（跟其他非 suppressed 命令一致），且要写进 Activity 日志的 pending 表

**实现建议（可选，自己判断）**
- `BridgeRunTracker` 加一个 `suppressUntilMs: Long`，`armIdleTimer()` 里判断「当前时间 < suppressUntilMs 就不起计时器」
- 或加一个 `suppressed: Boolean` + 一个定时恢复的 Job；两者都要能被 `/keep_foreground {"seconds":0}` 立刻清掉

## 范围（只改这些）

- `app/src/main/kotlin/com/hermesandroid/relay/bridge/`（BridgeRunTracker、BridgeCommandHandler 新路由）
- `app/src/main/kotlin/com/hermesandroid/relay/data/BridgeCapabilityPolicyRepository.kt`、`BridgeCapability*`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/BridgeScreen.kt`（两个 Switch 取值）
- 对应单测
- **不碰** `accessibility/`、`script/` 的算法、`localvision/`、`assets/pages.json`、`reads.json`

## 验收

- `./gradlew :app:assembleSideloadDebug --no-daemon` BUILD SUCCESSFUL
- `./gradlew :app:testSideloadDebugUnitTest --no-daemon` 不新增失败（已知 flaky 三条与本块无关）
- 新增单测覆盖：D1 三条 / A5 的 0、正常值、超上限、master 关闭
- 报告写清：capability 怎么选的（复用 SCREEN_CONTROL 还是新加）、`suppressUntilMs` 的恢复路径、
  以及「到期后不补跳」这个约束实现落在哪一行

## 硬约束

- 中文 commit，直接提 `main`，不开 PR
- 向后兼容：老版本 App 不认 `/keep_foreground` 时服务器侧会回落到 `/screen` 轮询，所以命令即使不被调用也不能影响现有行为
- 不新增第三方依赖
