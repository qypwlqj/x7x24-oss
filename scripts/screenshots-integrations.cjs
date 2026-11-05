/* 集成页 / 预算 / 治理页截图。需要后端已启动（默认 http://127.0.0.1:18787）。
   令牌通过环境变量 GITHUB_TOKEN 传入，只用于在“粘贴令牌”输入框里输入（输入框是密码类型，截图中是圆点）。 */
if (process.env.LIVE_SCREENSHOTS !== '1' || !process.env.TARGET_REPO) {
  throw new Error('Disabled: this legacy driver mutates integrations. Requires LIVE_SCREENSHOTS=1 and explicit TARGET_REPO on a disposable instance.');
}
const path = require('path');
const { chromium } = require(process.env.PLAYWRIGHT_CORE || 'playwright-core');
const OUT = path.join(__dirname, '..', 'docs', 'screenshots') + path.sep; const BASE = process.env.BASE || 'http://127.0.0.1:18787';
(async () => {
  const b = await chromium.launch({ executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  const errs = []; const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } }); const p = await ctx.newPage();
  p.on('pageerror', (e) => errs.push('pageerror ' + e.message)); p.on('console', (m) => { if (m.type() === 'error') errs.push('console ' + m.text()); });
  const go = async (hash) => { await p.goto(`${BASE}/${hash}`, { waitUntil: 'load' }); await p.waitForTimeout(1500); };
  await ctx.request.delete(`${BASE}/api/integrations/github`).catch(() => {});
  await go('integrations'); await p.screenshot({ path: OUT + 'integrations-disconnected.png', fullPage: true });
  await go('overview'); await p.screenshot({ path: OUT + 'overview-connect-guide.png' });
  await go('integrations'); await p.click('[data-testid=device-start]'); await p.waitForSelector('[data-testid=device-error],[data-testid=device-waiting]'); await p.waitForTimeout(500);
  await p.screenshot({ path: OUT + 'integrations-device-flow.png', fullPage: true });
  await p.fill('[data-testid=token-input]', process.env.GITHUB_TOKEN || ''); await p.click('text=校验并保存'); await p.waitForSelector('[data-testid=gh-login]'); await p.waitForTimeout(800);
  await p.screenshot({ path: OUT + 'integrations-connected.png', fullPage: true });
  await p.click('text=选择仓库'); await p.waitForSelector('[data-testid=repo-item]'); await p.screenshot({ path: OUT + 'integrations-repo-picker.png' });
  await p.fill('[aria-label=搜索仓库]', process.env.TARGET_REPO); await p.waitForTimeout(1200); await p.click('[data-testid=repo-item]'); await p.waitForTimeout(300); await p.screenshot({ path: OUT + 'integrations-repo-bind.png' });
  await p.click('[data-testid=bind-confirm]'); await p.waitForSelector('[data-testid=health-panel]', { timeout: 20000 }); await p.waitForTimeout(500);
  await p.locator('[data-testid=health-panel]').scrollIntoViewIfNeeded(); await p.screenshot({ path: OUT + 'integrations-health.png', fullPage: true });
  await p.click('role=tab[name="连接状态"]'); await p.waitForSelector('[data-testid=status-tab]'); await p.waitForTimeout(500);
  await p.screenshot({ path: OUT + 'integrations-status.png', fullPage: true });
  await p.click('[data-testid=diagnostics] summary'); await p.click('text=最近调用记录'); await p.waitForTimeout(300); await p.screenshot({ path: OUT + 'integrations-diagnostics.png', fullPage: true });
  await go('budget'); await p.waitForSelector('[data-testid=budget-summary]'); await p.screenshot({ path: OUT + 'budget.png', fullPage: true });
  await p.click('[data-testid=edit-global]'); await p.waitForSelector('[data-testid=edit-drawer]'); await p.fill('[data-testid=field-dailyUsd]', '1'); await p.waitForTimeout(900);
  await p.screenshot({ path: OUT + 'budget-edit-drawer.png' });
  await go('governance'); await p.hover('[data-infotip] >> nth=0'); await p.waitForTimeout(300); await p.screenshot({ path: OUT + 'governance.png', fullPage: true });
  await p.click('[data-testid=toggle-advanced]'); await p.waitForTimeout(500); await p.screenshot({ path: OUT + 'governance-advanced.png', fullPage: true });
  console.log(JSON.stringify({ errors: errs }));
  await b.close();
})();
