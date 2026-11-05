# 工具适配器（Git / CI / 指标）

接口 `DeliveryTools`（`apps/server-java/.../adapters/DeliveryTools.java`）是流水线与外部世界之间唯一的出口，实现为 `GitHubTools`（`GitHubClient` 负责 HTTP、重试与限流）。

| 能力 | 实现 |
|---|---|
| Issue 抓取 / 评论 / 标签 / 关闭 | GitHub Issues API（带 `INTAKE_LABEL` 的 open Issue） |
| 建分支、提交文件、开 PR、读文件列表与检查、合并 | GitHub REST（分支名 `agent/<task8>-it<n>`，合并用 squash） |
| CI：触发、轮询、读 job 与日志、CI 分钟 | GitHub Actions（`workflow_dispatch`，`TARGET_WORKFLOW`） |
| 发布 / 回滚 | GitHub Release（tag `<RELEASE_PREFIX>-<task8>` 指向合并提交）；回滚用 revert PR |
| 金丝雀指标 | main 分支 CI 运行结果；配置 `PROMETHEUS_URL` 后可加 PromQL 查询（未联调） |

GitHub Release 只代表仓库版本发布。项目可选择 `github_release` 或 `wechat_miniprogram`；微信发布能力尚未接入，选择后任务会在合并前停在“待配置发布”。发布状态、能力检查及恢复规则见 [发布目标与平台上线](release-targets.md)。

## 模式
- `ADAPTER_MODE=offline`（默认）：不触网，不抓取 Issue。
- `ADAPTER_MODE=real` + `ADAPTER_DRY_RUN=true`（real 的默认）：读请求真实发出，写操作只记录「将要执行的请求」与审计，不发送。
- `ADAPTER_MODE=real` + `ADAPTER_DRY_RUN=false`：真实写入。需要 `GITHUB_TOKEN`（classic PAT：`repo` + `workflow`；或限定单仓库的 fine-grained PAT：Contents RW、Pull requests RW、Actions RW、Issues RW、Workflows RW）。

## 每次真实调用的流程
1. 禁区拦截：只允许推送 `agent/*` 分支；改动治理文件 / `.github/workflows` 的 PR 被拒；`main` 直推被拒。
2. kill switch：`HALT` / `READ_ONLY` 阻断一切写；`FREEZE_RELEASE` 阻断合并与发布。
3. 预算：触顶则阻断；CI 分钟计入台账。
4. 幂等：步骤幂等键缓存结果；远端 422「已存在」（分支 / PR）视为成功并复用。
5. 执行 + 限流退避（429、次要限流按 `Retry-After`，主限流等到 reset）。
6. 审计：写调用追加哈希链审计；全部调用写入调用日志，页面「适配器」可查看。

绝不：直接推送受保护分支、管理员 bypass、修改分支保护 / rulesets（只读）、force push。

## 安全
令牌只存在于进程环境与 `Authorization` 头；`Redactor` 对日志、错误、审计、CI 日志摘要脱敏（`ghp_` / `github_pat_` 等模式与精确令牌）。调用日志不记录请求头与 body。`GET /api/adapters` 只返回 `tokenConfigured`。

## 测试
`GitHubAdapterIT` 用 MockWebServer 覆盖成功、失败、限流、幂等、脱敏、dry-run；真实联调见 [live-e2e.md](live-e2e.md)。
