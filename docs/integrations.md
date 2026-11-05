# 集成：连接 GitHub、仓库、写入模式

工作区管理的「集成」页分为**连接与仓库**、**连接状态**。登录后直接使用已有的 GitHub 授权选择仓库，不再要求重复连接。

## 登录与仓库授权

网页登录与设备码登录均在校验身份、登录名单后，加密保存个人 GitHub 授权及刷新凭据。首次登录为个人工作区关联这份授权；已有同账号 OAuth 连接会升级为引用个人授权。手动 PAT 连接保持原样。

GitHub 的账号授权与 App 仓库安装权限是两件事：已有安装时直接选择仓库；尚未安装时点击「管理仓库授权」，在 GitHub 选择允许访问的仓库。配置 App 的 Setup URL 为 `<后端公开地址>/api/auth/github/setup`，安装完成后自动回到原窗口。组织审批中的请求显示「等待管理员批准」。回调 state 带签名、有效期、用户和工作区，并重新检查管理员角色；仅凭回调中的 installation_id 不会绑定安装。

团队工作区的连接由 owner/admin 管理；成员登录不会覆盖团队连接。仓库列表查询只读，只有显式绑定时才校验操作者能访问并写入该仓库、认领安装及记录选中的仓库。一个 GitHub 安装仍只属于一个工作区。

## 状态与恢复

顶栏和集成页共享连接状态：正常、待授权、待选择仓库、等待管理员批准、权限待更新、需要更新授权、网络异常。进入工作区、回到窗口、每分钟及仓库请求失败时更新状态。保存了令牌不再等于连接正常，GitHub 401 不会退出本站登录。

正常过期在服务端自动续期，刷新凭据与访问令牌原子轮换，并通过数据库行锁避免并发重复刷新。刷新暂时遇到网络故障时保留凭据并提供重试；刷新凭据失效或授权被撤销时才要求重新授权。

更新授权在新窗口或验证码弹窗完成，限制使用当前登录账号。原项目、仓库搜索和绑定表单保持打开；管理员明确恢复时可用这次登录授权更新工作区连接。恢复后自动重新读取仓库，写操作不会自动重放。

## 连接方式

| 方式 | 适合 | 说明 |
|---|---|---|
| GitHub 登录授权 | 默认 | 一次登录后直接选择已授权的仓库；网页和设备码登录共用这份授权 |
| 高级：工作区设备码连接 | 兼容方式 | 管理员可在高级连接方式中连接工作区凭据，支持自动续期 |
| 高级：粘贴令牌 | 自托管 / 兼容方式 | 保存前先调用 `GET /user` 校验，并显示权限摘要；最少权限：经典令牌 `repo` + `workflow`，或限定仓库的细粒度令牌（Contents、Pull requests、Issues、Actions 读写） |
| 服务器环境 `GITHUB_TOKEN` | 部署时预置 | 来源显示为“服务器环境变量”，界面不能删除，只会被界面里连接的账号覆盖 |

**优先级**：数据库里已连接的凭据 > 环境变量 `GITHUB_TOKEN`。全部通过 `CredentialProvider` 取令牌，运行时变更立即生效，不用重启。

### 授权登录需要什么

授权登录使用 **GitHub App 的设备码流程**（只要 Client ID，不需要 client secret）：

1. 在 GitHub App 设置里勾选 **Enable Device Flow**。否则 GitHub 返回 `device_flow_disabled`，页面会明确提示“请到 GitHub App 设置里勾选 Enable Device Flow”，并引导使用「粘贴令牌」。
2. 用户需要在 GitHub 上**安装该 App 并选择可访问的仓库**。App 的用户访问令牌只能看到已安装的仓库；仓库列表为空时，页面会提示安装（设置了 `GITHUB_APP_SLUG` 时给出 `https://github.com/apps/<slug>/installations/new` 链接，否则提示去 App 页面安装）。
3. App 权限需包含：Contents、Pull requests、Issues（读写）、Actions（读写）、Metadata（只读）。

## 安全模型

- 令牌用 **AES-256-GCM** 加密后存入 `integration_credentials`（Flyway `V4`），只存密文和元数据（来源 oauth/pat、github login、scopes/权限摘要、创建时间、过期时间）。
- 统一登录授权存入 `github_user_authorization`（`V52`），工作区通过 user_id 引用；访问令牌及刷新令牌均加密，不返回前端。
- 主密钥：环境变量 `X7_SECRET_KEY`；未设置时在 `DATA_DIR` 下生成随机的 `secret.key`，权限 600。**丢失密钥文件 = 已保存的令牌无法解密**，重新连接即可。
- 令牌**永不返回前端**，也不进日志、审计、证据、SSE；`Redactor` 覆盖所有已注册令牌。
- 设备码轮询状态存在内存里并带过期；后端按 GitHub 返回的 `interval` 节流，处理 `authorization_pending` / `slow_down` / `expired_token` / `access_denied`。
- 已启用 GitHub 登录：`/api` 除 `/api/auth/*`、`/api/health` 外都需要登录，只有 `X7_ALLOWED_GITHUB_LOGINS` 白名单内的账号可访问；跨域只放行 `X7_ALLOWED_ORIGINS`。令牌在服务端 AES-GCM 加密保存，永不显示（集成页顶部提示会按实际配置显示）。

## 仓库、体检与 CI 模板

- 仓库选择器：搜索、分页，来源是 `GET /user/repos`（粘贴令牌）或 App 安装的仓库（授权登录）。可绑定到已有项目，或新建项目。沿用“一个未归档项目绑定一个仓库”“有未结束任务不能切换仓库”两条约束。
- **体检**（只读，`POST /api/projects/{id}/connection`）逐项检查：仓库可读、分支存在、CI 文件存在、CI 支持 `workflow_dispatch`、令牌写权限（代码/PR/Issue/Actions）、分支保护（读不到就标“无法确认”，不报错）。每个失败项有人话原因和修复建议。
- **补 CI 模板**：缺少 CI 文件时，可一键“新建分支 + 提交模板 workflow + 开 PR”。这是用户显式发起的一次性初始化，后端单独放行路径策略（`.github/workflows` 平时禁止修改）并写审计 `INTEGRATION_CI_BOOTSTRAP`；**只开 PR，不自动合并**。观察模式下只预览模板和手动步骤。

## 写入模式

| 界面 | 含义 | 内部对应 |
|---|---|---|
| 体验模式 | 没有凭据，所有写操作不触网 | — |
| 观察模式（只看不改） | 读取仓库，写操作只记录 | `ADAPTER_MODE=real` + dry-run |
| 执行模式 | 会创建 Issue、PR、触发 CI、合并、发布 | `ADAPTER_MODE=real`，`ADAPTER_DRY_RUN=false` |

模式是运行时的全局持久化设置（`settings` 表），环境变量 `ADAPTER_MODE` / `ADAPTER_DRY_RUN` 只作初始默认。切到执行模式需要确认。

## 无人值守的后台身份

配置 `GITHUB_APP_PRIVATE_KEY`（支持 PKCS#1 / PKCS#8 PEM）和 GitHub App Client ID 或 `GITHUB_APP_ID` 后，已显式绑定的仓库使用安装令牌运行后台任务。令牌通过 RS256 签名的 App JWT 生成，只包含该次请求的 repository_id；到期前重新生成并缓存。用户退出本站或个人访问令牌过期不影响已经授权的后台任务。安装被撤销或暂停后，显示仓库权限需要更新。

未配置 App 私钥时保留现有用户令牌 / PAT 执行方式，并对支持刷新凭据的 OAuth 连接自动续期。增加配置后，需要在仓库选择器中明确绑定一次已有仓库，才能记录其安装和仓库 ID；不会从历史浏览记录推断授权。

## 环境变量

`GITHUB_OAUTH_CLIENT_ID`、`GITHUB_OAUTH_CLIENT_SECRET`（网页登录）、`GITHUB_APP_SLUG`、`GITHUB_APP_PRIVATE_KEY`（后台安装身份）、`GITHUB_APP_ID`（未使用 Client ID 签名时）、`X7_SECRET_KEY`、`DATA_DIR`、`GITHUB_TOKEN`（兼容方式）。旧版本丢弃了登录授权的用户，需要更新一次授权后进入新流程。

## 预算

「预算」页可编辑：全局、产品线、项目、单任务额度，触顶行为（降级 / 暂停新任务 / 全部停止）和降级线。保存前校验“下层不超过上层”并预览影响，保存立即生效并写审计 `BUDGET_UPDATED`（变更历史在页面底部）。额度持久化在数据库；yaml/env 只是初始默认。接口：`GET /api/budget/summary|changes|projects/{id}/tasks`，`POST /api/budget/preview`，`PUT /api/budget/limits`。
