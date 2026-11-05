# 大需求：按风险分级合并，不再因为改动大而废弃（设计）

> 状态：**设计稿 v2，待确认**（决定项 D1–D14 见 §12）。v1（54b954b）的方向是「不放宽上限、把大需求拆小」，已按用户确认的新方向改写：**改动大小只决定要过哪道关，不决定能不能做**。
> 基线：`codex/product-line-project-architecture` @ 54b954b。
> 关联：`composable-pipeline.md`（流程组装、任务流程图快照、通用引擎）、`chat-intake-and-adaptive-pipeline.md`（对话录入、放松审批）、`docs/copy-guide.md`（文案规范）。

## 0. 结论

1. **取消「超过改动上限就废弃」**。改动多大都照常开发、测试、开 PR。改动大小只决定合并前要过哪一道关。
2. **按「没经过人看就上线的风险」分级合并**：
   - 小改动且低风险：按自治等级自动合并、自动发布（和现在一样）；
   - 改动较大或风险较高：照常做完、开 PR，合并前多过一道关。这道关是更严的测试 / 评审（加一轮评审委员会、更高覆盖率、安全审计），以及 / 或者负责人一键确认。
   - 分级用**加权行数**计（测试 0.5、纯删除 0.5、翻译文件 0.1、lock / 生成文件 0），阈值参考业界数据（§2），并和自治等级联动：L1 合并前总要确认，L2 / L3 按阈值。
3. **现有的每次提交 / 每段上限改成「建议」**：只用来把提交切成方便审阅的大小（分段），不再硬性拒绝。叠加的余量系数（v1 的 0.6）取消，只保留一层余量（`planSplitRatio`）。
4. **父需求 + 子需求的拆分保留，但目的是好审阅、好撤回**，不是硬门槛。规划助手优先把大需求拆成能单独合并的子需求；拆完仍然大的子需求就走更严的那道关。
5. **花费 / 时间 / 步数预算是硬兜底**（业界通行做法）：钱、Token、CI 分钟、开发轮次、单步耗时用完才会停，规则和现在的「单任务预算 + 省钱模式」一致。
6. 被废弃的日语多语言任务在新规则下：照常完成，拆成 4–6 个子需求；翻译文件按 0.1 计，大多数子需求能自动合并，替换组件文案那一两个子需求改动较大，需要负责人一键确认后合并（§9）。

## 1. 背景

### 1.1 日语多语言任务为什么被废弃

项目上限是每次 20 个文件 / 100 行，最多自动拆 12 段。现在的路径是：

- `LlmAgents.plan()` 给出 `estimates` 和 `subtasks`；`repack()` 按每段 ≤ `maxChangeLines × planSplitRatio(0.8)`、≤ `maxFiles × 0.8`、原始行 ≤ `OUTPUT_LINE_CAP(350)` 重新装箱。
- 段数 > `ChangeSize.splitCap(rel) = max(maxSplitParts, maxAutoSplitParts)` 时 `estimate.exceedsBudget = true`，`Engine.onPlanning()` 调 `exceedsBudget()` → `Decision.discard("exceeds_budget")`。
- 开发阶段也会走到废弃：`change_too_large` 异常的自动修复试完「收缩 → 拆分 → 重新规划」仍超限，同样调 `exceedsBudget()`。
- 验收的 L1 检查 `diff_within_limits`（`prWithin`：PR 加权行 ≤ 单段上限 × 段数）不通过也会退回。

也就是说，**改动大小现在是「能不能做」的门槛**。

### 1.2 为什么要改

7x24 会自动合并、自动发布，没有人在合并前看 PR。改动上限其实是在**代替人工审阅**：改动越大，没人看过就上线的风险越高。所以真正要控制的是「没经过审阅就到线上的风险」，而不是行数本身。

## 2. 业界做法对照

| 产品 / 研究 | 有没有硬性行数上限 | 用什么兜底 | 大任务怎么做 |
|---|---|---|---|
| GitHub Copilot 编码助手 | 没有；开 PR，**合并前必须由人审阅**，不能自己批准自己的 PR | 每次会话最多 59 分钟 | 一个 Issue 一个 PR |
| Devin | 没有；开 PR 给人审阅 | 会话时长 / 用量额度（ACU） | 先出计划，大任务拆成多个会话 / PR |
| OpenAI Codex | 没有；在沙箱里做完，给出 diff / PR，由人审阅 | 任务时长与用量 | 文档建议先规划、再分成小任务逐个做 |
| Claude Code `/batch` | 没有 | 用量 | 先规划，把大改动拆成 5–30 个独立单元并行做，每个单元一个 PR |
| Anthropic 长时运行助手的做法 | 没有 | 多轮会话 | 先写「功能清单 + 进度文件」，每轮读进度、做一项、更新进度、提交 |
| SWE-agent / OpenHands | 没有 | 单任务花费上限（例如 $3）、最多步数（例如 500 步） | — |
| SmartBear / Cisco 代码审阅研究 | — | — | 一次审阅 200–400 行以内效果最好，超过 400 行发现问题的能力明显下降 |
| Google 工程实践「小改动」 | — | — | 一个改动只做一件事；100 行左右合适，1000 行通常太大 |
| LinearB 2025 基准 | — | — | AI 生成的 PR 中位数约 89 行改动；精英团队 PR 一般 < 200 行 |

要点：**别人没有硬性行数上限，是因为合并前有人审阅；他们限制的是时间、花费、步数**，并且「先规划再拆小」。7x24 没有人工审阅，所以用「按大小 / 风险分级的合并关卡」代替人工审阅：小的自动过，大的多一道关（更严的自动检查或负责人确认）。

来源：
- https://docs.github.com/en/copilot/concepts/agents/coding-agent/about-coding-agent
- https://docs.devin.ai/
- https://code.claude.com/docs/en/commands
- https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents
- https://developers.openai.com/codex/workflows
- https://google.github.io/eng-practices/review/developer/small-cls.html
- SmartBear / Cisco：https://static0.smartbear.co/support/media/resources/cc/book/code-review-cisco-case-study.pdf
- https://linearb.io/resources/software-engineering-benchmarks-report

## 3. 原则

1. **改动大小不决定能不能做**，只决定合并关卡的等级。
2. **硬兜底只有预算**：花费（USD / Token）、CI 分钟、开发轮次、时长。用完走现有的省钱模式（换方案 → 缩小范围 → 废弃）。
3. **能拆就拆**：拆成能单独合并的子需求，每个都更容易自动通过、出问题更容易撤回。拆分是推荐，不是门槛。
4. **分段只为好审阅**：提交按建议大小切段，但超出建议不再报错、不再废弃。
5. **规则可预期**：用户在对话草稿卡上就能看到「这个需求预计需要你确认后合并」。

## 4. 改动大小怎么算

沿用 `gov/ChangeSize`（真实 diff 行 × `sizeWeights`），调整权重默认值：

| 文件类型 | 权重 | 说明 |
|---|---|---|
| 测试文件（`**/*.test.*`、`**/*.spec.*`、`tests/**`、`__tests__/**`） | 0.5 | 测试多是好事，但也要有人看 |
| 纯删除的行 | 0.5 | 删除比新增风险低（新增：按文件统计删除行，单独乘权重） |
| 翻译文件（`**/locales/**`、`**/i18n/**` 的 JSON / YAML） | 0.1 | 已有 |
| 文档（`docs/**`、`*.md`） | 0.25 | 已有 |
| lock 文件、生成文件（`**/generated/**`、`**/*.generated.*`） | 0 | 已有；生成文件需要 CI 重新生成并比对一致（§10） |
| 其他 | 1 | |

`maxExemptLines`（打折文件原始行合计上限，超出部分按 1 计）保留，防止把大量手写逻辑塞进打折路径。

## 5. 分级合并关卡

### 5.1 等级

按 **PR 加权行数** 和 **风险等级**（`Risk.classify` 的 low / mid / high，按实际改动文件重算）取较高的那一档：

| 等级 | 加权行数（默认） | 风险 | 合并前要过的关 |
|---|---|---|---|
| **G0 小改动** | ≤ 200 | 低 | 现有关卡（CI、验收、发布检查）→ 自动合并 |
| **G1 较大** | 200–400 | 中，或低风险但行数在此区间 | 加一轮评审委员会（换一组评审模型 / 角色）、覆盖率不降且新增代码有测试；全过 → 自动合并 |
| **G2 大** | 400–1000 | 高，或行数在此区间 | G1 的全部 + 安全审计（`SEC_AUDIT` 强制加入流程）+ 负责人一键确认 |
| **G3 很大** | > 1000 | 任意 | G2 的全部 + 灰度发布按高风险处理（更长观察、更严指标）；规划时强烈建议拆成子需求 |

阈值来自：SmartBear 200 / 400 行（审阅效果拐点）、Google「小改动」约 100 行、1000 行太大、LinearB AI PR 中位数 89 行。因为按加权行计（测试、删除打折），实际能放进 G0 的改动比 200 行原始 diff 更多。

### 5.2 和自治等级的关系

| 自治等级 | G0 | G1 | G2 | G3 |
|---|---|---|---|---|
| L1（只做低风险） | 负责人确认 | 负责人确认 | 负责人确认 | 负责人确认 |
| L2（低 / 中） | 自动 | 自动（加评审） | 负责人确认 | 负责人确认 |
| L3（低 / 中 / 高） | 自动 | 自动（加评审） | 自动（加评审 + 安全审计），**高风险仍要确认** | 负责人确认 |

- 「禁区」（`forbidden`）不变：任何等级都不自动执行。
- 自治等级本身决定能不能**开工**（现有 `autonomyAllows`）；分级关卡决定能不能**自动合并**。L1 项目里中风险需求现在会被废弃，新规则下可以做完、开 PR、等确认（D5）。
- 负责人确认是「一键」：看到改动摘要、规模、测试 / 评审结果、PR 链接，点「确认合并」或「退回修改」（附意见，走现有退回修改流程）。

### 5.3 在流程里的位置

- 规划后预估等级（按 `estimates`），写进任务流程计划；G1+ 在组装时加对应步骤（第二轮评审、安全审计），和 098d684 的「组装时一次决定」一致。
- 开发完成后按**实际 PR** 重算等级（`prSize` + 实际文件风险）。比预估高就补加步骤；比预估低不减（已经组装的步骤照常执行）。
- 「负责人确认」是验收之后、合并之前的新步骤 `MERGE_APPROVAL`（执行方式 `system`，等待人工）。任务状态停在「交付未上线」前，显示「等你确认合并」。超过 `gate.approvalTimeoutHours`（默认 72 小时）没人处理就提醒；不会自动合并，也不会自动废弃（D7）。
- `diff_within_limits` 不再是验收硬检查，改成「改动规模：G2（加权 620 行），需要确认后合并」这样的信息项。

## 6. 分段与上限改成建议

- `release.maxChangeLines / maxFiles` 改名语义为「每次提交的建议大小」（配置键保留，新增 `release.segmentHardLimit = false`，默认不硬拒）。
- 开发助手提示词：「每段尽量不超过 X 个文件、Y 行，方便审阅；这一段已用 A / B」。超出建议**不报错**，只记录。
- 规划仍按建议大小分段（`repack()` 不变，保留 `planSplitRatio` 一层余量）。`splitCap` 不再导致废弃：段数超过 `splitCap` 时优先拆成子需求（§7），拆不了也照常做，只是等级更高。
- `change_too_large` 异常不再产生。自动修复序列里的「收缩 / 拆分」只在开发助手单次输出被截断（`OUTPUT_LINE_CAP`，这是模型输出长度限制，仍是硬限制）时使用。
- `exceedsBudget()` 只用于**花费 / 时间 / 步数预算**用完（改名 `budgetExhausted`），废弃原因 `exceeds_budget` 不再因为改动大小出现。
- v1 的「子需求按 `单次上限 × splitCap × 0.6` 装」取消。

## 7. 父需求 + 子需求（好审阅、好撤回）

### 7.1 什么时候拆

规划助手预估等级 ≥ G2，或段数 > `splitCap` 时，**建议**拆成子需求：

- 能拆成每个 ≤ G1 的、能单独合并的子需求 → 拆（多数自动合并，少数确认）；
- 拆不开（改动强耦合，例如一次重命名牵连全仓）→ 不拆，整体走高等级关卡。

`EpicPlanner.validate()` 做确定性检查：依赖无环、子需求之间不改同一个文件（共享文件归依赖链最前面的子需求）、逐个算等级和风险。**不再因为子需求太大而报错**，只是标出它的等级。

### 7.2 子需求怎么做

- 每个子需求是普通任务（`task.kind = child`，`parent_task_id` 指向父需求），各自组装流程、开自己的 PR、各自按等级过关。
- 基于最新主干开分支，依赖合并后再开工，默认严格顺序。
- 没做完的用户可见功能藏在功能开关后面（技术栈画像登记开关方式）；最后一个收尾子需求打开开关并删掉开关代码。
- 子需求跳过 PRD 评审（继承父需求），只校验自己的验收条件可验证。

### 7.3 进度交接（功能清单 + 进度文件）

参考 Anthropic 长时运行助手的做法：父需求有一份 `epic_plan`（按版本追加），内容是子需求清单（每项的范围、验收、状态、PR）、做完了什么、下一步、做过的决定、功能开关。每个子需求开始时读、合并后系统追加新版本；开发助手可以在最后一段给出 `handoff`（新决定、给后面的提醒）。可选同步到仓库 `docs/plans/<id>.md`（默认关）。

### 7.4 父需求状态与失败处理

- 新状态 `EPIC`：`PLANNING → EPIC → ACCEPTANCE`（全部合并后整体验收）`→ DELIVERED`。
- 子需求测试未过 / 评审不通过：只返修这个子需求。
- 子需求花费预算用完被废弃：父需求重新规划剩下的子需求（最多 2 次）；再失败父需求废弃，已合并的部分在开关后面不影响线上。
- 负责人退回某个子需求：按意见返修这个子需求。

## 8. 数据与配置

### 8.1 治理配置（新增 `gate.*`、`epic.*`，调整 `release.*`）

| 配置 | 默认 | 含义 |
|---|---|---|
| `gate.tiers` | `[{name:G0,maxLines:200},{name:G1,maxLines:400},{name:G2,maxLines:1000},{name:G3}]` | 等级与加权行数阈值 |
| `gate.riskTier` | `{low:G0, mid:G1, high:G2}` | 风险对应的最低等级 |
| `gate.autoMerge` | `{L1:[], L2:[G0,G1], L3:[G0,G1,G2]}` | 各自治等级下可以自动合并的等级；不在列表里的需要确认 |
| `gate.highRiskAlwaysApprove` | true | 高风险无论等级都要确认 |
| `gate.extraReview` | `{G1:"second_committee", G2:"second_committee+sec_audit", G3:"second_committee+sec_audit+strict_canary"}` | 各等级加的检查 |
| `gate.minCoverageDelta` | 0 | G1+ 覆盖率不能下降（百分点） |
| `gate.approvalTimeoutHours` | 72 | 等确认超时提醒 |
| `release.segmentHardLimit` | false | 每段大小超出建议时是否硬拒（默认否） |
| `release.sizeWeights` | 增加测试 0.5 | 见 §4 |
| `release.deletionWeight` | 0.5 | 纯删除行权重 |
| `epic.enabled / maxParallel / maxReplans` | true / 1 / 2 | 拆分开关、并行数、重新规划上限 |
| `epic.suggestFromTier` | G2 | 预估达到这个等级就建议拆 |
| 删除 | `epic.childFillRatio`、`epic.confirmAbove`、`epic.maxChildren` 作为硬门槛 | 子需求数量不再需要确认（超过 30 个时提示，参考 `/batch` 5–30 单元） |

### 8.2 数据表（Flyway 草稿 `V19__merge_gate_and_epic.sql`）

```sql
ALTER TABLE task ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'single';   -- single / epic / child
ALTER TABLE task ADD COLUMN child_seq INTEGER;
ALTER TABLE task ADD COLUMN gate_tier VARCHAR(4);                         -- 实际 PR 的等级 G0..G3
CREATE INDEX ix_task_parent ON task(parent_task_id);

CREATE TABLE merge_approval (                 -- 负责人一键确认
  id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL REFERENCES task(id),
  tier VARCHAR(4) NOT NULL, head_sha VARCHAR(64) NOT NULL,   -- 确认绑定到具体提交，之后有新提交要重新确认
  summary TEXT NOT NULL,                       -- 规模、风险、检查结果快照（JSON）
  status VARCHAR(12) NOT NULL,                 -- pending / approved / returned / expired
  decided_by VARCHAR(100), decided_at VARCHAR(40), note TEXT,
  created_at VARCHAR(40) NOT NULL, workspace_id VARCHAR(64)
);

CREATE TABLE epic_plan (
  id VARCHAR(64) PRIMARY KEY, epic_id VARCHAR(64) NOT NULL REFERENCES task(id), version INTEGER NOT NULL,
  content TEXT NOT NULL, summary_md TEXT NOT NULL, author VARCHAR(100) NOT NULL, created_at VARCHAR(40) NOT NULL,
  workspace_id VARCHAR(64), UNIQUE (epic_id, version)
);
CREATE TABLE epic_child_dep (epic_id VARCHAR(64) NOT NULL, child_id VARCHAR(64) NOT NULL, depends_on VARCHAR(64) NOT NULL, PRIMARY KEY (child_id, depends_on));
-- postgresql/V19__merge_gate_rls.sql：三张新表按 workspace_id 加行级隔离（同 V17 写法）
```

### 8.3 接口

- `GET /api/tasks/{id}/plan` 增加 `gate`：`{ estimatedTier, actualTier, weightedLines, risk, autoMerge, needs: ["second_committee","sec_audit","approval"] }`。
- `GET /api/approvals?status=pending`：等确认的合并列表（工作区管理员 / 项目负责人）。
- `POST /api/tasks/{id}/merge-approval`：`{ decision: "approve" | "return", note, headSha }`，`headSha` 必须等于当前 PR 头提交；记审计 `MERGE_APPROVED` / `MERGE_RETURNED`。
- `GET /api/tasks/{id}/epic`：父需求的子需求清单、进度、最新 `epic_plan`。

### 8.4 引擎

- `onPlanning`：删掉 `exceedsBudget` 因改动大小废弃的分支；按预估等级写进流程计划，必要时生成父需求。
- `Assembler`：按等级加步骤（第二轮评审、安全审计、`MERGE_APPROVAL`）。
- `onDev`：超出建议大小不再走 `change_too_large`，只记录；只有单次输出截断才收缩。
- 验收 L1：`diff_within_limits` 改为信息项；新增 `gate_checks`（按等级要求的检查是否都过）。
- 合并前（`onDelivered` 合并之前）：`gate_tier` 需要确认且没有对应 `head_sha` 的 `approved` 记录 → 停下等待，生成 `merge_approval`；有新提交则旧确认作废。
- `onAnomaly`：`change_too_large` 的「用完就废弃」分支删除；预算用完照旧走省钱模式。
- v1 / v2 引擎共用同一套等级计算和合并前检查。

## 9. 日语多语言任务在新规则下

需求：全站支持日语。改动包括：语言切换与加载（约 6 个文件）、各模块组件把写死的中文换成 `t('key')`（约 60 个组件、每个 5–15 行）、每个模块的 `ja.json`（合计约 3000 行）、测试。

1. 规划预估：加权 ≈ 组件改动 600 行 × 1 + 翻译 3000 行 × 0.1 + 测试 200 行 × 0.5 ≈ 1000 行，等级 G3，建议拆。
2. 拆成 5 个子需求（按模块，严格顺序）：
   - ① 多语言基础：加载、切换、功能开关 `i18n_ja`（加权约 90 行，G0，自动合并）；
   - ② 课程模块（约 15 个组件 + 翻译，加权约 220 行，G1，加评审后自动合并）；
   - ③ 学员与成绩模块（约 20 个组件，加权约 300 行，G1）；
   - ④ 管理后台（约 25 个组件，加权约 450 行，**G2**，加安全审计，**需要负责人确认**）；
   - ⑤ 收尾：打开开关、删掉开关代码、整体验收（G0）。
3. 结果：任务完成，不再废弃；L2 项目里只有子需求 ④ 需要一键确认。用户如果不想确认，可以让规划把 ④ 再拆成两个 G1。
4. 花费兜底：父需求总预算按子需求预估分配，留 15% 给返修；用完才停。

## 10. 批量机械改动：脚本 / 生成器路线

- 翻译、批量重命名、按模板生成：子需求可以标 `mode: generator`，提交生成脚本 + 生成结果。生成结果放在权重 0 / 低权重路径，CI 重新运行脚本并比对一致，不一致即失败（防止夹带手写逻辑）。
- 好处：等级按脚本和少量手写改动算，更容易落在 G0 / G1。
- 脚本受 `PathPolicy` 内容检查（不读密钥、不联网、不执行子进程）。

## 11. 界面与文案

### 11.1 任务详情

- 「合并关卡」卡片：「改动规模：加权 620 行（测试按一半、翻译按十分之一计）· 风险：中 → 等级 G2」，列出要过的检查和状态。
- 等确认时顶部提示：**「改动较大，合并前需要你确认」**，按钮「查看改动」「确认合并」「退回修改」。
- 父需求：进度「已合并 3 / 5」、父流程图（与总览「交付流水线」同一样式，每个子需求一个流程步骤）、拆分计划、功能开关。

### 11.2 总览与对话

- 总览新增「等你确认」计数，点开是等确认的合并列表。
- 对话草稿卡：「预计改动较大（等级 G2），做完后需要你确认再合并」；预估 G3 时：「改动很大，建议拆成约 5 个子需求，大部分可以自动合并」。

### 11.3 文案表（按 `copy-guide.md`）

| 场景 | 文案 |
|---|---|
| 等确认 | 改动较大，合并前需要你确认 |
| 确认卡说明 | 已通过测试、两轮评审和安全审计。确认后自动合并并按发布时间上线。 |
| 退回 | 已退回修改，执行助手会按你的意见返修 |
| 新提交使确认失效 | 确认后又有新的提交，需要重新确认 |
| 超时提醒 | 这个合并已经等你确认 3 天了 |
| 时间线 `GATE_TIER` | 改动规模：等级 G2（加权 620 行），合并前需要确认 |
| 时间线 `EPIC_SPLIT` | 需求较大，拆成 5 个子需求按顺序完成，方便审阅和撤回 |
| 时间线 `EPIC_CHILD_MERGED` | 子需求 2 / 5「课程模块」已合并 |
| 建议大小 | 这一段已改 18 个文件、120 行，超过建议大小（20 个文件 / 100 行），不影响继续，会提高合并关卡等级 |
| 预算用完 | 本任务的花费已用 $2.00 / 上限 $2.00，系统会先换个做法、再缩小范围 |

新增术语写进 `apps/web/src/copy/terms.ts`：**合并关卡**、**负责人确认**、**子需求 / 父需求**、**拆分计划**、**功能开关**、**建议大小**。「G0–G3」只出现在灰色小字和详情里，正文写「小改动 / 较大 / 大 / 很大」。

## 12. 决定项（推荐项加粗）

> v1 的 D1–D12 中，D1（拆分触发）、D7（确认门槛按子需求个数）、v1 §8 的 `childFillRatio` 被本版取代；D2–D6、D8–D12 的大部分沿用，编号已重排。

- **D1 改动大小的作用**：A 仍是硬上限（v1）；**B 只决定合并关卡等级，不决定能不能做**。推荐 B。（取代 v1 原则「不放宽上限」）
- **D2 等级阈值**：**A G0 ≤ 200 / G1 ≤ 400 / G2 ≤ 1000 / G3 > 1000（加权行）**；B 更严 100 / 200 / 400。推荐 A（SmartBear 200–400、Google 1000 太大），项目可在治理配置里收紧。
- **D3 加权规则**：**A 测试 0.5、纯删除 0.5、翻译 0.1、文档 0.25、lock / 生成 0，打折总量受 `maxExemptLines` 限制**；B 不加权。推荐 A。
- **D4 各等级加的检查**：**A G1 加第二轮评审 + 覆盖率不降；G2 再加安全审计 + 确认；G3 再加严格灰度**；B 只用负责人确认。推荐 A：能自动把关的先自动把关，减少打扰。
- **D5 和自治等级的关系**：**A L1 合并前总要确认（中风险需求也可以做完等确认）；L2 自动到 G1；L3 自动到 G2，高风险仍确认**；B 维持现在 L1 直接废弃中风险。推荐 A。
- **D6 每段上限**：**A 改成建议大小，超出只记录、提高等级；`segmentHardLimit` 可选开启**；B 保持硬上限。推荐 A。（取代 v1 的 0.6 叠加余量，只保留 `planSplitRatio`）
- **D7 等确认超时**：**A 72 小时提醒，不自动合并也不自动废弃**；B 超时自动退回。推荐 A。
- **D8 确认绑定提交**：**A 确认绑定 PR 头提交，之后有新提交要重新确认**；B 确认绑定任务。推荐 A：防止确认后又改了东西。
- **D9 拆分触发**：**A 预估 ≥ G2 或段数 > `splitCap` 时建议拆，拆不开就整体走高等级**；B 总是拆；C 不拆。推荐 A。（取代 v1 D1）
- **D10 合并方式**：**A 每个子需求基于最新主干开分支，依赖合并后再开工，默认严格顺序**；B 叠加 PR。推荐 A。（沿用 v1 D2 / D3）
- **D11 进度交接存哪**：**A `epic_plan` 表为准，可选同步仓库 `docs/plans/<id>.md`（默认关）**。推荐 A。（沿用 v1 D4）
- **D12 功能开关**：**A 技术栈画像登记开关方式，第一个子需求加、收尾子需求删；没有开关方式时要求每个子需求都是完整增量**。推荐 A。（沿用 v1 D5）
- **D13 硬兜底**：**A 只有预算（花费 / Token / CI 分钟 / 开发轮次 / 时长）是硬限制，用完走省钱模式；单次输出截断（350 行）保留为技术限制**；B 再加行数上限。推荐 A。
- **D14 子需求预算与重新规划**：**A 父需求预算按子需求预估比例分配、留 15% 机动；子需求失败最多重新规划 2 次；整体验收在全部合并后做一次**。推荐 A。（合并 v1 D8 / D10 / D11）

v1 中继续沿用但不再单列的：子需求不单独做 PRD 评审（v1 D6）、做到一半发现很大就升级成父需求（v1 D9）、总览父需求一行显示进度（v1 D12）。

## 13. 实施计划

| 期 | 内容 | 估计 |
|---|---|---|
| **P0** 不再因为大小废弃 | 删除 `exceedsBudget` 的大小分支和 `change_too_large`「用完就废弃」；`diff_within_limits` 改信息项；每段上限改建议（`segmentHardLimit=false`）；权重增加测试 0.5、删除 0.5；先用「大改动一律负责人确认」兜住风险（最简单的 G2 行为） | 2 天 |
| **P1** 分级关卡 | `gate.*` 配置、等级计算（预估 + 实际 PR）、`task.gate_tier`；组装按等级加第二轮评审 / 安全审计；`MERGE_APPROVAL` 步骤、`merge_approval` 表、确认接口与审计、绑定头提交；自治等级矩阵 | 4 天 |
| **P2** 界面 | 合并关卡卡片、「等你确认」列表与总览计数、一键确认 / 退回、对话草稿卡预估提示、时间线文案、文案守护测试 | 3 天 |
| **P3** 子需求 | `task.kind / child_seq`、`epic_plan`、`epic_child_dep`；`plan.decompose` + `EpicPlanner.validate()`；`EPIC` 状态、顺序放行、进度交接、功能开关与收尾子需求、失败重新规划；父需求页面 | 5 天 |
| **P4** 生成器路线与校准 | `mode: generator` 与 CI 比对；用 30 天真实数据校准阈值（各等级自动合并率、确认等待时长、上线后撤回率），按项目给出建议 | 2 天 |

P0 单独上线就能解决「日语多语言任务被废弃」：任务会做完并等负责人确认合并。

测试要点：大小不再导致废弃（规划 / 开发 / 验收三处）；G0–G3 等级计算与加权（测试、删除、翻译、生成文件）；自治等级矩阵；确认绑定头提交、新提交使确认失效；预算用完仍走省钱模式；子需求顺序合并后整体验收；日语多语言回放拆成 5 个子需求、只有 ④ 需要确认。

## 14. 不做的事

- 不按需求临时放宽预算（预算仍是硬兜底）。
- 不做叠加 PR、不做跨仓库拆分。
- 不允许执行助手自己确认合并（和 Copilot「不能批准自己的 PR」一致）。

## 附：P0 实施记录（2026-10-18）

- 大小不再决定任务能不能做：规划预估超出（`exceedsBudget`）只记录「改动较大」事件；开发实测超出建议大小只记录「超过建议大小」，继续提交；自动修复用完后接受当前大小继续（`size_accepted`），不再 `exceeds_budget` 放弃。`release.segmentHardLimit=true` 可恢复旧的硬上限行为。
- `diff_within_limits` / 发布检查 `within_change_limits` 默认只作信息展示，附带合并关卡等级。
- 权重：测试文件 `release.testWeight=0.5`，纯删除行 `release.deletionWeight=0.5`，语言包 0.1、文档 0.25、锁文件 / 生成文件 0 不变。v1 文档里的 0.6 叠加系数从未落到代码，只保留 `planSplitRatio` 一层余量。
- 合并关卡 `gate`（G0–G3，按加权行 200 / 400 / 1000 与风险取高；自治矩阵 L1 无自动合并、L2 自动合并 G0–G1、L3 自动合并 G0–G2；高风险总要确认）。需要确认时任务停在 DELIVERED 合并前，写 `merge_approval`（V19，绑定 PR 头提交），新提交使旧确认作废；72 小时只提醒一次，不自动合并 / 放弃。负责人可「确认合并」或「退回修改」（必须写意见，回到开发返修）。v1 / v2 共用同一合并路径。
- 接口：`GET /api/tasks/{id}/merge-gate`、`POST /api/tasks/{id}/merge-approval`（管理员，body `{decision: approve|return, headSha, note}`）、`GET /api/merge-approvals`。
