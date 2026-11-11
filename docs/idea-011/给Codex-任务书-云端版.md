# Codex 任务书（云端版 · 2026-11-11 改）

原 `给Codex-任务书-第一批.md` 是 Windows 版，这里改成 Codex Cloud 版。以本文件为准。

## 你的环境

- 仓库已挂在 `/workspace/hermes-relay-local`，工作目录就是它。
- JDK 17 在 `/opt/jdk17`，Android SDK 在 `/opt/android-sdk`（含 platforms/android-37.0、build-tools、platform-tools）。
- **任务 shell 里 `JAVA_HOME` / `ANDROID_HOME` 可能是空的**，调 sdkmanager / java 时用绝对路径：
  `/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager`、`/opt/jdk17/bin/java`。
- 编译：仓库根目录 `./gradlew assembleDebug`。首次约 15 分钟，正常。
- 出网走容器内代理（`~/.gradle/gradle.properties` 已配好 systemProp），别改代理配置。

## 分支规则（用户 2026-11-11 定）

- **直接提交到 `main`，不要开 feature 分支、不要开 PR。**
- 只有用户和 Codex 两个写手，且不会同时写，顺序改造，main 即工作线。
- 每次提交只做一件事，commit message 用中文，写清「改了什么 / 为什么」。

## 素材

- `fixtures/blhx/`：12 张 2460×1080 真机帧，分 list / info / formation / home / negative。
- `fixtures/blhx/manifest.md`：每张帧的**期望判页结果**，这是验收口径。
- `docs/idea-011/`：方案、基线数据、测试清单。

## 硬约束

1. **不许碰 `plugin/`**（服务端 Python 在线上跑 QQ 通道）。本批只改 `app/` 和新增 `tools/`。
2. 不许升级 Gradle / AGP / compileSdk，不许换 SDK 版本；版本对不上就停下来问。
3. 不许为了"测试通过"修改 fixtures 里的帧或改期望值。
4. **音量零接触**：这是真机阶段的硬规（不许调音量、不许按音量键、不许静音）。你碰不到手机，但写的代码里也不许出现任何音量相关调用。
5. 编译必须通过，测试必须贴原始输出，不许只写"通过"。

## 验收口径

一律给数字：判对率、每帧耗时中位数、P99、误报数。没有数字的"完成"不算完成。
