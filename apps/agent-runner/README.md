# x7x24-runnerd（agent 服务器上的 runner 守护进程）

后端在 `AGENT_RUNTIME=remote-docker` 时，经私有网络调用本服务（默认本机 `127.0.0.1:8790`，生产请设 `RUNNER_BIND` / `AGENT_RUNNER_URL`）。每次只跑一个 job。

## 协议（全部请求需 HMAC 签名）
签名头：`X-X7-Timestamp`（秒，允许 ±300 秒）、`X-X7-Nonce`（随机 hex，600 秒内不可重放）、`X-X7-Signature` = HMAC-SHA256(key, `METHOD\nPATH?QUERY\nTS\nNONCE\nsha256hex(body)`)。

| 接口 | 说明 |
|---|---|
| `POST /v1/jobs` | 提交 job（`jobKey` 幂等）；忙时 409 `BUSY` |
| `GET /v1/jobs/{id}` | 状态 QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/TIMED_OUT，结束后带 `result` |
| `GET /v1/jobs/{id}/logs?from=N` | 已脱敏日志事件 |
| `POST /v1/jobs/{id}/cancel` | `docker kill`（审查作业先发 SIGINT 让 ocr 保存会话，30 秒后强制结束） |
| `POST /v1/review` | 逐行审查作业：在沙箱里跑 open-code-review v1.13.5（`ocr review --audience agent --format json`），返回 ocr 的 JSON；状态 / 取消走上面的 jobs 接口 |

## 行为
1. 宿主侧 `git clone`（令牌只经环境变量传给 git，不写盘、不进容器），检出基线。
2. `docker run x7x24-agent:claude`：uid 10001、`--memory/--memory-swap` 同值、`--cpus`、`--pids-limit 512`、`--cap-drop ALL`、`no-new-privileges`；挂载仓库、只读 prompt、共享依赖缓存（`~/.m2`、`~/.npm`、`~/.gradle`，flock 串行）。
3. `claude --bare -p ... --output-format stream-json --dangerously-skip-permissions --disallowedTools WebSearch,WebFetch`。`--dangerously-skip-permissions` **只允许在这个一次性容器里使用**；Phase 3 换成工具白名单。
4. 结束后对比基线：已跟踪文件的改动（含已提交与未提交）回传；未跟踪文件、二进制、>1MB 文件只报告路径；删除工作目录。runner 不推送分支——后端仍按原有 PathPolicy / 范围检查 / 提交 / PR 流程处理。

## 逐行审查（open-code-review）
- 官方 skill 原文：`ocr/skills/open-code-review/SKILL.md`（Apache-2.0，见 `ocr/LICENSE`）。
- 二进制：`sudo ./install-ocr.sh` 下载 v1.13.5 linux-amd64 并校验 sha256，装到 `/opt/x7x24-agent/bin/ocr-v1.13.5`；runnerd 每次运行前再校验一次 sha256，不匹配就拒绝审查。
- 模型地址、密钥、模型名由后端随请求发来，只经环境变量 `OCR_LLM_URL/TOKEN/MODEL/PROTOCOL` 按名字传进容器，不写盘、不进日志、不回传。
- 每个任务节点一个持久目录（`RUNNER_OCR_WORK/<key>/{repo,home,out}`），用来 `--resume`；保留 `RUNNER_OCR_KEEP_DAYS` 天。

## 部署（尚未执行）
```bash
useradd -r -u 10001 -M -s /usr/sbin/nologin x7runner   # 加入 docker 组 = 等同 root，见设计文档的风险说明
usermod -aG docker x7runner
install -d -o x7runner -g x7runner -m 700 /opt/x7x24-agent/jobs /opt/x7x24-agent/cache
install -m 755 runnerd.py /opt/x7x24-agent/runnerd.py
chgrp x7runner /opt/x7x24-agent/model.env && chmod 640 /opt/x7x24-agent/model.env
install -m 640 -g x7runner runnerd.env.example /opt/x7x24-agent/runnerd.env   # 填入 RUNNER_HMAC_KEY
install -m 644 x7x24-runnerd.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now x7x24-runnerd
```
x7runner 与容器内 agent 用同一个 uid 10001，clone 出的工作目录容器可直接写。

## 测试
`python3 -m unittest discover -p 'test_*.py' -v`（用 `fake_docker.py` 模拟容器，`fake_ocr.py` 模拟 ocr，`testdata/ocr-*.json` 是真实 ocr v1.13.5 输出的录制）。
