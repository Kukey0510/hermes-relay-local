# Codex 任务书：relay 端上识图（第一批 · 最小闭环）

版本 v1 · 2026-11-10
配套：`测试清单.md`（验收标准）、`给Codex-装打包环境.md`（环境）

---

## 0. 你的处境

- 你在用户的 Windows 电脑上，能执行命令、能读写文件。
- **你碰不到手机，也碰不到服务器。** 手机上的 App 由用户自己安装验证。
- 项目代码在用户本地 `F:\project\hermes-relay-local`，从 https://github.com/Kukey0510/hermes-relay-local 克隆（私有仓库，需 GitHub 认证）。
- 这个项目是 `Codename-11/hermes-relay` 的私有 fork，基线 commit `62de6b51`（v1.13.0），MIT 协议。
- 仓库是**裁剪快照**：只有 `app/` `gradle/` `desktop/` 和根配置，`website/` `docs/` 等已去掉。完整历史在服务器上，你不需要。
- **用户会在你改代码期间继续用现有脚本玩游戏，任何改动不能影响线上。**

---

## 1. 要解决什么

现状：手机上的 App 每判断一次游戏页面，就把整屏截图压成 PNG（约 3MB）传给服务器，服务器用 tesseract 识别。一次 5~9 秒。

目标：**让 App 自己看**。截图留在手机内存里，本地比对，服务器只收结果。

预期收益：一次判页从 5~9 秒降到 20~100 毫秒。

---

## 2. 本次只做两件事

### P0-1：文件双向传输（最高优先级）

**用途**：排障。脚本卡在奇怪的地方时，服务器要能拿到手机上的日志和截图，否则没法诊断。

需要三个端点（挂在 App 的 bridge 服务上，和现有 `/screenshot` 平级）：

| 端点 | 作用 | 参数 |
|---|---|---|
| `GET /local/fs/read` | 读手机上的文件，返回字节流 | `path`（绝对路径） |
| `POST /local/fs/write` | 往手机写文件 | `path` + `base64` 内容 |
| `GET /local/fs/list` | 列目录 | `dir`，可选 `pattern` |

安全要求（必须做）：
- 路径白名单：只允许 App 沙盒目录 + `/sdcard/Android/data/<包名>/` + `/sdcard/hermes/`。白名单外一律 403。
- 单文件大小上限 50MB。
- 拒绝 `..` 路径穿越。
- 写操作只允许白名单目录。

**验收**：
- 用 `adb push` 一个已知文件进白名单目录，调 `read` 拿回来，字节完全一致（比对 sha256）。
- 往白名单外读，返回 403。
- 读一个 20MB 文件，耗时应 < 3 秒（本地网络）。

### P0-2：端上判页（证明速度）

**用途**：验证端上识别真的能快，而不是纸上谈兵。

这一批**只要判页**（认出当前是哪个页面），不要做读数字（判数留到第二批）。

范围（交错战线优先，因为用户最痛）：`home` / `base` / `staff` / `arena_select` / `arena_battle` / `other`

**技术方案**（服务器上已实测的数据，照这个方向做）：

| 做法 | 实测耗时（服务器 CPU） |
|---|---|
| 全屏彩色模板匹配 2460×1080 找 100×50 | 191ms |
| 同上，转灰度 | 65ms |
| 同上，灰度 + 降采样 0.5 | **11.7ms** |
| 区域搜索（800×400）彩色 | 19ms |
| 颜色判据（区域均值差） | **0.14ms** |

建议实现：
1. 先做**颜色判据**（区域均值 vs 期望值，容差 10~30）—— 覆盖大部分页面，几乎零成本。
2. 颜色判不准时才上模板匹配：**先转灰度，必要时降采样 0.5**。
3. 模板图由用户提供（`frames/` 目录，见 `测试清单.md`），放到 `app/src/main/assets/templates/`。
4. 判据顺序和阈值写在一个 JSON 里（`app/src/main/assets/pages.json`），**别硬编码在 Kotlin 里** —— 用户要能改判据而不用重新编译。

`pages.json` 建议格式：

```json
{
  "pages": {
    "arena_select": {
      "checks": [
        {"type": "color", "area": [1100, 300, 1300, 360], "expect": [45, 52, 70], "tol": 25},
        {"type": "template", "file": "arena_select_title.png", "area": [900, 120, 1600, 200], "min": 0.85}
      ]
    }
  }
}
```

`checks` 全部通过才算命中；都不命中返回 `other`。

**验收**：见 `测试清单.md` 第 2 组（M-1 ~ M-7）+ 第 4 组 E-3。

---

## 3. 明确不要做的（这批）

- ❌ 不动现有 bridge 端点（`/screenshot` `/tap` `/screen` `/media` …）的**行为**，只在旁边加新的。
- ❌ 不做虚拟屏 / `am start --display` / ADB 授权（用户手机固定，游戏前台即可）。
- ❌ 不做流程引擎（"第一屏点这里、第二屏点那里"），下一批再做。
- ❌ 不做全屏 OCR / 不引入 Tesseract。
- ❌ 不升级依赖版本、不改 Gradle 版本、不换 AGP。

---

## 4. 硬约束

1. **改动最小化**：新增文件为主。可以插入的地方只有三处，总共不超过 60 行改动：
   - `app/src/main/kotlin/.../accessibility/ScreenCapture.kt`（加本地模式分支）
   - `app/src/main/kotlin/.../bridge/`（注册新端点）
   - `app/src/main/AndroidManifest.xml`（如果需要新权限，尽量不需要）
2. **必须写 `PATCHES.md`**：一条一行，记录「改了哪个文件 / 加了什么 / 为什么」。用户以后要跟上 upstream，靠这个文件。
3. **不许碰** `plugin/`（服务端 Python）。这一批只改 `app/`。
4. **编译必须通过**：`gradlew.bat assembleDebug` 成功出 APK。
5. **自测要贴原始输出**：测试脚本的 stdout 原样贴，不许写"测试通过"四个字了事。

---

## 5. 交付物

1. 代码（分支 `feature/local-vision`）
2. `tools/bench_local_vision.py`（或 Kotlin 侧等价物）：一条命令跑完 `测试清单.md` 的四组测试，输出 JSON + 可读报告
3. `BENCH.md`：实测数字对照表，逐项打勾或打叉
4. `PATCHES.md`
5. 一段「用户怎么装怎么验证」的说明（三条命令以内）

---

## 6. 分批节奏

**第一批（本次）**：P0-1 文件传输 + P0-2 判页（交错战线）
**第二批**：判数（次数、战力）+ 碧蓝适配 + 流程引擎

第一批做完先给用户看数据，通过了再开第二批。别一次全做。

---

## 7. 卡住了怎么办

- 编译报错 → 贴原文，自己先试两轮，还不行就停下来说清楚卡在哪。
- SDK 版本对不上 → **停下来问**，不要自己换版本。
- 判据调不准 → 用 `frames/` 里的帧做回归，说明哪张判错、错成什么、你的猜测。

---

## 8. 附：服务器端已确认的事实（不用你验证）

- relay 的 Android 端已有 MediaProjection + VirtualDisplay + ImageReader 常驻管线，`ScreenCapture.kt` 726 行，处理过 Android 14 的 single-create 限制。
- 点击注入在 `ActionExecutor.kt`（1841 行），`dispatchGesture` + 节点点击降级链。
- 上游近 105 个提交没动过 `accessibility/` 目录。
- 服务器（Ubuntu）没有 Java/Android SDK，编不了 APK，所以只在你这里编。
