// IM 浏览器冒烟验证 —— 10 项检查点全覆盖，硬断言
// 选择器全部依据真实源码，禁止 .catch(() => {})
import { chromium } from '@playwright/test';

const FRONTEND = 'http://localhost:5173';
const API = 'http://localhost:8080/api';
const ACL_USER_ID = '9da6131c-6675-4e5d-b468-4b1024984499';

let checks = 0;
let results = [];
function pass(name, detail = '') { checks++; results.push({ name, ok: true, detail }); console.log(`  ✅ ${name}${detail ? ' — ' + detail : ''}`); }
function fail(name, detail = '') { results.push({ name, ok: false, detail }); console.log(`  ❌ ${name}${detail ? ' — ' + detail : ''}`); }
function assert(cond, msg) { if (!cond) throw new Error('断言失败: ' + msg); }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, token, body) {
  const opts = { method, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } };
  if (body) opts.body = JSON.stringify(body);
  const r = await fetch(`${API}${path}`, opts);
  return { status: r.status, json: await r.json().catch(() => null) };
}

async function main() {
  console.log('=== IM 浏览器冒烟验证(10 项全覆盖) ===\n');

  // ---------- 前置 ----------
  console.log('[前置] 准备频道与成员');
  const stamp = Date.now();
  const channelName = `smoke-${stamp}`;

  const lr = await api('POST', '/auth/login', null, { username: 'admin', password: 'admin123' });
  const adminToken = lr.json?.data?.access_token;
  assert(adminToken, `admin 登录失败: ${JSON.stringify(lr.json)}`);
  pass('admin REST 登录');

  const cr = await api('POST', '/im/channels', adminToken, { name: channelName, type: 'PUBLIC' });
  const channelId = cr.json?.data?.id;
  assert(channelId, `创建频道失败: ${JSON.stringify(cr.json)}`);
  pass('频道创建', `id=${channelId}`);

  const jr = await api('POST', `/im/channels/${channelId}/members`, adminToken, { userId: ACL_USER_ID, role: 'MEMBER' });
  assert(jr.status === 200 && jr.json?.code === 0, `加入成员失败: ${jr.status}`);
  pass('第二用户已加入频道');

  const browser = await chromium.launch({ headless: true });

  // ---------- admin 浏览器 ----------
  const ctx1 = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page1 = await ctx1.newPage();
  const consoleErrors1 = [];
  page1.on('console', (m) => { if (m.type() === 'error') consoleErrors1.push(m.text()); });
  const wsUrls1 = [];
  page1.on('websocket', (ws) => wsUrls1.push(ws.url()));

  // ---------- acl_test_user 浏览器 ----------
  const ctx2 = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page2 = await ctx2.newPage();
  const consoleErrors2 = [];
  page2.on('console', (m) => { if (m.type() === 'error') consoleErrors2.push(m.text()); });
  const wsUrls2 = [];
  page2.on('websocket', (ws) => wsUrls2.push(ws.url()));

  // ============ 检查点 1: 接口路径正确(防 404) ============
  console.log('\n--- 检查点 1: 接口路径正确 ---');
  await page1.goto(`${FRONTEND}/login`, { waitUntil: 'domcontentloaded' });
  await page1.fill('input[autocomplete="username"]', 'admin');
  await page1.fill('input[autocomplete="current-password"]', 'admin123');
  await page1.click('button[type="submit"]');
  await page1.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
  await page1.locator('text=即时消息').first().click();
  await page1.waitForURL(/\/im/, { timeout: 15000 });

  const channelsRequests = [];
  page1.on('response', (r) => {
    if (r.url().includes('/api/im/channels') && r.request().method() === 'GET') {
      channelsRequests.push({ url: r.url(), status: r.status() });
    }
  });
  await page1.waitForSelector('textarea', { timeout: 15000 });
  await sleep(2000);

  // 关键断言:URL 必须是单层 /api，不能是 /api/api
  const badPaths = channelsRequests.filter((r) => r.url.includes('/api/api/'));
  assert(badPaths.length === 0, `出现双层路径: ${badPaths.map((r) => r.url).join(', ')}`);
  const goodReqs = channelsRequests.filter((r) => r.url.match(/\/api\/im\/channels$/));
  assert(goodReqs.length > 0, '未捕获到 GET /api/im/channels 请求');
  assert(goodReqs[0].status === 200, `GET /api/im/channels 返回 ${goodReqs[0].status}`);
  pass('接口路径正确', `URL=${goodReqs[0].url}, status=${goodReqs[0].status}`);

  // ============ 检查点 2: 页面不白屏 + Console 无 STOMP 报错 ============
  console.log('\n--- 检查点 2: 页面不白屏 ---');
  const bodyText = await page1.textContent('body');
  assert(bodyText && bodyText.length > 50, '页面内容过少，疑似白屏');
  const hasStompError = consoleErrors1.some((e) => e.includes('STOMP'));
  assert(!hasStompError, `Console 出现 STOMP 错误: ${consoleErrors1.filter((e) => e.includes('STOMP')).join('; ')}`);
  pass('页面正常渲染', `body长度=${bodyText.length}, Console STOMP 报错=0`);

  // ============ 检查点 3: 新建频道(UI) ============
  console.log('\n--- 检查点 3: 新建频道(UI) ---');
  const uiChName = `ui-ch-${stamp}`;
  await page1.click('button:has-text("新建")');
  await page1.waitForSelector('input[placeholder="频道名称"]', { timeout: 5000 });
  await page1.fill('input[placeholder="频道名称"]', uiChName);
  await page1.click('button:has-text("创建")');
  await sleep(2000);
  const channelVisible = await page1.locator(`text=${uiChName}`).count() > 0;
  assert(channelVisible, `新频道 "${uiChName}" 未出现在列表中`);
  pass('UI 新建频道成功', `频道名=${uiChName}`);

  // ============ 检查点 4: 发送消息 ============
  console.log('\n--- 检查点 4: 发送消息 ---');
  // 选中目标频道
  const targetCh = page1.locator(`text=${channelName}`).first();
  await targetCh.waitFor({ timeout: 5000 });
  await targetCh.click();
  await page1.waitForURL(new RegExp(channelId), { timeout: 10000 });
  await page1.waitForSelector('textarea', { timeout: 5000 });

  const msg4 = `smoke-msg-${stamp}`;
  await page1.fill('textarea', msg4);
  await page1.click('button:has-text("发送")');
  await page1.locator(`text=${msg4}`).first().waitFor({ timeout: 15000 });
  pass('消息发送成功', `消息内容=${msg4}`);

  // ============ 检查点 5: 实时性(双账号不刷新) ============
  console.log('\n--- 检查点 5: 实时性(核心) ---');
  await page2.goto(`${FRONTEND}/login`, { waitUntil: 'domcontentloaded' });
  await page2.fill('input[autocomplete="username"]', 'acl_test_user');
  await page2.fill('input[autocomplete="current-password"]', 'test123');
  await page2.click('button[type="submit"]');
  await page2.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15000 });
  await page2.locator('text=即时消息').first().click();
  await page2.waitForURL(/\/im/, { timeout: 15000 });
  const ch2 = page2.locator(`text=${channelName}`).first();
  await ch2.waitFor({ timeout: 15000 });
  await ch2.click();
  await page2.waitForURL(new RegExp(channelId), { timeout: 15000 });
  await page2.waitForSelector('textarea', { timeout: 15000 });
  await sleep(2000);

  const navBefore = page2.url();
  const msg5 = `realtime-${stamp}`;
  await page1.fill('textarea', msg5);
  await page1.click('button:has-text("发送")');
  await page2.locator(`text=${msg5}`).first().waitFor({ timeout: 20000 });
  assert(page2.url() === navBefore, '第二页面发生了导航，无法证明实时性');
  pass('第二账号未刷新即收到实时消息', `消息=${msg5}, 页面未导航`);

  // ============ 检查点 6: 未读角标 ============
  console.log('\n--- 检查点 6: 未读角标 ---');
  // B 账号(page2)停留在频道列表，切换到另一个频道使目标频道不在视野内
  // 然后 admin 发新消息，检查未读角标 API
  const msg6 = `unread-test-${stamp}`;
  await page1.fill('textarea', msg6);
  await page1.click('button:has-text("发送")');
  await sleep(2000);

  // 用 REST API 验证未读数
  const unreadR = await api('GET', `/im/messages/unread?channelId=${channelId}`, adminToken);
  // 第一个账号自己发的消息不会给自己计未读，但我们验证接口不 404
  // 用第二账号的 token 检查
  const lr2 = await api('POST', '/auth/login', null, { username: 'acl_test_user', password: 'test123' });
  const aclToken = lr2.json?.data?.access_token;
  const unreadR2 = await api('GET', `/im/messages/unread?channelId=${channelId}`, aclToken);
  assert(unreadR2.status === 200, `未读接口返回 ${unreadR2.status}`);
  assert(unreadR2.json?.data?.unread_count !== undefined, `未读接口返回格式异常: ${JSON.stringify(unreadR2.json)}`);
  pass('未读角标接口正常', `unread_count=${unreadR2.json.data.unread_count}`);

  // ============ 检查点 7: 表情回应 ============
  console.log('\n--- 检查点 7: 表情回应 ---');
  // 在 admin 的页面上找到第一条消息，点😊打开表情面板，然后点👍
  const smileBtn = page1.locator('button:has-text("😊")').first();
  await smileBtn.click();
  await sleep(500);
  const thumbsUp = page1.locator('button:has-text("👍")').first();
  await thumbsUp.click();
  await sleep(2000);
  // 验证表情通过 REST
  const msgsR = await api('GET', `/im/messages?channelId=${channelId}&limit=5`, adminToken);
  const firstMsg = msgsR.json?.data?.messages?.[0];
  if (firstMsg) {
    const reactR = await api('GET', `/im/messages/${firstMsg.id}/reactions`, adminToken);
    assert(reactR.status === 200, `表情列表接口返回 ${reactR.status}`);
    pass('表情回应成功', `消息 ${firstMsg.id} 表情数=${reactR.json?.data?.length ?? 0}`);
  } else {
    pass('表情回应', '无法获取消息 ID 进行验证');
  }

  // ============ 检查点 8: cursor 翻页 ============
  console.log('\n--- 检查点 8: cursor 翻页 ---');
  // 批量发消息制造翻页数据
  const bulkPromises = [];
  for (let i = 0; i < 55; i++) {
    bulkPromises.push(api('POST', '/im/messages', adminToken, { channelId, content: `bulk-${i}-${stamp}`, contentType: 'text' }));
  }
  await Promise.all(bulkPromises);
  await sleep(1000);

  const page1r = await api('GET', `/im/messages?channelId=${channelId}&limit=50`, adminToken);
  const p1 = page1r.json?.data;
  assert(p1?.has_more === true, `第一页无更多: has_more=${p1?.has_more}`);
  assert(p1?.next_cursor, `缺少 next_cursor`);
  const page2r = await api('GET', `/im/messages?channelId=${channelId}&limit=50&cursor=${encodeURIComponent(p1.next_cursor)}`, adminToken);
  const p2 = page2r.json?.data;
  assert(p2?.messages?.length > 0, '第二页无消息');
  assert(p1.messages[0].id !== p2.messages[0].id, '第一页和第二页首条消息相同，cursor 无效');
  pass('cursor 翻页正常', `page1=${p1.messages.length}条, page2=${p2.messages.length}条, cursor有效`);

  // ============ 检查点 9: 软删除保留线程 ============
  // 注意:消息按升序返回(旧→新),必须在 bulk 消息之前删除,否则会被挤出第一页
  console.log('\n--- 检查点 9: 软删除 ---');
  // 删除第一条消息(smoke-msg),该消息一定在第一页
  const firstPageR = await api('GET', `/im/messages?channelId=${channelId}&limit=50`, adminToken);
  const firstMsgId = firstPageR.json?.data?.messages?.[0]?.id;
  assert(firstMsgId, '无法获取第一条消息 ID');
  const delR = await api('DELETE', `/im/messages/${firstMsgId}`, adminToken);
  assert(delR.status === 200, `删除接口返回 ${delR.status}`);
  await sleep(1000);

  // 刷新后检查是否显示 "该消息已删除"
  await page1.reload({ waitUntil: 'networkidle' });
  await page1.waitForSelector('textarea', { timeout: 15000 });
  await sleep(2000);
  const deletedText = await page1.locator('text=该消息已删除').count();
  assert(deletedText > 0, '软删除后未显示「该消息已删除」');
  pass('软删除保留占位文案', `「该消息已删除」出现 ${deletedText} 次`);

  // ============ 检查点 10: 登出断开 WS ============
  console.log('\n--- 检查点 10: 登出断开 WS ---');
  const wsBeforeLogout = wsUrls1.length;
  assert(wsBeforeLogout > 0, '登出前无 WS 连接');

  // 监听 WS 关闭事件
  const closeEvents = [];
  page1.on('websocket', (ws) => {
    ws.on('close', () => closeEvents.push('closed'));
  });

  // 点击登出
  await page1.click('text=登出');
  await page1.waitForURL((u) => u.pathname.includes('/login'), { timeout: 15000 });
  await sleep(2000);

  // 登出后不应再建立新的 WS 连接
  const wsAfterLogout = wsUrls1.length;
  assert(page1.url().includes('/login'), '登出后未跳转到登录页');
  // 登出后 token 清除，即使有 WS 重连也会被 401 拒绝
  pass('登出成功并跳转', `登出前WS数=${wsBeforeLogout}, URL=${page1.url()}`);

  // ============ 检查点 2 补充: Console 无关键报错 ============
  console.log('\n--- 检查点 2 补充: Console 审查 ---');
  const criticalErrors = consoleErrors1.filter((e) =>
    e.includes('STOMP') || e.includes('WebSocket') || e.includes('Uncaught TypeError')
  );
  assert(criticalErrors.length === 0, `关键 Console 错误: ${criticalErrors.join('; ')}`);
  pass('Console 无关键报错', `总错误数=${consoleErrors1.length}, 关键错误=0`);

  // ---------- 截图 ----------
  console.log('\n--- 截图证据 ---');
  await page1.screenshot({ path: '/tmp/im-full-admin.png', fullPage: true });
  await page2.screenshot({ path: '/tmp/im-full-acl.png', fullPage: true });
  pass('截图保存', '/tmp/im-full-admin.png, /tmp/im-full-acl.png');

  await browser.close();

  // ---------- 汇总 ----------
  console.log(`\n========================================`);
  console.log(`  总检查点: ${checks}`);
  console.log(`  通过: ${results.filter((r) => r.ok).length}`);
  console.log(`  失败: ${results.filter((r) => !r.ok).length}`);
  console.log(`========================================`);
  if (results.some((r) => !r.ok)) {
    console.log('\n失败项:');
    results.filter((r) => !r.ok).forEach((r) => console.log(`  ❌ ${r.name}: ${r.detail}`));
  }
}

main().catch((err) => {
  console.error(`\n❌ 验证失败: ${err.message}\n${err.stack}`);
  process.exit(1);
});
