# x7x24 — 全自治 Agent 交付流水线

从信号到仓库版本发布由 agent 推进：**GitHub Issue → 需求池 → PRD 评审（规则 + 多评审委员会）→ 规划 / 策划 → 开发（建分支、提交真实代码、开 PR）→ 质量测试（GitHub Actions CI）→ 业务验收 → 发布目标能力检查 → 合并 → 发布（GitHub Release）→ 回写并关闭 Issue**。GitHub Release 不代表应用已上线；微信小程序发布能力尚未接入，选择该目标时停在“待配置发布”。CI 失败会读日志返修（Loop）；自动处理耗尽后停在当前节点，保留进度，等待恢复。详见 [发布目标与平台上线](docs/release-targets.md)。

> 实验性软件。本仓库是独立开源快照；旧运行截图及沙盒记录已排除。请自行配置密钥与部署，不要把真实环境写进仓库。

项目自有代码采用 [Apache-2.0](LICENSE)。第三方材料继续受各自许可约束，见 [NOTICE](NOTICE) 和 [THIRD_PARTY.md](THIRD_PARTY.md)。

安装与安全边界：[安装](docs/installation.md) · [最小权限](docs/permissions.md) · [数据外发](docs/data-policy.md) · [安全报告](SECURITY.md) · [贡献](CONTRIBUTING.md)。

后端 Java 21 + Spring Boot；前端 Next.js 16（App Router）+ React 19 + TypeScript + Tailwind CSS 4（赛博朋克主题，流转脉冲动画）。默认 `ADAPTER_MODE=offline`、`ADAPTER_DRY_RUN=true`。GitHub 真实读写需要显式配置；LLM/SMTP 外发由各自配置控制。

流程恢复规则与历史任务兼容见 [可恢复交付流程](docs/design/recoverable-workflow.md)。

## 架构
```
Next.js 看板 ── /api 代理（REST/SSE）──> Spring Web (Api/Sse)
                              │
                       Engine（事件溯源状态机 + 调度）
        ┌─────────┬───────────┼──────────────┬───────────┐
     Intake    Agents      Governance     Patrol      WorkflowOrchestrator
  (抓取 Issue) (规则/LLM)  (禁区·预算·    (异常巡查)    （可替换 Temporal）
                           kill switch·
                           审计哈希链)
                              │
                     DeliveryTools（GitHubTools）
                  GitHub REST（Issue/分支/PR/合并/Release）+ Actions + 可选 Prometheus
                              │
                  H2（默认）/ PostgreSQL，Flyway 迁移
```
- 引擎：任务状态是事件投影，`task_event` 只追加（触发器禁改），步骤有幂等键与乐观锁，崩溃后同库继续。
- 治理：禁区关键词与自治分级（支付 / 权限 / 发布流水线等拦截并保留恢复记录）、三层预算（金额 / Token / CI 分钟）、kill switch（全局 / 产品线 / agent）、审计哈希链（`GET /api/audit/verify`）、执行与评审隔离。
- **集成页**：在界面里连接 GitHub（授权登录设备码 / 粘贴令牌，AES-GCM 加密存储）、选择并绑定仓库、仓库体检、观察/执行模式切换、连接状态与高级诊断；预算页可编辑额度并留审计。详见 [docs/integrations.md](docs/integrations.md)。支持 GitHub 登录（网页授权 / 设备码），`/api` 全部需要登录，可用 `X7_ALLOWED_GITHUB_LOGINS` 限定白名单，详见 [docs/deploy.md](docs/deploy.md)。
- 适配器：每次真实调用都经过禁区、kill switch、预算、幂等、限流退避与审计；令牌脱敏（`Redactor`）。详见 [docs/adapters.md](docs/adapters.md)。

## 快速开始

本快照按全新安装设计，使用空数据库。完整步骤见 [安装指南](docs/installation.md)。

```bash
npm ci && npm run verify
(cd apps/server-java && mvn verify)
```

正常启动默认要求认证。先配置自己的 OAuth App、允许登录名单、平台管理员和站点来源；本项目不提供默认作者账号、App 或目标仓库。仅在回环本地的假数据演示中可按安装指南显式关闭认证。`ADAPTER_DRY_RUN=false` 会允许真实 GitHub 写入，必须与目标仓库权限和授权范围一起审查。

前端使用 history 地址（如 `/overview`、`/p/<id>/tasks`），页面入口在 `apps/web/src/app/`，业务视图在 `src/views/`。旧 `/#/...` 链接会自动转换。生产运行需要 Node.js；开发与部署步骤见 [开发环境](docs/dev-setup.md) 和 [部署](docs/deploy.md)。

## 环境变量
见 `.env.example`。关键项：`ADAPTER_MODE`(offline|real)、`ADAPTER_DRY_RUN`、`GITHUB_TOKEN`（可选，服务器环境提供；界面连接的账号优先）、`GITHUB_OAUTH_CLIENT_ID`、`X7_SECRET_KEY`、`TARGET_REPO`、`TARGET_BASE_BRANCH`、`TARGET_WORKFLOW`、`INTAKE_LABEL`/`INTAKE_ALL`、`PORT`、`DATA_DIR`/`DATABASE_URL`、`LLM_*`、`TICK_MS`、`INTAKE_MS`。

## agent：LLM 与规则型
- **配置了 LLM**（`LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` / `LLM_JUDGE_MODELS`，OpenAI 兼容）：起草 PRD、规划、策划、开发都由 LLM 完成，开发 agent 一次产出多文件变更（源码 + 测试 + 文档）提交成 PR；CI 失败时把日志摘要喂给 LLM 返修；评审委员会由多个评审席位（不同模型 / 温度 / 人设）投票 + 仲裁。Issue 正文一律当不可信数据（围栏 + 系统提示 + 路径 / 内容白名单），详见 [docs/llm.md](docs/llm.md)（配置、模型、安全、成本估算与局限）。
- **未配置 LLM**：规则型 agent，根据 Issue 内容确定性地生成 JS 模块 + `node:test` 测试 + 文档（模板：slugify / wordCount / clamp / capitalize / isPalindrome）、更新 README「使用说明」，读取失败的 CI 日志后切换实现返修；不支持的需求会被判定为待调整并停止执行。评审由三个规则评审员投票。

## 真实联调边界
真实 GitHub / LLM 联调不属于默认验证。只可在另行授权的一次性测试仓库显式设置 `LIVE=1 TARGET_REPO=owner/disposable-repo`；脚本会创建、合并、发布和复位数据，见 [测试边界](docs/live-e2e.md)。本快照不包含历史联调结果。

任务通知：支持个人验证收件邮箱、工作区或项目订阅、站内通知中心及后台邮件重试。管理员发信配置和触发规则见 [任务通知](docs/notifications.md)。

## 目录

```
apps/server-java/   Java 21 + Spring Boot 后端（engine / agents / gov / adapters / llm / web / db，测试在 src/test）
apps/web/           Next.js 16 App Router + React 19 + TS + Tailwind CSS 4（Vitest）
packages/shared/    前后端共享类型
scripts/            live-e2e.sh · live_e2e.py（规则 agent 联调）· live-e2e-llm.sh · live_e2e_llm.py（LLM 联调）· screenshots.cjs（截图）
docs/               decisions（5 个自治边界决策）· dev-plan · adapters · llm · orchestration · design-system · live-e2e · dev-setup
```
本快照排除了旧运行截图和视频。视觉材料应由固定 mock 数据重新生成并逐张审查。

## 验证与限制

本次快照的验证、清理、遗留风险见 [准备报告](docs/release-preparation.md) 和 [依赖扫描清单](docs/security-findings.json)。当前依赖复扫无命中；扫描发现或工具失败仍会阻断 CI。部署配置、密钥与基础设施细节不在本仓库中。

- 实验性自治交付软件；不承诺生产安全或完全消除数据泄漏。
- 已有工作区角色和 RLS，后台系统上下文仍有跨租户权限；部署账号与数据库权限须独立限制。
- 自研事件溯源引擎，Temporal 仅有接口。Prometheus 金丝雀和真实部署回滚未在本次验证。
- LLM 成本为可配置估算，多评审模型的独立性取决于部署配置。
- 依赖/字体许可已建立构建清单；旧图标已统一替换为有官方许可证据的 Lucide，兼容的滚动条补丁已按精确源码许可锁定，详见 docs/materials-audit.md。原7x24数据库不可直接接入。

## 产品线与项目

在「产品线与项目」页面新增产品线，再添加连接 GitHub 仓库的项目。项目下创建交付任务，任务在流水线中流转，节点执行记录保存每次执行的结果和证据。LMS 可选择 React + Go 预设。

详见 [项目架构与使用方法](docs/project-architecture.md)。本独立快照不支持直接接入原仓库数据库。对于本快照内部的后续启动，已有项目配置以数据库为准。

过程学习 MVP：支持整理执行样本、生成返修策略候选、隔离对照评测和受控试用，默认关闭。配置、接口与验证范围见 [学习闭环](docs/learning.md)。
