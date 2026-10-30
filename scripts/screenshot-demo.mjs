// Wave2 T2.3 — 演示截图脚本（带登录态）
// 用法：cd frontend && node ../scripts/screenshot-demo.mjs
// 产物：docs-site/public/screenshots/*.png
// 说明：本机演示凭据 admin/admin123（与 scripts/ 各 e2e 脚本一致）。
import { createRequire } from 'module';
import { mkdirSync } from 'fs';
import { dirname, resolve } from 'path';
import { fileURLToPath } from 'url';

// playwright 依赖装在 frontend/node_modules，用 createRequire 从那里解析
const frontendRequire = createRequire(new URL('../frontend/package.json', import.meta.url));
const { chromium } = frontendRequire('playwright');

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BASE = process.env.DBCOOL_URL || 'http://localhost:5173';
const OUT = resolve(ROOT, 'docs-site/public/screenshots');
const USER = process.env.DBCOOL_USERNAME || 'admin';
const PASS = process.env.DBCOOL_PASSWORD || 'admin123';

const SHOTS = [
  { name: 'workbench', path: '/workbench' },
  { name: 'collections', path: '/designer/schemas' },
  { name: 'collection-detail', path: '/designer/collections/demo_contacts' },
  { name: 'wiki', path: '/wiki/kb' },
  { name: 'im', path: '/im' },
  { name: 'project', path: '/projects' },
  { name: 'workflows', path: '/designer/workflows' },
  { name: 'views', path: '/designer/views' },
  { name: 'agent', path: '/agent' },
];

mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

// 登录
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.waitForTimeout(1500);
const userInput = page.locator('input').first();
const passInput = page.locator('input[type="password"]').first();
await userInput.fill(USER);
await passInput.fill(PASS);
await page.locator('button[type="submit"], button:has-text("登录")').first().click();
await page.waitForTimeout(2500);

let ok = 0, fail = 0;
for (const s of SHOTS) {
  try {
    await page.goto(`${BASE}${s.path}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);
    await page.screenshot({ path: `${OUT}/${s.name}.png`, fullPage: false });
    console.log(`✅ ${s.name}.png`);
    ok++;
  } catch (e) {
    console.log(`❌ ${s.name}: ${String(e).slice(0, 120)}`);
    fail++;
  }
}
// 登录页（未登录态已在 workbench 之前）
try {
  const ctx2 = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const p2 = await ctx2.newPage();
  await p2.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await p2.waitForTimeout(1500);
  await p2.screenshot({ path: `${OUT}/login.png` });
  console.log('✅ login.png');
  ok++;
} catch (e) {
  console.log(`❌ login: ${String(e).slice(0, 120)}`);
  fail++;
}
await browser.close();
console.log(`完成：${ok} 成功 / ${fail} 失败`);
process.exit(fail > 0 ? 1 : 0);
