# 逐行审查（CODE_REVIEW / 技能 ocr-review@1）

> 直接使用 [alibaba/open-code-review](https://github.com/alibaba/open-code-review)（Apache-2.0）官方的 `open-code-review` agent skill 和 ocr 程序，版本钉死 **v1.13.5**。
> skill 原文原样放在 `apps/agent-runner/ocr/skills/open-code-review/SKILL.md`，署名见根目录 `NOTICE`、`THIRD_PARTY.md`。
> 旧的「Java 重写 + 内置规则 + 按风险排序最多 12 组 + 每组检查点」已整体删除。

## 位置与组装

```
DEV → QA（CI + 附加检查）→ CODE_REVIEW 逐行审查 → SEC_AUDIT 安全审计（若组装）→ ACCEPTANCE 验收 → RELEASE
```

- 节点目录：`Nodes.CODE_REVIEW`（交付模块、评审类，requires QA、before ACCEPTANCE），预设 `Catalog.STAGE_PRESETS["CODE_REVIEW"]`（anchorPhase ACCEPTANCE，artifactKey `codeReview`，技能 `ocr-review@1`）。
- 只从 G1 起组装：`PlanService.mandatory` 调用 `CodeReviewGate.reason(size, risk, governance)`，命中时作为治理必选节点加入（source = governance，不能被 AI 调整移除）。
  - 组装在规划时，还没有 PR，所以用**预估等级** = max（草稿规模档位，风险档位）：规模 XS / S → G0、M → G1、L → G2、XL → G3；风险档位与合并关卡共用 `gate.riskTier`（默认 mid → G1、high → G2）。预估等级 ≥ `codeReview.minTier`（默认 G1）才组装。
  - 节点执行时再按实际 PR 算一次合并关卡等级（`Engine.mergeGate` → `MergeGate.evaluate`，加权行数 + 风险），记进结果（`tier` / `weightedLines`），观察期用来对照预估是否准确。
  - 设计里「G1 加第二轮委员会评审」由逐行审查**替代**：G1 不再加第二轮评审（目前分级 P0 只做了合并关卡，第二轮评审尚未实现，以后实现分级组装时 G1 只需确认 CODE_REVIEW 已组装）；G2 / G3 仍加安全审计与合并前确认，逐行审查的发现会一并带给它们。
  - 已知限制：预估偏小（G0）但实际 PR 到了 G1 的任务不会补加这一步（流程在规划时钉住）；手动创建的任务（不经过对话规划）只有项目 `nodePolicy.mandatory` 含 `CODE_REVIEW` 时才会加。
- 组装器把 CODE_REVIEW 放在测试之后的最前面（`Assembler` 里把它移到必选节点最后插入），所以总在 SEC_AUDIT 之前。
- 组装时一次决定；组装了就一定执行。没有可审查的代码文件时直接通过并写明原因。

## 执行（runner 沙箱）

1. 后端 `Engine.onCodeReview` 从委员会里挑审查模型（避开写代码的模型），按顺序得到端点链：主模型 → `codeReview.fallbackModels`（默认 `deepseek-chat`）。没有可用模型时停下（`code_review_failed`，`error=no_review_model`）。
2. `RemoteOcrRunner` 调 runnerd `POST /v1/review`（HMAC 签名，经私有网络）。请求里带仓库、`baseRef`、`headSha`、背景说明、模型 `{url, token, model, protocol}`、可选 `resume`、Token 预算、超时、并发。runner 忙（409）时最多等 15 分钟。
3. runnerd 在每个 reviewKey（任务 + 节点）固定的目录里 fetch / clone 仓库并检出 headSha，然后 `docker run`（uid 10001、限内存 / CPU、cap-drop ALL、no-new-privileges）执行：
   ```
   ocr review --audience agent --format json --output /out/<job>.json \
     --from origin/<baseRef> --to <headSha> --timeout <min> --concurrency <n> \
     [--background …] [--max-tokens-budget <n>] [--resume <session>]
   ```
   模型密钥只通过环境变量 `OCR_LLM_URL / OCR_LLM_TOKEN / OCR_LLM_MODEL / OCR_LLM_PROTOCOL` 按名字传进容器，不出现在命令行、日志或返回结果里；任务结束后从内存里的 job 描述中删除。
4. ocr 自己选文件、分组、调用模型、复核，输出 JSON。runnerd 去掉 `thinking`、限制意见条数、脱敏字符串后原样返回。
5. 会话保存在同一 reviewKey 的 `$HOME/.opencodereview/sessions`，所以「重试」和换备用模型都用 `--resume` 接着审，已审完的文件不重审。目录保留 `RUNNER_OCR_KEEP_DAYS`（默认 7）天。

## 结果与判定

- 解析 `OcrResult`：`comments[]` → 发现（去重、规范化）；`start_line` / `end_line` 都为 0 的是**没定位到行的意见**，单独列出，**永不阻断**。
- 阻断规则不变：定位到行，且 critical，或 high 且类别为 bug / security。
- `codeReview.blocking=false` 为观察期：只记录「按规则应退回」，不退回。
- 覆盖率来自 `manifest.coverage`：审完 / 没审的文件、没审原因（超时、超出 Token 预算等）。只因预算没审完 → 视为完成，结论标「部分审查」。
- 同一个 head 已经通过过（检查点 `cr:d:`）→ 直接通过，不再跑。
- 每次模型尝试都在审查节点下记一次模型调用（source `ocr`），含 Token 与花费。

## 没审完

- 主模型失败（ocr 退出非 0 或 status 为 failed / partial）→ 带上会话换下一个模型 `--resume`。
- 全部失败 → 写 `CODE_REVIEW_FAILED`（审完 / 总文件、失败文件、尝试过的模型）并停下。检查点 `cr:s:<task>:<iter,node>` 保存 head、会话、模型、用量和结果（沿用 `task_step_checkpoint` 表，不需要迁移）。
- 卡片两个按钮：
  - 「重试」：同一 head 时用保存的会话 `ocr --resume`；head 变了就从头审。
  - 「跳过并继续」：只有负责人 / 管理员能点，记审计；用保存的结果，把没审完的文件记成「没审」，结论标「部分审查」。
- 暂停任务会取消 runner 上的作业（先 SIGINT 让 ocr 保存会话，30 秒后强制结束），恢复后接着审。

## 配置（`governance.codeReview`）

| 字段 | 默认 | 说明 |
|---|---|---|
| enabled | true | 总开关 |
| blocking | false | 是否退回修改（false = 观察期） |
| minTier | G1 | 预估等级达到才组装 |
| fallbackModels | [deepseek-chat] | 主模型失败后依次尝试 |
| maxTokens | 600000 | 传给 `--max-tokens-budget` |
| timeoutMin | 15 | 传给 `--timeout` |
| wallClockMin | 60 | 后端等一次审查的上限 |
| concurrency | 8 | 传给 `--concurrency` |

旧字段（分组上限、调用策略、项目 `codeReviewMaxGroups`）读到时忽略。

## 部署（runner 主机）

1. `sudo apps/agent-runner/install-ocr.sh`（下载 v1.13.5 linux-amd64 并校验 sha256，装到 `/opt/x7x24-agent/bin/ocr-v1.13.5`）。
2. 更新 `runnerd.py`，按需在 `runnerd.env` 设置 `RUNNER_OCR_BIN`、`RUNNER_OCR_WORK`、`RUNNER_OCR_KEEP_DAYS`。
3. `systemctl restart x7x24-runnerd`。

后端没有新环境变量：模型地址和密钥来自工作区的模型配置（provider 的 baseUrl 与 key）。需要登记 `deepseek-chat` 作为备用模型。没有数据库迁移。

## 离线 / CI

没有接 runner 或没有真实模型时，用 `RuleOcrRunner`：按 ocr 的 JSON 格式给出确定性的结果（明文密钥 → critical/security，`console.log` → low），并在内存里模拟会话，保证测试和离线模式可跑。
