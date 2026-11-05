// 关键页面截图 + 无障碍树（文本）。X7_PAGES="/,/login"
import { chromium } from '/usr/local/lib/node_modules/@playwright/test/index.mjs';
const base = process.env.X7_URL; const pages = (process.env.X7_PAGES || '/').split(',').map(s => s.trim()).filter(Boolean).slice(0, 12);
const browser = await chromium.launch({ args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, locale: 'zh-CN' });
let i = 0;
for (const p of pages) {
  const name = String(i++).padStart(2, '0') + '-' + (p.replace(/[^a-zA-Z0-9]+/g, '_').replace(/^_|_$/g, '') || 'root');
  try {
    await page.goto(new URL(p, base).toString(), { waitUntil: 'networkidle', timeout: 30000 });
    await page.screenshot({ path: `/out/shots/${name}.png`, fullPage: false });
    const tree = await page.locator('body').ariaSnapshot();
    const fs = await import('node:fs'); fs.writeFileSync(`/out/a11y/${name}.txt`, tree.slice(0, 8000));
  } catch (e) { const fs = await import('node:fs'); fs.writeFileSync(`/out/a11y/${name}.txt`, 'ERROR ' + String(e).slice(0, 500)); }
}
await browser.close();
