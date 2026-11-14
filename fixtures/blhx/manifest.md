# 碧蓝演习帧样本（fixtures/blhx）

来源：2026-11-10 真机采集，全部 2460×1080 PNG。
用途：端上判页/判数的回归样本。**判据要拿这些帧跑，判错就算不通过。**

| 目录 | 文件 | 期望判页结果 | 说明 |
|---|---|---|---|
| negative/ | chapter_map.png | other | 章节地图，必须不误判成任何演习页 |
| negative/ | list_loading.png | other | 列表加载中，纯色/骨架屏 |
| info/ | capB_info_231813_0.png | info | 演习详情，含橙色「开始演习」 |
| info/ | capB_info_231817_0.png | info | 同上，另一态 |
| info/ | capInfo1_232048_0.png | info | 同上 |
| info/ | capInfo1_232052_0.png | info | 同上 |
| list/ | capA_list_231751_0.png | list | 演习列表，含次数框 + 四张对手卡 |
| list/ | capL_232032_0.png | list | 同上 |
| formation/ | capC_formation_231829_0.png | formation | 编队出击页 |
| formation/ | capD_check_231854_0.png | formation | 同上 |
| formation/ | capForm1_232104_0.png | formation | 同上 |

## 易混提示

- `list/` 和 `info/` 都有橙色按钮（列表页的下方按钮与详情页「开始演习」外形接近）。判据必须能把两者分开，别只靠颜色。
- `negative/` 两张必须判 other，任何一张被判成 list/info 都算误报。
- `formation/` 三张已修复（2026-11-14，判据 `(2048,928)=242,214,178 tol12` 全中），**必须判 formation**。

## 版本注记（2026-11-14）

- 原 `home/capZ_now_232313_0.png` 是 2026-11-10 的**旧主城主题**（顶部色块 248,205,126）。
  当前游戏主城换成换装活动主题（顶部色块 160,50,95），一条色块判据不可能同时覆盖两种主题，
  故该帧移到 `fixtures/blhx-old-theme/`，不再参与判页回归。
- 2026-11-14 新采 24 帧（每页 3 帧）在 `app/src/test/resources/frames/blhx/`，
  由 `BlhxPageClassificationTest.v2FrameMatrixMatchesDirectoryLabels` 覆盖。
- `list` 判据保持原值 `(608,400,8,8)=75,97,138 tol8`：新旧两批帧实测完全一致（Δ0），是跨期最稳的位置。
  `info` 同样保持原值 `(352,240,16,16)=162,165,174 tol12`（新旧帧 Δ≤7）。
