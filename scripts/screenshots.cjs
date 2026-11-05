/* 截图（需 Java 后端已启动并带有真实运行数据，默认 http://127.0.0.1:18787，Spring 同端口托管前端静态资源）。
   用法：BASE=http://127.0.0.1:18787 node scripts/screenshots.cjs   依赖 playwright-core + 系统 Chrome */
if (process.env.REVIEWED_DEMO !== '1') throw new Error('Use a local fixed mock instance and REVIEWED_DEMO=1; review every image before sharing.');
const path = require('path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE || 'playwright-core');
const OUT = path.join(__dirname, '..', 'docs', 'screenshots') + path.sep; const BASE = process.env.BASE || 'http://127.0.0.1:18787';
(async () => {
  const b = await chromium.launch({ executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  const errs = []; const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } }); const p = await ctx.newPage();
  p.on('pageerror', (e) => errs.push('pageerror ' + e.message)); p.on('console', (m) => { if (m.type() === 'error') errs.push('console ' + m.text()); });
  const go = async (hash) => { await p.goto(`${BASE}/${hash}`, { waitUntil: 'load' }); await p.waitForTimeout(1500); };
  await go('overview'); await p.screenshot({ path: OUT + 'overview.png', fullPage: true });
  await p.click('[data-testid=node-dev]', { timeout: 5000 }); await p.waitForTimeout(800); await p.screenshot({ path: OUT + 'node-drawer.png' });
  await go('tasks'); await p.screenshot({ path: OUT + 'tasks.png', fullPage: true });
  await p.click('tbody tr:nth-child(1)'); await p.waitForSelector('[data-testid=task-detail]'); await p.waitForTimeout(800); await p.screenshot({ path: OUT + 'task-detail.png', fullPage: true });
  await go('governance'); await p.screenshot({ path: OUT + 'governance.png', fullPage: true });
  await go('budget'); await p.screenshot({ path: OUT + 'budget.png', fullPage: true });
  await go('audit'); await p.screenshot({ path: OUT + 'audit.png' });
  console.log(JSON.stringify({ errors: errs }));
  await b.close();
})();
