# IDEA-011 r6d 块 H：FrameArchive 环形裁剪单测

仓库 `Kukey0510/hermes-relay-local`，分支 `main`，直接改当前分支，不开 PR、不开 feature 分支。

**环境**（任务 shell 里 `JAVA_HOME`/`ANDROID_HOME` 为空，命令写绝对路径）
`JAVA_HOME=/opt/jdk17`、`ANDROID_HOME=/opt/android-sdk`

真实 gradle 任务名（别写错）：

- `:app:compileSideloadDebugUnitTestKotlin`
- `:app:assembleSideloadCandidate`
- `:app:testSideloadDebugUnitTest --tests "*FrameArchiveTest*"`

整套单测有 98 个既有失败，**只跑 `--tests "*FrameArchiveTest*"`**，不许跑全量。

## 只许碰这两个文件

1. 改 `app/src/main/kotlin/com/axiomlabs/hermesrelay/localvision/FrameArchive.kt`
2. 新建 `app/src/test/kotlin/com/axiomlabs/hermesrelay/localvision/FrameArchiveTest.kt`

**禁止改**：`DigestReader.kt`、`DigestReaderTest.kt`、`assets/**`、`fixtures/**`、`plugin/**`、
`docs/**`、`LocalVisionBridgeRoutes.kt`、`BridgeCommandHandler.kt`、`BridgeCapabilities.kt`、
`ScreenCapture.kt`、任何 gradle 文件。

## 背景与硬约束（这条最关键）

`FrameArchive.save()` 依赖 `android.graphics.Bitmap` 和 `android.content.Context`，
**纯 JVM 测试跑不了**；而 Robolectric 在这个云端沙箱里拉不到构件（既有 98 个失败全是这个原因）。

所以**必须先把「环形裁剪要删哪些文件」抽成纯 JVM 的纯函数，再对它写测试**。
**测试文件里出现下面任何一个 import 都算不合格**：

`org.robolectric.**`、`android.graphics.**`、`android.content.**`、`android.os.**`

## 要做什么

### 1. 抽出纯 JVM 裁剪策略（`FrameArchive.kt` 内）

在 `FrameArchive` 里新增（外部可见性用 `internal`，便于同模块测试直接调）：

```kotlin
/** 环形裁剪的决策结果，与文件系统无关，纯 JVM 可测。 */
internal data class ArchiveCandidate(
    val name: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
)

/** 返回应当被删除的条目，按「最旧优先」顺序；调用方负责真正 delete。 */
internal fun evictionsFor(
    entries: List<ArchiveCandidate>,
    maxFiles: Int = MAX_FILES,
    maxBytes: Long = MAX_BYTES,
): List<ArchiveCandidate>
```

语义（照这个写）：

- 按 `lastModifiedMs` 升序、相同再按 `name` 升序，得到「最旧优先」序列
- 从最旧开始逐个判入待删列表，直到「剩余条数 ≤ `maxFiles` **且** 剩余总字节 ≤ `maxBytes`」
- 剩余条数/字节都已满足 → 返回空列表
- `entries` 为空、`maxFiles <= 0` 的极端输入不许抛异常（`maxFiles <= 0` 视为全删）

然后把现有私有 `trim(directory: File)` 改成：读出 `ArchiveCandidate` 列表 → 调 `evictionsFor` →
按返回顺序 `delete()`。

**不许动的东西**：

- `save()` 的签名、返回值、对外行为一行不许变（`Entry` / `DIR_NAME` / 目录名 / 文件名格式 / `synchronized` 锁都保留）
- 不许改 `MAX_FILES = 12`、`MAX_BYTES = 20MB`
- 不许改 `list()`
- 不许把 `Bitmap` / `Context` 的用法搬到别处绕开

### 2. 单测（`FrameArchiveTest.kt`，纯 JVM）

三条必须覆盖：

1. **超文件数**：13 条、每条 `sizeBytes = 1KB`、`maxFiles = 12`、`maxBytes = 20MB`
   → 恰好返回 1 条，且是 `lastModifiedMs` 最小的那条
2. **超总量**：4 条、每条 `sizeBytes = 8MB`、`maxFiles = 12`、`maxBytes = 20MB`
   → 返回待删条目使剩余总量 ≤ 20MB（即删掉最旧的 2 条），且顺序是「最旧在前」
3. **空输入 / 不超限**：空列表 → 空结果且不抛；2 条小文件在限额内 → 空结果

额外一条硬要求：**同 `lastModifiedMs` 的条目必须按名字稳定排序**，用一个用例把这条钉住
（两条 mtime 相同、名字不同，断言被删的是名字小的那条）。

测试只构造 `ArchiveCandidate` 数据，**不创建真实文件、不碰临时目录、不碰 Bitmap**。

## 先落盘再验证

先把代码写进磁盘，再跑 gradle。**不要先输出长篇设计方案。**

## 验收（必须真跑，贴实际结果行）

```bash
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:compileSideloadDebugUnitTestKotlin
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:assembleSideloadCandidate
JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ./gradlew :app:testSideloadDebugUnitTest --tests "*FrameArchiveTest*"
```

## 回答格式（≤20 行，不贴全量日志、不贴大段代码）

```
改了什么：<文件列表，一行>
裁剪函数：<签名 + 一句话语义>
验收：<每条命令 -> 实际结果行，含 test 通过数>
越界：无 / <说明>
```

不要试图 push（云端没有 git remote）。
