# idea-011 r6 接口契约（并行切块用）

> 三个任务并行跑，**各自只许碰自己那批文件**。接线（`BridgeCommandHandler.dispatch` /
> `BridgeCapabilities` / `READ_ONLY_PATHS`）由主控在任务合并后统一做，任务里不要动这几个文件。

## 环境（已就绪，别重建）

- 仓库 `github.com/Kukey0510/hermes-relay-local`，默认分支 `main`，**直接改 main**，不开 PR、不开 feature 分支
- JDK17 `/opt/jdk17`、Android SDK `/opt/android-sdk`（`platforms/android-37.0`，没有裸 `android-37`）
- **任务 shell 里 `JAVA_HOME` / `ANDROID_HOME` 为空**，命令里写绝对路径：
  `JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew ...`
- 依赖缓存已热：`assembleCandidateDebug` 约 1.5 分钟
- 云端沙箱**没有 git remote**，产物由主控用 `codex cloud diff` 取

## 全局硬约束（违反即整轮作废）

- 不许动 `app/src/main/assets/pages.json`（判据/期望值/容差）、`fixtures/`、`assets/templates/`
- 不许改 `ScreenCapture.captureAndUpload()`、超时参数、`MAX_CAPTURE_ATTEMPTS`、`captureMutex` 语义
- 不许动 `plugin/`、不许动别的块的文件（见各块「只许碰」）
- 回答 **≤20 行**，不打印全量日志、不贴大段代码

## 块 A：`/localvision`（服务端直接触发判页 + 判据热更新）

**只许碰**

- `app/src/main/kotlin/com/axiomlabs/hermesrelay/localvision/LocalVisionRunner.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenCapture.kt`
- 新建 `app/src/main/kotlin/com/hermesandroid/relay/network/relay/LocalVisionBridgeRoutes.kt`

**不要碰** `BridgeCommandHandler.kt`、`BridgeCapabilities.kt`、`ConnectionViewModel.kt`、`pages.json`、`fixtures/`。

### A1. `ScreenCapture` 暴露结构化帧信息

新增（放在 `lastFrameDebug` 旁边），保留 `lastFrameDebug` 原有字符串格式不变：

```kotlin
data class LocalFrameInfo(
    val width: Int,
    val height: Int,
    val format: String,      // "RGBA_8888" / "YUV420_888"
    val rowStride: Int,      // 平面 0
    val pixelStride: Int,    // 平面 0
    val samples: List<String>, // "#RRGGBB"，沿用 lastFrameDebug 里现有的采样点
)
var lastFrameInfo: LocalFrameInfo? = null
```

在 `imageToRgbImage()` 里与 `lastFrameDebug` 同一处赋值（不要改像素取法）。

### A2. `LocalVisionRunner` 支持热更新目录

- 构造改成 `LocalVisionRunner(context: Context, hotReloadDir: java.io.File? = null)`
- `pages.json`：先找 `hotReloadDir/pages.json`，存在且能解析就用；解析失败或不存在回落 `assets/pages.json`
- 模板：先找 `hotReloadDir/templates/<name>`，失败回落 `assets/templates/<name>`
- `LocalVisionResult` 加字段 `source: String`（`"hot"` / `"assets"` / `"assets_fallback"`）
- 保留现有 `detect(image)` 签名与 `"other"` 兜底语义；现存的 `LocalVisionRunner(application)` 调用点必须还能编译（默认参数）

### A3. 新文件 `LocalVisionBridgeRoutes.kt`（自足，不依赖 handler 私有成员）

```kotlin
package com.hermesandroid.relay.network.relay

object LocalVisionBridgeRoutes {
    const val PATH: String = "/localvision"
    data class Result(val status: Int, val payload: kotlinx.serialization.json.JsonObject)
    suspend fun handle(
        screenCapture: com.hermesandroid.relay.accessibility.ScreenCapture?,
        appContext: android.content.Context?,
        body: kotlinx.serialization.json.JsonObject,
    ): Result
}
```

行为：

- body：`{"delay_ms": int 0..5000（可选，默认 0）, "with_frame": bool（可选，默认 true）}`
- 有 `delay_ms` 先 `kotlinx.coroutines.delay`
- `screenCapture == null` → `status 503`、`{ok:false, page:"other", error:"capture_not_wired"}`
- `appContext == null` → 照常判页但不做热更新目录（`hotReloadDir = null`），不算错误
- 抓帧失败 → **status 200**、`{ok:false, page:"other", error:"capture_failed", detail:"<≤200 字原因>"}`，不抛异常、不回 500
- 成功 → `status 200`、
  `{ok:true, page:"list|formation|info|home|other", elapsed_ms:<整数>, source:"hot|assets|assets_fallback", frame:{width,height,format,row_stride,pixel_stride,samples:[...]}}`
  （`with_frame=false` 时去掉 `frame` 字段）

### 验收

```bash
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:compileCandidateDebugKotlin
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:testSideloadDebugUnitTest
```

两条都要过。回答里给出：改了哪几个文件、两条命令的实际结果行。

## 块 B：`/fs/*` + `/logcat`（把脚本推到手机、把日志拉回来）

**只许碰**

- 新建 `app/src/main/kotlin/com/hermesandroid/relay/network/relay/FileBridgeRoutes.kt`
- 新建 `app/src/test/kotlin/com/axiomlabs/hermesrelay/network/relay/FileBridgeSandboxTest.kt`

**不要碰** `BridgeCommandHandler.kt`、`BridgeCapabilities.kt`、`ConnectionViewModel.kt`、任何现有文件（`ScreenCapture.kt` 也不行）。

### B1. 接口（主控照这个签名接线，别改名字）

```kotlin
package com.hermesandroid.relay.network.relay

object FileBridgeRoutes {
    val pathMethods: Set<Pair<String, String>> // (method, path) 大写 method
    fun handles(path: String, method: String): Boolean
    data class Result(val status: Int, val payload: kotlinx.serialization.json.JsonObject)
    suspend fun handle(
        appContext: android.content.Context?,
        path: String,
        method: String,
        body: kotlinx.serialization.json.JsonObject,
    ): Result
}
```

`pathMethods` 恰好等于：

```
POST /fs/list, POST /fs/read, POST /fs/write, GET /logcat
```

### B2. 协议

```
POST /fs/list  {"path":"<rel>","recursive":false}
  → 200 {ok, root, resolved, entries:[{name,is_dir,size,mtime}], count}
POST /fs/read  {"path":"<rel>","max_bytes":<int<=1048576>}
  → 200 {ok, root, resolved, size, encoding:"utf8"|"base64", content}
POST /fs/write {"path":"<rel>","content_base64":"<...>","mkdirs":true}
  → 200 {ok, root, resolved, size}
GET  /logcat?lines=<int<=500>
  → 200 {ok, lines:[...], count}
```

- `/logcat` 用 `Runtime.exec("logcat -d -t <n> --pid=<Process.myPid()>")`，失败回 `{ok:false,error:"logcat_unavailable",detail}` + status 200
- `/fs/write` 单文件上限 4MB，超了 `400 {ok:false,error:"too_large"}`
- 所有错误：`status 400` + `{ok:false, error:"path_outside_sandbox"|"too_large"|"not_found"|"bad_request", detail}`，**不许回 500**
- 不实现删除、不实现任意路径读写

### B3. 沙箱（验收重点：这块写错就是安全事故）

- 允许的根**只有三个**：`appContext.filesDir`、`appContext.cacheDir`、`appContext.getExternalFilesDir(null)`
- `appContext == null` → `503 {ok:false,error:"context_unavailable"}`
- 解析：`File(root, rel).getCanonicalFile()`，然后校验
  `canonical.path.startsWith(root.getCanonicalPath() + File.separator)`（或 canonical == root）
- 显式拒绝：`..` 穿越、绝对路径（`File.isAbsolute`）、canonical 之后仍指向根外（含符号链接）、`root` 本身不存在
- list 最多 500 条；`recursive` 默认 false

### B4. 单测（JVM，必须有）

测试类构造临时目录作根，**不依赖 Android Context** → 把沙箱判定抽成一个纯 JVM 函数，例如：

```kotlin
internal object FileBridgeSandbox {
    fun resolve(roots: List<java.io.File>, rel: String): java.io.File? // null = 拒绝
}
```

4 条用例全部必须被拒（`resolve` 返回 null 或 `handle` 回 400）：

1. `../../etc/passwd`
2. 绝对路径 `/sdcard/Hermes/x.txt`
3. 超 1MB 的读（写 1MB+1 字节文件后读）
4. 指向根外的符号链接（`Files.createSymbolicLink`）

### 验收

```bash
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:testSideloadDebugUnitTest
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:compileCandidateDebugKotlin
```

`compileCandidateDebugKotlin` 必须过（新文件还没接线，编译通过即证明无语法/类型错误）。

## 块 C：打包标识（防止覆盖安装假成功）

**只许碰**

- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `.github/workflows/localvision-apk.yml`

**不要碰**任何 Kotlin 源码、`pages.json`、`fixtures/`。

要求：

1. candidate 是 `buildTypes` 里的构建类型（不是 flavor），`applicationIdSuffix` `.candidate` → `.script`（最终包名变 `com.axiomlabs.hermesrelay.sideload.script`）
2. candidate 加 `resValue("string", "app_name", "Hermes Script")`（`sideload` flavor 与 `debug` 构建类型里的名字不要变）
3. `versionCode` 现在是 `libs.versions.appVersionCode.get().toInt()`，改成可被 `providers.gradleProperty("hermes.versionCode")` 覆盖，toml 里的值作基线
4. candidate 的 `versionNameSuffix` 改成 `-script-r<批次号>`，批次号取同一个 `hermes.versionCode` 属性（没传属性时保持原后缀）
5. workflow 里给 gradle 传 `-Phermes.versionCode=$((10000 + github.run_number))`

### 验收

```bash
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:assembleCandidateDebug -Phermes.versionCode=10042
```

必须 `BUILD SUCCESSFUL`，并核对两件事（贴实际输出行，≤3 行）：

- 产物里 `versionCode=10042`、`versionName` 带 `script-r10042`
- APK 路径存在：`app/build/outputs/apk/candidate/debug/*.apk`

## 回答格式（三块通用）

```
改了什么：<文件列表，一行>
验收：<命令 -> 实际结果行>
越界：无 / <说明>
```
