# PROGRESS（按轮次追加，不重写历史）

## 轮次 1 · 2026-11-11 · UI 改造立项（IDEA-012）

- 定稿底部 4 tab 结构（对话 / 脚本 / Bridge / 设置），tab2 只展示不执行；状态条挪 tab3 顶部。
- 建 `feat/ui-redesign`（基线 `main@dc3b1470`）并推送。
- 代码体检：`ui/` 106,892 行、42 个 Screen、123 个组件、31 条 NavHost 路由、`testTag` 仅 59 处、无底部导航。
- 落 `docs/idea-012/00-总纲-云端版.md` + `01-块1-P0a-抽状态层.md`，分批表 6 块。
- **未做**：任何代码改动。第 1 块派给 Codex Cloud（环境 `hermes-relay-android`）待回。
- 未验证项：云端 `assembleDebug` 在本分支上的实际耗时未记；单测 task 名待 Codex 报回。
