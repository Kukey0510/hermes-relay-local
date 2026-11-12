# 块 9 · 摘除 googlePlay flavor（自用，只留 sideload）

先读 `00-总纲-云端版.md`。基线：块1-8 已合并。

> 背景（用户 2026-11-12 定）：这个分支**自用**，不上架 Google Play，也不需要 Play 版。
> Play 版留着只会让每次 `assembleDebug` 多编一个 flavor、多花一半时间，还要维护一堆分叉逻辑。
> 本块把它从构建里摘掉，**只保留 sideload 一条线**。

> 🔴 本块**不出包**。验收 = `assembleDebug` 通过 + 产物只剩 sideload。

---

## 1. 现状（已核实，别重新调研）

| 位置 | 内容 |
|---|---|
| `app/build.gradle.kts:63` | `flavorDimensions += "track"` |
| `app/build.gradle.kts:130-133` | `create("googlePlay") { dimension = "track" }` |
| `app/build.gradle.kts:134-138` | `create("sideload") { ... applicationIdSuffix = ".sideload" }` |
| `app/build.gradle.kts:149-153` | `playConfigs { register("sideload") { enabled.set(false) } }`（防止误发 Play） |
| `app/build.gradle.kts:315-320` | `play { serviceAccountCredentials... }`（gradle-play-publisher 配置） |
| `app/build.gradle.kts:393-394` | `"googlePlayImplementation"(libs.play.app.update)` 及 `-ktx` |
| `app/src/googlePlay/` | `kotlin/` 三个实现类（VoiceBridgeIntentFactory / VoiceBridgeIntentHandlerImpl / UpdateAvailabilityFactory）、`AndroidManifest.xml`、`play/` 上架元数据与截图 |
| `app/src/main/kotlin/.../data/FeatureFlags.kt:110` | `object BuildFlavor`，用 `BuildConfig.FLAVOR` 判断 |
| 测试 | 3 处只是字符串 `"googlePlay"` 字面量，无编译依赖 |

**关键事实**：`app/src/sideload/kotlin/` 里已有完整对应实现（`VoiceBridgeIntentHandlerImpl.kt`、`VoiceIntentClassifier.kt`、`VoiceBridgeIntentFactory.kt`、`UpdateAvailabilityFactory.kt`），删掉 googlePlay 源集**不会缺实现**。

## 2. 必须做

1. **删 flavor**：`app/build.gradle.kts` 里删掉 `create("googlePlay") { ... }` 整块。
   `flavorDimensions` 与 `create("sideload")` 保留（sideload 仍是唯一 flavor）。
2. **删 playConfigs**：`playConfigs { register("sideload") { enabled.set(false) } }` 整块删除（没有任何 Play 发布路径了）。
3. **删 Play 发布配置**：`play { ... }` 整块删除。如果 `com.github.triplet.play` 插件仍被 apply 且删配置后构建报错，就把插件 apply 一并去掉；反之若留着不报错，可以保留但要在交付说明里写清为什么。
4. **删依赖**：`"googlePlayImplementation"(libs.play.app.update)` 与 `"googlePlayImplementation"(libs.play.app.update.ktx)` 两行删除。
   `gradle/libs.versions.toml` 里对应的 `play-app-update` 版本与库声明，若确认无其他引用，一并删除。
5. **删源集**：整个 `app/src/googlePlay/` 目录删除（含 `kotlin/`、`AndroidManifest.xml`、`play/` 上架元数据）。
6. **`BuildFlavor` 不动**：`FeatureFlags.kt` 里的 `GOOGLE_PLAY` 常量与相关分支保留即可 —— flavor 删掉后 `BuildConfig.FLAVOR` 恒为 `"sideload"`，那些分支会成为死代码，R8 会折掉，**不要为了清理去改 65 处调用点**（本块风险要小）。
   如果你发现某个 `isGooglePlay` 分支在删除 flavor 后**导致编译错误**（例如引用了已删的源集类），才做最小必要修改，并在交付说明里列出。

## 3. 明确不要做

- ❌ 不改 `app/src/sideload/` 下任何文件（那是唯一在用的实现）
- ❌ 不删 `app/src/main/` 里的 `VoiceBridgeIntentHandler` 接口（sideload 实现依赖它）
- ❌ 不改任何 UI 代码、不动 route、不动业务逻辑
- ❌ 不动 `targetSdk` / `compileSdk` / `minSdk`
- ❌ 不删测试里的 `"googlePlay"` 字符串字面量（那是测试数据，改了反而多余）
- ❌ 不顺手重构别的模块

## 4. 验收（必须给证据）

1. `JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug` 通过，贴最后 15 行。
2. 贴 `./gradlew tasks --all | grep -i assemble` 里与 APK 相关的 task 列表，**证明已经没有 GooglePlay 变体**。
3. 贴产物列表：`find app/build/outputs/apk -name '*.apk'`，应只剩 `sideload-debug` 与 `sideload-candidate` 两个目录。
4. 交付说明里列出：删了哪些文件/目录（数量 + 路径）、改了 build.gradle.kts 的哪几处、是否动了插件 apply、`libs.versions.toml` 删了哪些条目。

## 5. 提交

一个 commit，commit message 中文：
`chore(build): 摘除 googlePlay flavor，只保留 sideload（自用分支不上架 Play）`
