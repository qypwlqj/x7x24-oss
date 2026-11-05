#!/usr/bin/env bash
# macOS 可双击运行；也可在终端执行 ./start.command。
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_PID=""
WEB_PID=""
OPEN_BROWSER=true
CHECK_ONLY=false

cleanup() {
  local status=$?
  trap - EXIT
  for pid in "$SERVER_PID" "$WEB_PID"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  if [[ "$status" -ne 0 && "$status" -ne 130 && "$status" -ne 143 && -t 0 ]]; then
    read -r -p '启动失败，按回车键退出…' _ || true
  fi
  exit "$status"
}
fail() { printf '\n错误：%s\n' "$1" >&2; exit 1; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'printf "\n启动中断，请查看上方错误信息。\n" >&2' ERR

for arg in "$@"; do
  case "$arg" in
    --no-open) OPEN_BROWSER=false ;;
    --check) CHECK_ONLY=true ;;
    --help|-h)
      printf '用法：./start.command [--no-open] [--check]\n'
      printf '默认安装依赖、构建前后端、启动服务并打开看板。\n'
      printf '%s\n' '--no-open：不自动打开浏览器；--check：只检查环境。'
      exit 0 ;;
    *) fail "未知参数：$arg（使用 --help 查看用法）" ;;
  esac
done

cd "$PROJECT_ROOT"
# Finder 启动时补充 Homebrew 路径，并在需要时加载已安装的 nvm。
export PATH="${PATH:-/usr/bin:/bin:/usr/sbin:/sbin}:/opt/homebrew/bin:/usr/local/bin"
if ! command -v node >/dev/null 2>&1 && [[ -s "${NVM_DIR:-$HOME/.nvm}/nvm.sh" ]]; then
  export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
  set +u
  # shellcheck disable=SC1091
  . "$NVM_DIR/nvm.sh" --no-use
  nvm use --silent default >/dev/null 2>&1 || nvm use --silent node >/dev/null 2>&1 || true
  set -u
fi
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  if [[ -n "$JAVA_HOME" ]]; then export JAVA_HOME; fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi

for dependency in node npm java mvn curl; do
  command -v "$dependency" >/dev/null 2>&1 || fail "缺少 $dependency，请按 docs/dev-setup.md 安装后重试。"
done
NODE_VERSION="$(node -p 'process.versions.node')"
node -e 'const [major,minor,patch]=process.versions.node.split(".").map(Number); process.exit(major===24 && (minor>22 || (minor===22 && patch>=1)) ? 0 : 1)' || fail "需要 Node 24.22.1 或更高的 24 LTS 补丁，当前为 $NODE_VERSION。"
JAVA_VERSION="$(java -version 2>&1)"
JAVA_MAJOR="$(printf '%s\n' "$JAVA_VERSION" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ && "$JAVA_MAJOR" -ge 21 ]] || fail '需要 JDK 21 或以上，请检查 JAVA_HOME。'
MAVEN_VERSION="$(mvn -Dstyle.color=never --version 2>/dev/null | sed -n 's/^Apache Maven \([0-9][0-9.]*\).*/\1/p')"
IFS=. read -r MAVEN_MAJOR MAVEN_MINOR _ <<< "$MAVEN_VERSION"
[[ "${MAVEN_MAJOR:-}" =~ ^[0-9]+$ && "${MAVEN_MINOR:-}" =~ ^[0-9]+$ ]] || fail '无法读取 Maven 版本。'
[[ "$MAVEN_MAJOR" -gt 3 || ( "$MAVEN_MAJOR" -eq 3 && "$MAVEN_MINOR" -ge 9 ) ]] || fail "需要 Maven 3.9 或以上，当前为 $MAVEN_VERSION。"

export PORT="${PORT:-8787}"
[[ "$PORT" =~ ^[0-9]{1,5}$ ]] || fail 'PORT 必须是 1–65535 的整数。'
PORT=$((10#$PORT))
[[ "$PORT" -ge 1 && "$PORT" -le 65535 ]] || fail 'PORT 必须在 1–65535 之间。'
export ADAPTER_MODE="${ADAPTER_MODE:-offline}"
export ADAPTER_DRY_RUN="${ADAPTER_DRY_RUN:-true}"
case "$ADAPTER_MODE" in offline|real) ;; *) fail 'ADAPTER_MODE 只能是 offline 或 real。' ;; esac
case "$ADAPTER_DRY_RUN" in true|false) ;; *) fail 'ADAPTER_DRY_RUN 只能是 true 或 false。' ;; esac
if [[ "$ADAPTER_MODE" == real && -z "${GITHUB_TOKEN:-}" ]]; then
  fail 'real 模式需要通过环境变量配置 GITHUB_TOKEN。'
fi
export API_PORT="${API_PORT:-18787}"
[[ "$API_PORT" =~ ^[0-9]{1,5}$ ]] || fail 'API_PORT 必须是 1–65535 的整数。'
API_PORT=$((10#$API_PORT))
[[ "$API_PORT" -ge 1 && "$API_PORT" -le 65535 && "$API_PORT" -ne "$PORT" ]] || fail 'API_PORT 需要是有效端口，且与 PORT 不同。'
for listener in "$PORT" "$API_PORT"; do
  if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$listener" -sTCP:LISTEN >/dev/null 2>&1; then
    fail "端口 $listener 已被占用。请停止原服务，或设置 PORT / API_PORT 后再启动。"
  fi
done
export API_PROXY_TARGET="http://127.0.0.1:$API_PORT"

printf '\n7x24 一键启动\nNode %s · Java %s · Maven %s\n' "$NODE_VERSION" "$JAVA_MAJOR" "$MAVEN_VERSION"
printf '模式：%s · dry-run：%s · 端口：%s\n' "$ADAPTER_MODE" "$ADAPTER_DRY_RUN" "$PORT"
if $CHECK_ONLY; then printf '环境检查通过。\n'; exit 0; fi

printf '\n[1/4] 安装并同步前端依赖…\n'
npm install --no-audit --no-fund
printf '\n[2/4] 构建前端…\n'
npm run build
printf '\n[3/4] 构建 Java 后端…\n'
# 启动流程跳过测试；完整验证使用 npm run verify:all。
mvn -f apps/server-java/pom.xml -DskipTests package

export WEB_DIR="$PROJECT_ROOT/no-web"
export DATA_DIR="${DATA_DIR:-$PROJECT_ROOT/data}"
mkdir -p "$DATA_DIR"
export DATA_DIR="$(cd "$DATA_DIR" && pwd)"
JAR="$PROJECT_ROOT/apps/server-java/target/x7x24-server.jar"
[[ -f "$PROJECT_ROOT/apps/web/.next/standalone/apps/web/server.js" && -f "$JAR" ]] || fail '构建产物缺失，请查看上方构建结果。'
URL="http://localhost:$PORT"
printf '\n[4/4] 启动服务，等待看板就绪…\n'
java -jar "$JAR" --server.address=127.0.0.1 --server.port="$API_PORT" &
SERVER_PID=$!
HOSTNAME=127.0.0.1 node "$PROJECT_ROOT/apps/web/scripts/start-next.mjs" &
WEB_PID=$!
READY=false
for ((attempt=0; attempt<120; attempt++)); do
  kill -0 "$SERVER_PID" 2>/dev/null || fail 'Java 服务提前退出，请查看上方日志。'
  kill -0 "$WEB_PID" 2>/dev/null || fail 'Next.js 服务提前退出，请查看上方日志。'
  if curl --noproxy '*' -fsS --max-time 2 "$URL/api/health" >/dev/null 2>&1; then
    READY=true
    break
  fi
  sleep 1
done
$READY || fail '服务未在等待时间内就绪，请查看上方日志。'
printf '\n看板已启动：%s\n保留此窗口运行；按 Ctrl+C 停止服务。\n' "$URL"
if $OPEN_BROWSER; then
  if [[ "$(uname -s)" == Darwin ]]; then
    open "$URL" || true
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$URL" >/dev/null 2>&1 || true
  fi
fi
while kill -0 "$SERVER_PID" 2>/dev/null && kill -0 "$WEB_PID" 2>/dev/null; do sleep 1; done
fail '服务已停止，请查看上方日志。'
