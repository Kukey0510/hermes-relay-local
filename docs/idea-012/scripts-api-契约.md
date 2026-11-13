# 脚本目录接口契约（块15 / 块16 共用）

> 2026-11-13 立。**这是接口契约，App 侧照它写，服务器侧由小赫实现。**
> 定稿依据：用户 2026-11-13 口径——「按游戏分 tag，后面我自己能改」「tab2 加按钮手动拉，点 tab2 也拉一次」
> 「失败叫服务器解决，解决不了报错到会话」「日志留三天，失败推 QQ」。

## 基址与鉴权

- App 已有的 Hermes API 基址 + 现有 bearer token（和 `/api/sessions/*` 同一套，不新造鉴权）
- 全部 JSON，`Content-Type: application/json`

## 1. 拉目录 `GET {base}/scripts/catalog`

响应 200：

```json
{
  "version": "2026-11-13T13:30:00Z",
  "scripts": [
    {
      "id": "bl-jjc-daily",
      "name": "碧蓝演习",
      "game": "碧蓝航线",
      "targetPackages": ["com.bilibili.azurlane"],
      "tags": ["碧蓝航线", "日常"],
      "updatedAt": "2026-11-13",
      "description": "演习列表页读次数，够 5 次就跑满",
      "steps": [
        {"op": "probe", "expect": "list", "timeout_ms": 3000},
        {"op": "read", "field": "jjc_times"},
        {"op": "tap", "x": 2180, "y": 980}
      ],
      "sourceNote": "服务器推送"
    }
  ]
}
```

- `tags` 是**用户标签**（服务器侧已合并用户的编辑结果），不是默认值
- `steps` 是结构化步骤，语义见块16 任务书
- `targetPackages` 是**每个脚本自己的白名单**（不搞全局白名单）。已核实的三款：
  - 碧蓝航线 `com.bilibili.azurlane`
  - 交错战线 `com.megagame.crosscore`
  - 盲盒派对 `com.palmpi.matrix`
  （2026-11-13 从手机 `/get_apps` 实测拿到，不是猜的）
- 服务器没准备好或出错时返回 5xx，App **保留本地缓存并在 UI 标「离线」**，不许清空列表

## 2. 回写标签 `POST {base}/scripts/tags`

请求：

```json
{"tags": {"bl-jjc-daily": ["碧蓝航线", "日常", "演习"]}}
```

- 语义：**整表覆盖**（不是增量合并），App 每次把当前全量标签提交
- 响应 200 `{"ok": true, "version": "..."}`
- 服务器收到即落盘，下次 `/scripts/catalog` 返回的就是新值
- App 侧失败时不要回滚本地编辑，只标「待同步」，下次拉取前重试一次

## 3. 执行事件 `POST {base}/script-events`

请求：

```json
{
  "scriptId": "bl-jjc-daily",
  "runId": "uuid",
  "level": "error",
  "stepIndex": 3,
  "op": "probe",
  "message": "page=match_failed expected=list",
  "ts": "2026-11-13T13:30:00Z"
}
```

- 服务器收到后：先尝试自动处置（记录 + 按规则决定要不要重试/提醒），处置不了就推 QQ 会话
- `level` 取值：`info` / `warn` / `error`
- 响应 202 `{"accepted": true}`；超时或失败不影响手机端继续跑，App 只记本地日志

## 4. 拉取时机（App 侧）

- 进入 tab2 时自动拉一次（有 5 分钟节流，避免来回切 tab 反复打）
- tab2 顶部按钮手动拉（无视节流）
- 拉失败：保留缓存 + 顶部一行「离线，最后同步 xx:xx」

## 5. 下发运行（服务器 → 手机，走 bridge）

`POST {bridge}/script/run`，body：

```json
{"scriptId": "bl-jjc-daily"}
```

- 手机会话层（bridge）已有通用转发，服务器侧由小赫加一条路由再透传
- 响应 200 `{"accepted": true, "runId": "..."}`；已在运行则 409 `run_in_progress`
- 脚本白名单不匹配、抓帧授权失效等，都在 App 内直接拒绝，回 4xx + `error_code`
- 手机详情页的「运行」按钮走同一条内部路径，不发 HTTP

## 6. 失败推送目标（写死）

`qqbot:78313C5CF2D2A704917D25B4E2421416` —— 当前这个 QQ 私聊，不按「最近活跃」动态选，
免得推到另一个号上。

## 服务器侧配套（小赫做，不派 Codex）

- 在 `plugin/relay/server.py` 加三条路由，透传到 Hermes 后端或直接读本地文件
- 脚本源文件放 `~/.hermes/data/relay-scripts/scripts.json`，用户标签 `tags.json`
- 失败推送走 Hermes 的 cron/notify 通道到 QQ 私聊
