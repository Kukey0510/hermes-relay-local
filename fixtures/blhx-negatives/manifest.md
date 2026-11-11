# 碧蓝「非演习页」负样本（fixtures/blhx-negatives）

来源：2026-11-08~10 真机历史帧，全部 2460×1080 PNG。
用途：**卡判页误报**。这些画面都不该被判成 list / info / formation / home，期望一律 `other`。

**每张的页面类型都用视觉模型逐张复核过**（2026-11-11），不再靠文件名猜。

| 文件 | 期望 | 画面内容 |
|---|---|---|
| tail_probe0_130909_0.png | other | 战斗结算：VICTORY / 大获全胜 S 评级 / 击败所有敌方角色 |
| tail_probe1_130505_0.png | other | 战后「获得道具」弹窗（功勋 ×70） |
| tail_probe2_130516_0.png | other | 结算页：大获全胜 S + 舰队战报 + 胜利台词 + 确定 |
| tail_probe3_132748_0.png | other | 结算页：大获全胜 S + 积分/EXP + 确定 |
| tail_probe6_131403_0.png | other | 结算页：大获全胜 S + 参战舰船 + 胜利台词 |
| settle_tofu_info.png | other | 结算页（与 tail_probe6 同型，不同局） |
| defeat_annihilated.png | other | DEFEAT / 全军覆没 + 战斗评价 |
| defeat_guide_popup.png | other | 战败引导弹窗（提升舰队实力四项） |

## 已确认的实测事实（2026-11-11）

- 旧判据（`[608,400,8,8]≈(75,96,137) tol 8`）在 `tail_probe0_130909_0.png` 上误判为 **list**——这是**已确认的真误报**（画面是 VICTORY 结算页）。
- 其余 7 张旧判据都正确判 `other`。
- **反例警告**：本目录早期版本误收了 `tail_probe3_124653_0.png`、`tail_probe4_132819_0.png`，视觉复核后确认它们其实是**演习列表页**，已移到 `fixtures/blhx-list-extra/`。
  → 教训：`tail_probe*` 是探针批次名，不代表页面类型；**不要用文件名当标签**。

## 硬约束

- 本目录帧与期望值不许改（不二值化、不裁剪、不缩放）。
- 判据必须让这 8 张全部判 `other`，同时 `fixtures/blhx`（12 张）与 `fixtures/blhx-list-extra`（4 张列表页变体）全部判对。
- 不许用「放宽/收紧正样本判定」的办法换通过。
