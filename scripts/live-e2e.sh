#!/usr/bin/env bash
# 真实联调（live e2e）：对真实 GitHub 仓库跑完整闭环。必须显式 LIVE=1；令牌只从环境变量 GITHUB_TOKEN 读取。
# 场景：kill switch → 1 成功闭环（2 个 Issue）→ 2 返修 Loop → 3 禁区拦截；最后校验看板指标与审计哈希链。
# 产生的 Issue / PR / Release 保留在目标仓库，不会删除。
#   LIVE=1 GITHUB_TOKEN=... scripts/live-e2e.sh            # 必须显式指定一次性测试仓库
#   TARGET_REPO=owner/repo PORT=18787 DATA_DIR=/tmp/x7-live scripts/live-e2e.sh
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "${LIVE:-}" != "1" ]; then echo "跳过：真实联调会写入 GitHub 仓库，需显式 LIVE=1"; exit 0; fi
: "${GITHUB_TOKEN:?需要环境变量 GITHUB_TOKEN（classic PAT: repo + workflow）}"
: "${TARGET_REPO:?需要显式 TARGET_REPO=owner/disposable-repo}"
export TARGET_REPO PORT="${PORT:-18787}" DATA_DIR="${DATA_DIR:-$(mktemp -d)/x7data}"
export ADAPTER_MODE=real ADAPTER_DRY_RUN=false OBSERVE_HOURS="${OBSERVE_HOURS:-0.005}" INTAKE_MS="${INTAKE_MS:-5000}" TICK_MS="${TICK_MS:-2000}"
export WEB_DIR="$PWD/no-web"
JAR=apps/server-java/target/x7x24-server.jar
if [ ! -f "$JAR" ]; then (cd apps/server-java && mvn -q -DskipTests package); fi
# 先复位沙盒（关闭遗留 open 信号 Issue、删除先前联调产生的示例文件），再启动服务，避免被误抓取
python3 scripts/live_e2e.py --base x --repo "$TARGET_REPO" --reset-only
mkdir -p "$DATA_DIR"; LOG="$DATA_DIR/server.log"
java -jar "$JAR" >"$LOG" 2>&1 &
PID=$!; trap 'kill $PID 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do curl -sf "localhost:$PORT/api/health" >/dev/null && break; sleep 1; done
echo "服务已启动 pid=$PID port=$PORT repo=$TARGET_REPO data=$DATA_DIR"
python3 scripts/live_e2e.py --base "http://localhost:$PORT" --repo "$TARGET_REPO" --out docs/live-e2e-result.json
