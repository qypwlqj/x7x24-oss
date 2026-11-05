#!/bin/sh
# 独立验收容器入口。输入（环境变量）：X7_SETUP X7_START X7_URL X7_READY_SEC X7_SPEC_DIR X7_PAGES（逗号分隔路径）
# 输出：/out/report.json /out/shots/*.png /out/a11y/*.txt /out/app.log /out/status.json
set -u
mkdir -p /out/shots /out/a11y /out/artifacts
cd /work || exit 2
status() { printf '{"phase":"%s","code":%s}\n' "$1" "$2" > /out/status.json; }
if [ -n "${X7_SETUP:-}" ]; then sh -c "$X7_SETUP" > /out/setup.log 2>&1 || { status setup $?; tail -n 50 /out/setup.log; exit 3; }; fi
sh -c "$X7_START" > /out/app.log 2>&1 &
APP=$!
i=0; until node -e "fetch(process.argv[1]).then(r=>process.exit(r.status<500?0:1)).catch(()=>process.exit(1))" "$X7_URL"; do
  i=$((i+1)); if [ "$i" -ge "${X7_READY_SEC:-120}" ]; then status start 1; tail -n 50 /out/app.log; kill $APP 2>/dev/null; exit 4; fi; sleep 1; done
echo "app ready: $X7_URL"
mkdir -p /tmp/acc/specs && ln -sf /usr/local/lib/node_modules /tmp/acc/node_modules
if [ -n "${X7_SPEC_DIR:-}" ] && [ -d "/work/$X7_SPEC_DIR" ]; then cp -r "/work/$X7_SPEC_DIR/." /tmp/acc/specs/; fi
cat > /tmp/acc/pw.config.mjs <<CFG
export default { testDir: './specs', retries: 0, workers: 1, timeout: 60000, outputDir: '/out/artifacts',
  reporter: [['json', { outputFile: '/out/report.json' }], ['list']],
  use: { baseURL: process.env.X7_URL, headless: true, screenshot: 'on', locale: 'zh-CN', launchOptions: { args: ['--no-sandbox'] } } };
CFG
code=0
if ls /tmp/acc/specs/*.spec.* >/dev/null 2>&1 || find /tmp/acc/specs -name '*.spec.*' | grep -q .; then
  (cd /tmp/acc && npx playwright test -c pw.config.mjs); code=$?
else echo '{"suites":[],"stats":{"expected":0,"unexpected":0},"note":"no specs"}' > /out/report.json; fi
node /opt/x7/snapshot.mjs || true
kill $APP 2>/dev/null
status tests $code
exit 0
