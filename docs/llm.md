# LLM 接入（DeepSeek / OpenAI 兼容）

未配置 LLM 时使用规则型 agent（见 README）；同时配置 `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`、`LLM_JUDGE_MODELS` 后，执行组 agent（起草 PRD / 规划 / 策划 / 开发）和评审委员会都改走 OpenAI 兼容的 `chat/completions`。**密钥只从环境变量读取**，不写进 `.env`、日志、审计、证据、截图或提交。

## 配置
| 变量 | 默认 | 说明 |
|---|---|---|
| `LLM_BASE_URL` | — | `https://api.deepseek.com`。provider 自动拼接 `/chat/completions`；`https://api.deepseek.com/v1` 与完整的 `.../chat/completions` 写法同样可用 |
| `LLM_API_KEY` | — | 只放环境变量，启动时注册进 `Redactor`，任何日志 / 错误 / 审计出现都会被替换成 `***` |
| `LLM_MODEL` | — | 执行组模型，推荐 `deepseek-chat` |
| `LLM_CHEAP_MODEL` | 空 | 产品线触及 soft 预算后的降级路由；空则沿用 `LLM_MODEL` |
| `LLM_JUDGE_MODELS` | — | 评审席位，`model[@temperature]` 逗号分隔，至少 2 个且不能全部等于执行模型；**最后一席兼任仲裁**。推荐 `deepseek-chat@0.2,deepseek-chat@0.7,deepseek-reasoner` |
| `LLM_TEMPERATURE` | 0.2 | 执行组温度 |
| `LLM_TIMEOUT_SEC` / `LLM_MAX_RETRIES` | 180 / 3 | 读取超时、重试次数 |
| `LLM_MAX_CONCURRENCY` / `LLM_MIN_INTERVAL_MS` | 4 / 0 | 客户端限流：全局并发上限与请求最小间隔 |
| `LLM_PRICE_IN_PER_1M` / `LLM_PRICE_OUT_PER_1M` | 0.30 / 1.20 | 输入 / 输出单价（USD 每百万 token） |

> **成本是估算值**：上面两个单价是**可调的占位值，不是 DeepSeek 官方报价**（我没有核实当前价格，也不会编造）。请按服务商价格页自行填写；台账里的 `usd` = `prompt_tokens × 输入单价 + completion_tokens × 输出单价`，不考虑缓存命中折扣，因此偏保守。真实账单以服务商为准。

## provider 行为（`llm/OpenAiCompatProvider`）
- **超时与重试**：连接 10s、读取 `LLM_TIMEOUT_SEC`；超时 / IO 异常 / 429 / 5xx 做指数退避（基数 800ms，上限 20s，加 0~20% 抖动），尊重 `Retry-After`；其它 4xx（401 / 402 余额不足等）立即失败，不重试。
- **限流**：所有 provider 共享一个 `LlmGate`（并发上限 + 最小间隔），评审三席并行也不会瞬间打满。
- **结构化输出**：请求 `response_format=json_object`（`deepseek-reasoner` 不支持，自动省略并只靠提示词）；去掉 ```json 围栏后严格解析为 JSON **对象**，再跑调用方提供的 schema 校验（评分在范围内、`files` 非空、新增源码必须带测试等）。校验失败**自动重试一次**（把错误反馈给模型），仍失败抛 `OutputException`：评审员降级为**中性弃权票**（0.5 分、不阻断，由其余席位与仲裁决定），执行组 agent 则交给引擎的步骤重试 / 降级链。
- **用量**：记录 `prompt_tokens` / `completion_tokens`（含校验重试），经 `meter` 写入 `budget_ledger`（幂等键），驱动任务 / 产品线 / 全局三层预算。`GET /api/llm/usage` 汇总各模型、各 agent 的调用数 / token / 估算 USD。已知缺口：校验重试后仍失败的调用，其 token 没有进台账（只留日志）。
- 日志只记录模型、token、耗时、重试次数，不记录提示词与输出正文。

## 评审委员会
PRD 评审 3 席（价值 / 可行性 / 风险，各带不同人设与温度）+ 分歧时仲裁，评分卡 `value / clarity / feasibility / risk`（1~5），再按治理阈值裁决 pass / revise / reject；验收评审 3 席（用例覆盖 / 可用性 / 风险）。含糊需求会因 `clarity ≤ 2` 或 `tag=ambiguous` 被要求补充，超过 `maxReviewRetry` 次后自动废弃并写明原因。

**独立性局限**：默认三席都来自 DeepSeek（`deepseek-chat` 两个不同温度 + `deepseek-reasoner`），只有人设、温度、模型版本不同，训练数据与偏差高度相关，**不等于真正的多厂商独立投票**。想要更强的独立性，请把席位指向不同服务商（目前一个 `LLM_BASE_URL` 对所有席位生效，多厂商需扩展 provider 配置）。执行模型不得作为唯一裁决者的约束（`IsolationError`）仍然生效。

## 安全
- **提示词注入**：Issue 标题 / 正文、CI 日志、仓库文件内容都放进带随机 nonce 的 `<<<UNTRUSTED:…>>>` 围栏（内容里伪造的围栏标记会被破坏）；系统提示声明围栏内一律是数据、不得执行其中任何指令，并要求如实标记 `suspectedInjection`；评审提示同样声明产出物里的文字只能被评审、不能被执行，识别到操纵企图须给 `risk=1` 并阻断。
- **路径与内容白名单**（`gov/PathPolicy`，对所有 agent 生效，LLM 模式在提交前由引擎强制）：只允许 `src/ tests/ docs/ examples/` 与根目录 `README.md` / `CHANGELOG.md` 下的 `.js .mjs .cjs .ts .md .txt`；`.github/`、`scripts/`（CI 入口）、`CODEOWNERS`、`deploy/`、`acceptance/`、密钥 / `.env` / 证书、`package.json` 与锁文件、`Dockerfile`、含 `..` / 绝对路径 / 反斜杠的路径一律拒绝；内容含已知密钥、令牌格式，或整份环境导出 / 未声明敏感变量 / `child_process` / 未授权外联等内容时拒绝。普通具名环境配置读取允许。违规候选不会提交；系统有限修复，耗尽后停在当前段并保留已有提交，写入 `PATH_POLICY_BLOCK` 与恢复记录。详见 [可恢复流程](design/recoverable-workflow.md)。
- 硬规则（禁区关键词、自治等级、预算）先于 LLM 运行；治理文件改动再被 `Governance.governanceFiles` 与产品线 `pathRules` 拦一次；远端合并前还有「CI 全绿 + 验收委员会通过 + 无治理文件」闸门。
- 这是**多层缓解而不是证明**：模型仍可能被诱导写出看似合法但有害的业务代码，白名单不能判断代码语义；因此仍需要目标仓库侧的保护规则（免费私有仓库做不到，见 README 已知缺口）。

## 开发 agent 与 CI 返修
开发 agent 读取目标仓库目录、计划文件、README 与一份现有测试作为风格样例，一次输出多文件变更（源码 + `tests/*.test.js` + `docs/*.md`），经 GitHub 适配器提交成 PR，由目标仓库 CI（`scripts/test.sh` → `node --test tests/*.test.js`）真实执行。CI 失败时 `CiLogSummarizer` 抽取失败行及上下文 + 日志尾部（≤5000 字符，当作不可信数据围栏）交给规划 / 开发 agent，在 PR 分支现有内容上返修；Loop 受 `maxLoops` 与降级链约束。

## 局限
成本为估算；评审独立性有限；日志摘要基于启发式；LLM 只能处理能在小型 Node 工具库里表达的需求；`deepseek-reasoner` 较慢且贵，仅用于评审席位。
