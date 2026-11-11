# HANDOFF（接手先看这份）

私有 fork：`Kukey0510/hermes-relay-local`，基线是 `Codename-11/hermes-relay` 1.13.0。
两条线并行，互不覆盖：

| 线 | 立项 | 分支 | 在做什么 |
|---|---|---|---|
| localvision（端上判页） | IDEA-011 `docs/idea-011/` | `main` | App 端本地识别游戏页面，服务器只收结果 |
| UI 改造（4 tab） | IDEA-012 `docs/idea-012/` | `feat/ui-redesign` | 主界面改微信/QQ 形态底部导航，只动 UI |

## 怎么装、怎么跑

```bash
./gradlew assembleDebug          # 出 APK，首次约 15 分钟
./gradlew testDebugUnitTest      # 单测（先 tasks --all | grep -i unitTest 确认 task 名）
```

- 云端环境：JDK17 `/opt/jdk17`、SDK `/opt/android-sdk`，shell 里 `JAVA_HOME`/`ANDROID_HOME` 可能为空，用绝对路径。
- 服务器没有 JDK / SDK / Gradle，**不要在服务器上试编译**。
- 出包走 `.github/workflows/localvision-apk.yml`（`workflow_dispatch` 支持指定 ref），一轮 25~31 分钟；产物是 GitHub Release 附件。
- 覆盖安装必须抬 `versionCode`（`gradle/libs.versions.toml` 的 `appVersionCode`），否则静默跳过、表现为「装了没变化」。

## 目录要点

- `app/src/main/kotlin/com/hermesandroid/relay/ui/` ——本轮唯一改动面（约 10.7 万行）。
- `app/src/main/kotlin/com/hermesandroid/relay/{network,viewmodel,accessibility,bridge,data}/` ——UI 改造一行不碰。
- `docs/idea-012/` ——UI 改造总纲 + 各块任务书。
- `docs/idea-011/` ——localvision 设计、基线、测试清单。

## 已知问题 / 未验证项

- UI 改造刚起第一块，**尚无任何界面变化**。
- `ChatScreen()` 4432 行、`SessionDrawerContent` 59 个实参，搬动时容易出参数错位，只能靠编译 + 真机验。
- 真机验收只能在用户手机上做（sideload debug 覆盖安装，改 UI 不需重配对）。
- 六语言包只补 `values` + `values-b+zh+Hans`，其余回落 en。
