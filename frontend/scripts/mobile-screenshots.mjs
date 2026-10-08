/**
 * PHASE91：生成移动端（375×812）页面截图。
 *
 * <p>为什么放在 scripts/ 而不是 e2e/：
 * 早期版本放在 e2e 目录下（mobile-screenshots.spec.ts），会被 playwright
 * 当作测试收集 —— 但它只截图、没有断言，混在测试套件里会虚增用例数
 * （10 页 × 3 项目 = 30 个"测试"），也会让人误以为那些页面被验证过。
 * 产物生成脚本应该独立运行，不进测试套件。
 *
 * <p>用法（在 frontend/ 下执行）：
 * <pre>
 *   VITE_E2E=true pnpm build && VITE_E2E=true pnpm preview --port 4173 &   # 另开一个终端
 *   node scripts/mobile-screenshots.mjs
 * </pre>
 *
 * <p>登录态：先从真实后端取 token 再注入 localStorage
 * （前端把 token 存在 nocobase_access_token），这样截到的是登录后的真实页面。
 */
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const BASE = process.env.BASE_URL || 'http://localhost:4173';
const API = process.env.API_URL || 'http://localhost:8080';
// 输出到仓库根的 docs/mobile-screenshots/（scripts/ 的上两级即仓库根）
const OUT = process.env.OUT_DIR
    || new URL('../../docs/mobile-screenshots', import.meta.url).pathname;

const PAGES = [
    ['login', '/login'],
    ['home', '/home'],
    ['collections', '/designer/schemas'],
    ['kanban', '/views/kanban-test/run'],
    ['calendar', '/views/calendar-test/run'],
    ['table', '/views/table-test/table'],
    ['gallery', '/views/gallery-test/gallery'],
    ['messages', '/messages'],
    ['my-tasks', '/tasks/my'],
    ['wiki', '/wiki/kb'],
];

async function fetchToken() {
    const r = await fetch(`${API}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'admin', password: 'admin123' }),
    });
    if (!r.ok) throw new Error(`登录失败 HTTP ${r.status}`);
    const j = await r.json();
    const t = j?.data?.access_token;
    if (!t) throw new Error('响应中无 access_token');
    return t;
}

const token = await fetchToken();
console.log(`已取得 token（${token.slice(0, 12)}…）`);

mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 375, height: 812 } });
const page = await ctx.newPage();

let ok = 0;

// 1) 先截登录页（此时未登录）
try {
    await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 20000 });
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${OUT}/login.png` });
    console.log(`✅ ${'login'.padEnd(14)} → ${OUT}/login.png`);
    ok++;
} catch (e) {
    console.log(`❌ login          ${String(e.message).split('\n')[0]}`);
}

// 2) 走真实登录流程（比注入 localStorage 更可靠：不依赖前端 store 的内部结构）
try {
    await page.fill('input[name="username"]', 'admin');
    await page.fill('input[name="password"]', 'admin123');
    await page.click('button[type="submit"]');
    await page.waitForTimeout(2500);
    const url = page.url();
    console.log(`   登录后 URL: ${url}`);
} catch (e) {
    console.log(`❌ 登录失败：${String(e.message).split('\n')[0]}`);
}

// 3) 逐页截图（跳过 login，已单独截）
for (const [name, path] of PAGES) {
    if (name === 'login') continue;
    try {
        await page.goto(BASE + path, { waitUntil: 'domcontentloaded', timeout: 20000 });
        await page.waitForTimeout(1200);
        await page.screenshot({ path: `${OUT}/${name}.png` });
        console.log(`✅ ${name.padEnd(14)} → ${OUT}/${name}.png`);
        ok++;
    } catch (e) {
        console.log(`❌ ${name.padEnd(14)} ${String(e.message).split('\n')[0]}`);
    }
}

await browser.close();
console.log(`\n完成：${ok}/${PAGES.length} 张`);
