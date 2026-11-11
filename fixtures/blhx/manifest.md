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
| formation/ | capC_formation_231829_0.png | formation | 编队出击页，**现有判页全判 other，是要修的 bug** |
| formation/ | capD_check_231854_0.png | formation | 同上 |
| formation/ | capForm1_232104_0.png | formation | 同上 |
| home/ | capZ_now_232313_0.png | home | 主界面（港口） |

## 易混提示

- `list/` 和 `info/` 都有橙色按钮（列表页的下方按钮与详情页「开始演习」外形接近）。判据必须能把两者分开，别只靠颜色。
- `negative/` 两张必须判 other，任何一张被判成 list/info 都算误报。
- `formation/` 三张当前是已知失败样本，修好前判 other 属"现状"，不作为通过标准。
