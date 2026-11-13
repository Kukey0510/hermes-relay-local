# IDEA-011 r6d 块 G：次数读数右对齐变长切分

仓库 `Kukey0510/hermes-relay-local`，分支 `main`，直接改当前分支，不开 PR、不开 feature 分支。
基线 = 你 checkout 到的 `main` 头。

**环境**（任务 shell 里 `JAVA_HOME`/`ANDROID_HOME` 为空，命令写绝对路径）
`JAVA_HOME=/opt/jdk17`、`ANDROID_HOME=/opt/android-sdk`

真实 gradle 任务名（别写错，历史上写成 `compileCandidateDebugKotlin` 直接判失败）：

- `:app:compileSideloadCandidateKotlin`
- `:app:assembleSideloadCandidate`
- `:app:testSideloadDebugUnitTest --tests "*DigestReaderTest*"`

整套单测有 98 个既有失败（Robolectric 拉构件被网络白名单挡），**只跑 `--tests "*DigestReaderTest*"`**，
不许跑全量、不许把既有失败当成本次失败。

## 只许碰这两个文件

1. `app/src/main/kotlin/com/axiomlabs/hermesrelay/localvision/DigestReader.kt`
2. `app/src/test/kotlin/com/axiomlabs/hermesrelay/localvision/DigestReaderTest.kt`

**禁止改**（改了就算越界，本轮作废）：
`app/src/main/assets/reads.json`、`assets/digits/**`、`assets/templates/**`、`assets/pages.json`、
`fixtures/**`、`plugin/**`、`docs/**`、`FrameArchive.kt`、`LocalVisionBridgeRoutes.kt`、
`BridgeCommandHandler.kt`、`BridgeCapabilities.kt`、`ScreenCapture.kt`、任何 gradle 文件。

## 背景：读数现在为什么不能用

`DigestReader.read()` 目前只会「按 `cell_width` 从左等分格子」。真机实测演习次数文本是
**右对齐、位数可变**，等分左边会切进空白，切出来的格子对不上字形。

真机实测事实（本条已定，不许改、不许重新标定）：

- 文本区域 `x 2016–2076`、`y 213–245`（宽 60、高 32）
- 字高 24px，**字距（步进）= 12px**，字形宽度不等：`1`=9、`7`=10、`/`=10、`2`≈13、`0`=14
- **右边界恒定**，所以必须右锚定，且相邻字形间距是固定 12 而不是各自宽度
- 出现过的样式：`0/10`、`1/10`、`6/10`、`7/10`、`8/10`、`9/10`、`10/10`
  （`7/10` 是 4 字形 = 48px，`10/10` 是 5 字形 = 60px，左侧留空）

## 要做什么

### 1. `DigestDecoder` 新增右对齐解码（纯 JVM，不碰 Android）

```kotlin
internal object DigestDecoder {
    // 原有，签名与行为一行不改
    fun decode(cells: List<GrayImage>, templates: Map<Char, GrayImage>, threshold: Double): Int?

    /** 右锚定变长切分：strip 已裁到文本区域，从右边界往左逐位匹配。 */
    fun decodeRightAligned(
        strip: GrayImage,
        templates: Map<Char, GrayImage>,
        threshold: Double,
        cellStep: Int,
    ): Int?
}
```

`decodeRightAligned` 算法（照这个写，别自己换思路）：

1. `templates` 为空、`cellStep <= 0`、`strip.width <= 0` → 返回 `null`
2. `cursor = strip.width`，`chars = mutableListOf<Char>()`，循环上限 `strip.width / cellStep + 2` 次防死循环
3. 每轮：对每个模板 `t`（宽 `w = t.width`、高 `h = t.height`），
   取窗口 `[cursor - w, cursor) × [0, h)`（窗口如果 `cursor - w < 0` 或 `h > strip.height` 就跳过该模板），
   用**现有 `TemplateMatcher` 的打分公式**（`TemplateMatcher(t).find(window, minScore = 0.0).score`）打分，
   取最高分那个字符
4. 最高分 `< threshold` → **停止循环**（视为文本左端到了，左边是空白）
5. 否则 `chars += 该字符`，`cursor -= cellStep`
6. 循环结束后 `chars` **反转**得到从左到右的串
7. 后续处理与 `decode` 一致：取 `/` 前的子串，非空且全数字才 `toIntOrNull()`，否则 `null`

约束：

- 窗口高度不匹配、越界一律跳过，不许抛异常
- 不许引入第三方库，不许写新的打分公式（必须复用 `TemplateMatcher`）

### 2. `DigestReader` 支持两种 layout

- `FieldSpec` 增加 `layout: String`（`"left_to_right"` / `"right_to_left"`）与 `cellStep: Int`
- `parseSpecs` 读 `layout` 缺失时默认 `"left_to_right"`；读 `cell_step` 缺失时默认 = `cell_width`；
  **`layout` 只接受这两个值，其它值按解析失败处理（整份 reads.json 回落 assets，不要崩）**
- 原来那句 `require(item.getString("layout") == "left_to_right")` 必须替换掉，否则加了新字段照样抛
- `read()` 按 layout 分支：`left_to_right` 走原有等分格子路径（行为不变）；
  `right_to_left` 裁出 region → `Grayscale.fromRgb` → 调 `decodeRightAligned(..., cellStep)`
- region 裁切越界、高度不足这些原有防御保留，仍然不许抛到外面

⚠️ **不许为了让读数读得出来而放宽 `threshold`、改 `charset`、改 region 默认值**。这些由主控第 4 步标定。

### 3. 单测（`DigestReaderTest.kt`，纯 JVM）

**原有 4 条用例一条不许删、不许改断言**，只许在其后追加：

1. `10/10` 五字形右对齐 → 返回 `10`
2. `7/10` 四字形右对齐（左侧 12px 留空）→ 返回 `7`，且**空白位不许被识别成字符**
3. 中间某一位分数低于阈值 → 返回 `null`
4. `cellStep` 传 0 或负数、模板空表 → 返回 `null` 且不抛异常

测试夹具用 `GrayImage(width, height, ByteArray)` 手工造合成像素，**不许读 PNG、不许读 assets、
不许依赖 Android 类**（这个测试类要能在纯 JVM 下跑）。

## 先落盘再验证

先把两个文件写进磁盘，再跑 gradle。**不要先输出长篇设计方案。**

## 验收（必须真跑，贴实际结果行）

```bash
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:compileSideloadCandidateKotlin
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:assembleSideloadCandidate
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:testSideloadDebugUnitTest --tests "*DigestReaderTest*"
```

## 回答格式（≤20 行，不贴全量日志、不贴大段代码）

```
改了什么：<文件列表，一行>
decodeRightAligned：<一句话说清右锚定的推进方式>
验收：<每条命令 -> 实际结果行，含 test 通过数>
越界：无 / <说明>
```

不要试图 push（云端没有 git remote）。
