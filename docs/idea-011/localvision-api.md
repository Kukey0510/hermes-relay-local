# localvision 接口契约（2026-11-11 定稿）

本文件是并行开发的唯一接口来源。T1（核心实现）和 T2（测试脚手架）都按它写，改动必须双方同步。

包名：`com.axiomlabs.hermesrelay.localvision`（Kotlin，**不依赖任何 Android API**，可在 JVM 单测里直接跑）

## 1. 基础类型

```kotlin
data class RgbImage(val width: Int, val height: Int, val rgb: IntArray)  // 每像素 0xRRGGBB，长度 = width*height
data class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) // 每像素 0..255（Byte 按无符号读），长度 = width*height
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)          // 左上角 + 宽高，越界由调用方保证
data class MatchResult(val found: Boolean, val x: Int, val y: Int, val score: Double) // x/y = 模板左上角；score 0.0~1.0；未找到时 x=y=-1, score=0.0
```

## 2. 灰度化

```kotlin
object Grayscale {
    fun fromRgb(img: RgbImage): GrayImage                     // 0.299R + 0.587G + 0.114B，四舍五入
    fun downsample2x(src: GrayImage): GrayImage               // 2x2 均值；奇数边裁掉最后一列/行
}
```

## 3. 模板匹配

```kotlin
class TemplateMatcher(template: GrayImage) {
    val template: GrayImage
    fun find(hay: GrayImage, area: Rect? = null, minScore: Double = 0.85, downscale: Boolean = false): MatchResult
}
```

- `area` 为 null 表示整图搜索；否则只在区域内搜。
- `downscale=true` 时内部对 hay 降到 0.5 再搜，坐标**必须换算回原图尺度**。
- 评分用归一化互相关（NCC）或等价的归一化 SAD，输出 0~1；**同一实现内评分必须单调可比**。
- **未命中必须返回 `found=false`**，绝不返回"最像的位置"。

## 4. 颜色判据

```kotlin
object ColorCheck {
    fun meanInArea(img: RgbImage, area: Rect): IntArray        // 返回 [r,g,b] 均值，0~255
    fun within(mean: IntArray, expect: IntArray, tol: Int): Boolean  // 三个通道差值都 <= tol 才算通过
}
```

## 5. 页面判据与检测

`pages.json` 结构（放 `app/src/main/assets/pages.json`）：

```json
{
  "pages": {
    "list": {
      "checks": [
        {"type": "color", "area": [x, y, w, h], "expect": [r, g, b], "tol": 25},
        {"type": "template", "file": "list_btn.png", "area": [x, y, w, h], "min": 0.85}
      ]
    }
  }
}
```

- `type` 只认 `color` 和 `template` 两种，其他类型视为该 check 失败（不抛异常）。
- 一个页面的 `checks` **全部通过**才算命中；按 `pages.json` 里的顺序逐个判，命中即返回。
- 都不命中返回字符串 `"other"`。

```kotlin
interface TemplateLoader { fun load(name: String): GrayImage? }   // 返回 null 或抛异常都按"该 check 失败"处理

class PageDetector(spec: PageSpec, loader: TemplateLoader, templateCache: MutableMap<String, GrayImage> = mutableMapOf()) {
    fun detect(img: RgbImage): String        // 返回页面 id 或 "other"
}

object PageSpecParser { fun parse(json: String): PageSpec }   // 解析失败抛 IllegalArgumentException
```

- `PageDetector` 内部要缓存已加载的模板（默认按文件名缓存）。
- `detect()` **不许抛异常**：任何内部错误都退化为 `"other"`。

## 6. 性能预算

单次 `detect()` 在 2460×1080 图上：**P99 < 300ms，中位 < 60ms**（JVM，单线程）。

## 7. 测试脚手架约定（T2）

- PNG 解码只允许出现在 `app/src/test/` 和 `tools/`（可��� `javax.imageio`），**不许进 app 运行时**。
- bench 输出固定 JSON 字段：`{"frame": "...", "expect": "list", "actual": "list", "ms": 12.3}`，加一行汇总 `{"total":12,"correct":12,"median_ms":...,"p99_ms":...}`。
- 帧与期望值以 `fixtures/blhx/manifest.md` 为准，不许改。
