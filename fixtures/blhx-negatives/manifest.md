# 碧蓝「非演习页」负样本（fixtures/blhx-negatives）

来源：2026-11-08~10 真机采集的历史帧（`tail_probe*` / `defeat_*` / `settle_*`），全部 2460×1080 PNG。
用途：**卡判页误报**。这些画面都不该被判成 list / info / formation / home，期望一律 `other`。

| 文件 | 期望判页结果 | 画面内容 | 为什么是陷阱 |
|---|---|---|---|
| tail_probe0_130909_0.png | other | 战斗结算：击败所有敌方角色 + 评价条 | 底色调与演习列表极近，**现状就会判成 list** |
| tail_probe1_130505_0.png | other | 结算过场（PERFECT/大获全胜） | 同上 |
| tail_probe2_130516_0.png | other | 结算过场：大获全胜 + 积分 | 同上 |
| tail_probe3_124653_0.png | other | 战果结算页（标题区含「演习」字样） | **标题也带「演习」**，且**现状判成 list**；靠标题文本会误判 |
| tail_probe3_132748_0.png | other | 大获全胜 + 获得物品 | 同上 |
| tail_probe4_132819_0.png | other | 战果结算页 | **现状判成 list** |
| tail_probe6_131403_0.png | other | 大获全胜 + 掉落 | 同上 |
| defeat_annihilated.png | other | 全军覆没 + 重试引导 | 有结算弹窗，位置特征易串 |
| defeat_guide_popup.png | other | 战败引导弹窗 | 弹窗覆盖全屏，元素位置会串 |
| settle_tofu_info.png | other | 结算后的物品信息弹窗 | 半透明遮罩，颜色像列表底色 |

## 记录一条已实测的事实（2026-11-11）

旧 `pages.json` 的 list 判据 `[608,400,8,8] ≈ (75,96,137) tol 8`，在本目录 10 张里有 **3 张误判成 list**
（`tail_probe0_130909_0`、`tail_probe3_124653_0`、`tail_probe4_132819_0`，本地按契约语义复算）。
该 8×8 区块在 `capA_list_231751_0.png` 与 `tail_probe3_124653_0.png` 上**逐像素几乎相同**（各通道 std < 1.5）。
即：旧判据抓的是「通用蓝灰底色」，不是列表页特征。

`tail_probe3_*` 还把「演习」两个字写在标题区（模板匹配同样 1.000 命中），所以：
**别只靠单点颜色，也别只靠「演习」标题**，要组合能区分「列表页 vs 结算页」的结构特征。

## 硬约束

- 本目录帧与期望值**不许改**（不做二值化、不裁剪、不缩放）。
- 判据必须让这 10 张全部判 `other`，同时 `fixtures/blhx` 那 12 张保持全对。
- 不许通过放宽 `fixtures/blhx` 里已有的判定标准来「通过」，那是反方向。
