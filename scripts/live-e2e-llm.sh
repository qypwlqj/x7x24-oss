#!/usr/bin/env bash
# LLM 真实联调：DeepSeek（OpenAI 兼容）驱动的 agent 对真实 GitHub 仓库跑完整闭环。
# 必须显式 LIVE=1，且需要环境变量 GITHUB_TOKEN（repo+workflow）与 LLM_API_KEY；密钥只从环境读取，绝不打印 / 落盘。
# 场景：模板外新功能（roman.js）/ 补 JSDoc + README / 含糊需求（应被要求澄清或废弃）/ 提示词注入。
#   LIVE=1 GITHUB_TOKEN=... LLM_API_KEY=... scripts/live-e2e-llm.sh
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "${LIVE:-}" != "1" ]; then echo "跳过：真实联调会写入 GitHub 仓库并产生 LLM 费用，需显式 LIVE=1"; exit 0; fi
: "${GITHUB_TOKEN:?需要环境变量 GITHUB_TOKEN}"; : "${LLM_API_KEY:?需要环境变量 LLM_API_KEY}"
: "${TARGET_REPO:?需要显式 TARGET_REPO=owner/disposable-repo}"
export TARGET_REPO PORT="${PORT:-18788}" DATA_DIR="${DATA_DIR:-$(mktemp -d)/x7data}"
export ADAPTER_MODE=real ADAPTER_DRY_RUN=false OBSERVE_HOURS="${OBSERVE_HOURS:-0.005}" INTAKE_MS="${INTAKE_MS:-5000}" TICK_MS="${TICK_MS:-2000}"
export LLM_BASE_URL="${LLM_BASE_URL:-https://api.deepseek.com}" LLM_MODEL="${LLM_MODEL:-deepseek-chat}"
# 评审席位：model[@temperature]；末席兼任仲裁。席位 1/2 同模型不同温度与人设，末席用 reasoner（独立性局限见 docs/llm.md）
export LLM_JUDGE_MODELS="${LLM_JUDGE_MODELS:-deepseek-chat@0.2,deepseek-chat@0.7,deepseek-reasoner}"
export WEB_DIR="$PWD/no-web"
JAR=apps/server-java/target/x7x24-server.jar
if [ ! -f "$JAR" ]; then (cd apps/server-java && mvn -q -DskipTests package); fi
python3 scripts/live_e2e_llm.py --base x --repo "$TARGET_REPO" --reset-only    # 只关闭遗留的 open 信号 Issue，不删除任何文件
mkdir -p "$DATA_DIR"; LOG="$DATA_DIR/server.log"
java -jar "$JAR" >"$LOG" 2>&1 &
PID=$!; trap 'kill $PID 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do curl -sf "localhost:$PORT/api/health" >/dev/null && break; sleep 1; done
echo "服务已启动 pid=$PID port=$PORT repo=$TARGET_REPO"
python3 scripts/live_e2e_llm.py --base "http://localhost:$PORT" --repo "$TARGET_REPO" --out "${OUT:-docs/live-e2e-llm-result.json}"
