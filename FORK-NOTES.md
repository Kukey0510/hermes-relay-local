# hermes-relay-local

`Codename-11/hermes-relay` 的私有 fork，用于**端上识图改造**。

- 基线：上游 commit `62de6b51`（v1.13.0，2026-10-07），MIT 协议
- 本仓库为**裁剪快照**：去掉了 `website/` `docs/` `user-docs/` `assets/` `preview/` `legacy-pages-redirect/` `experiments/`，只保留编译所需（`app/` `gradle/` `desktop/` 与根配置）
- 完整历史（含 upstream 全部提交）保留在服务器 `/home/ubuntu/.hermes/hermes-relay`

## 编译

需要 JDK 17 + Android SDK（compileSdk 37）。

```
gradlew.bat assembleDebug
```

详见 `../idea 目录/给Codex-装打包环境.md`（环境安装提示词）。

## 改造范围

第一批只做两件事，详见任务书：

1. **文件双向传输**（`/local/fs/read|write|list`）——排障用，让服务器能取手机上的日志
2. **端上判页**（交错战线优先）——颜色判据 + 小区域模板匹配，目标 < 100ms

### 硬约束

- 只改 `app/`，**不动 `plugin/`**（服务端 Python 在线上跑着）
- 新增文件为主；必须插入的地方不超过 60 行
- 每处改动记进 `PATCHES.md`，供以后跟上游
- 不升级 Gradle / AGP / SDK 版本

## 分支

- `main`：基线，不要直接改
- 改造在 `feature/local-vision` 分支上做
