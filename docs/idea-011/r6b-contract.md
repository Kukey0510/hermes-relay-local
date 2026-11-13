# idea-011 r6b 接口契约（第二波并行切块）

> 与 r6 同样的规矩：**一个块只碰自己的文件**，接线（`BridgeCommandHandler.dispatch` / `BridgeCapabilities`）由主控合并后统一做。
> 环境与硬约束沿袭 `docs/idea-011/r6-contract.md` 顶部，不再重复。

## 环境速查

- 仓库 `Kukey0510/hermes-relay-local`，分支 `main`，**直接改 main，不开 PR、不开 feature 分支**
- `JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk`，任务 shell 里变量为空，命令写绝对路径
- **真实 gradle 任务名**（别写错）：`:app:assembleSideloadCandidate`、`:app:compileSideloadCandidateKotlin`、`:app:compileSideloadDebugUnitTestKotlin`、`:app:testSideloadDebugUnitTest --tests "*Xxx*"`
- 整套单测有 98 个既有失败（Robolectric 网络），**只跑自己新增的测试类**

## 全局硬约束

- 不动 `app/src/main/assets/pages.json`、`fixtures/`、`assets/templates/`、`plugin/`、`docs/`
- 不改 `ScreenCapture.captureAndUpload()`、超时参数、`MAX_CAPTURE_ATTEMPTS`
- 不改 `BridgeCommandHandler.kt` / `BridgeCapabilities.kt` / `ConnectionViewModel.kt`（接线主控做）
- 回答 ≤20 行，不贴全量日志、不贴大段代码

---

## 块 E：帧存档（失败存帧，供服务器 `/fs/read` 取）

**只许碰**

- 新建 `app/src/main/kotlin/com/axiomlabs/hermesrelay/localvision/FrameArchive.kt`
- 改 `app/src/main/kotlin/com/hermesandroid/relay/network/relay/LocalVisionBridgeRoutes.kt`（只在失败/要求存帧的路径插入调用，不改返回结构）

**接口（主控按这个接，别改名字）**

```kotlin
object FrameArchive {
    const val DIR_NAME = "frames"
    data class Entry(val file: java.io.File, val tag: String, val savedAtMs: Long)
    /** 把一帧存成 PNG，环形保留最近 MAX_FILES 个，总量超 MAX_BYTES 从最旧删。失败返回 null，不抛。 */
    fun save(context: android.content.Context?, image: RgbImage, tag: String): Entry?
    fun list(context: android.content.Context?): List<Entry>
}
```

- 目录 `<filesDir>/frames/`，文件名 `yyyyMMdd_HHmmss_SSS_<tag>.png`
- `MAX_FILES = 12`、`MAX_BYTES = 20MB`，超出按 `lastModified` 从旧到新删
- 存 PNG 用 `Bitmap.createBitmap(rgb, w, h, ARGB_8888)` + `compress(PNG, 100)`；**必须 `recycle()`**
- 并发安全：用 `synchronized` 或 `Mutex` 包住"写入+裁剪"，别两个协程同时写

**`/localvision` 的行为改动（只这三条）**

1. 抓帧失败（`capture_failed`）时**没有帧**，不存
2. 判页成功但 body 里 `save_frame: true` → 存一帧，tag = `manual`
3. 判页成功且 `page == "other"` 且 body 里 `save_unknown: true`（默认 **true**）→ 存一帧，tag = `unknown`
4. 返回体新增一个字段（可选，不破坏现有字段）：`"frame_saved": "<文件名或省略>"`

## 块 F：次数读数骨架（模板法，素材明天补）

**只许碰**

- 新建 `app/src/main/kotlin/com/axiomlabs/hermesrelay/localvision/DigestReader.kt`
- 新建 `app/src/main/assets/reads.json`
- 新建 `app/src/main/assets/digits/README.md`（占位说明，别加图片）
- 新建 `app/src/test/kotlin/com/axiomlabs/hermesrelay/localvision/DigestReaderTest.kt`

**接口**

```kotlin
class DigestReader(context: android.content.Context, hotReloadDir: java.io.File? = null) {
    data class ReadResult(val field: String, val value: Int?, val source: String, val elapsedMs: Double)
    /** 读不到（无该字段/无模板/置信不足）→ value = null，不抛异常。 */
    fun read(image: RgbImage, field: String): ReadResult
    fun fields(): List<String>
}
```

`assets/reads.json` 结构（`digits/` 下对应字段的模板图先空着，代码要能容忍缺失）：

```json
{
  "fields": [
    {
      "name": "jjc_times",
      "region": [x, y, w, h],
      "layout": "left_to_right",
      "charset": ["0","1","2","3","4","5","6","7","8","9","/"],
      "cell_width": 24,
      "cell_height": 32,
      "threshold": 0.90
    }
  ]
}
```

- 读数算法：把 `region` 裁出来 → 灰度（复用 `Grayscale.fromRgb`）→ 按 `cell_width` 等分格子（末格不足宽度就丢）→ 每格与 `digits/<field>/<char>.png` 灰度模板逐格匹配（复用 `TemplateMatcher` 的评分公式，别另写一套）→ 每格取最高分，低于 `threshold` 视为该位不可信
- 有一格不可信 → 整个 `value = null`（`source` 给 `"low_confidence"`；模板缺 → `"no_templates"`；字段不存在 → `"unknown_field"`）
- 模板查找顺序：`<hotReloadDir>/digits/<field>/<char>.png` → `assets/digits/<field>/<char>.png`
- `reads.json` 同理先找 `<hotReloadDir>/reads.json`，解析失败回落 assets
- 解析出的数字串去掉 `/` 后按整数返回（`"2/10"` 取 `/` 前的 `2`；没有 `/` 就整串）

**单测（必须有，纯 JVM，不依赖 Android）**

- 把"格子切分 + 选最高分 + 阈值判定"抽成 `internal object DigestDecoder { fun decode(cells: List<GrayImage>, templates: Map<Char, GrayImage>, threshold: Double): Int? }`
- 用例：① 全命中 → 返回数字 ② 有一个格子低于阈值 → null ③ 模板缺字符 → null ④ 带 `/` 的 `2/10` → 返回 2

**验收（两块通用）**

```bash
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:assembleSideloadCandidate
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:compileSideloadDebugUnitTestKotlin
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:testSideloadDebugUnitTest --tests "*DigestReaderTest*"
```

## 回答格式

```
改了什么：<文件列表，一行>
验收：<命令 -> 实际结果行>
越界：无 / <说明>
```
