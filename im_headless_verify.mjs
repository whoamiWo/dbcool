/**
 * IM 无头端到端验证脚本(由 CodeBuddy 编写)
 *
 * 原则:硬断言 —— 任一检查点失败立即 throw 并中断,绝不像 Cline 的脚本那样
 * .catch(() => {}) 后继续打印 ✓。
 *
 * 用法:node im_headless_verify.mjs
 */
const BASE = 'http://localhost:8080/api';
const WS_URL = 'ws://localhost:8080/ws/im/websocket';
const TENANT = 'tenant_default';

let failures = [];
let passed = 0;

function assert(cond, msg) {
  if (!cond) throw new Error(`断言失败: ${msg}`);
}

function ok(name, extra = '') {
  passed++;
  console.log(`  ✓ ${name}${extra ? ' — ' + extra : ''}`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- WebSocket 实现(优先全局,回退 ws 包) ----------
async function getWebSocketImpl() {
  if (typeof globalThis.WebSocket === 'function') return globalThis.WebSocket;
  try {
    const mod = await import('ws');
    return mod.default ?? mod.WebSocket;
  } catch {
    throw new Error('无可用 WebSocket 客户端(Node 需 >=22 或安装 ws 包)');
  }
}

// ---------- STOMP 帧 ----------
function frame(command, headers = {}, body = '') {
  const head = Object.entries(headers)
    .map(([k, v]) => `${k}:${v}`)
    .join('\n');
  return `${command}\n${head}\n\n${body}\0`;
}

// ---------- REST ----------
async function post(path, token, body) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body ?? {}),
  });
  const json = await res.json().catch(() => ({}));
  return { status: res.status, json };
}

async function get(path, token) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const json = await res.json().catch(() => ({}));
  return { status: res.status, json };
}

async function put(path, token, body) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body ?? {}),
  });
  const json = await res.json().catch(() => ({}));
  return { status: res.status, json };
}

async function del(path, token) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  });
  const json = await res.json().catch(() => ({}));
  return { status: res.status, json };
}

async function main() {
  console.log('=== IM 无头端到端验证开始 ===\n');

  // ---------- 检查点 1:登录 ----------
  console.log('[检查点 1] 登录');
  const loginRes = await post('/auth/login', null, { username: 'admin', password: 'admin123' });
  assert(loginRes.status === 200, `登录 HTTP ${loginRes.status}`);
  assert(loginRes.json?.code === 0, `登录 code=${loginRes.json?.code} msg=${loginRes.json?.message}`);
  const token = loginRes.json?.data?.access_token;
  assert(!!token, '未拿到 access_token');
  ok('登录成功', `token 前 20 字符: ${String(token).slice(0, 20)}...`);

  // ---------- 检查点 2:建频道 ----------
  console.log('\n[检查点 2] 创建频道');
  const chName = `verify-${Date.now()}`;
  const createRes = await post('/im/channels', token, {
    name: chName,
    type: 'PUBLIC',
    topic: '自动化验证频道',
    memberIds: [],
  });
  assert(
    createRes.status === 200 || createRes.status === 201,
    `建频道 HTTP ${createRes.status}: ${JSON.stringify(createRes.json)}`,
  );
  assert(createRes.json?.code === 0, `建频道 code=${createRes.json?.code} ${JSON.stringify(createRes.json)}`);
  const channelId = createRes.json?.data?.id;
  assert(!!channelId, `未返回频道 id: ${JSON.stringify(createRes.json?.data)}`);
  ok('频道创建成功', `id=${channelId}`);

  // ---------- 检查点 3:频道列表包含新频道 ----------
  console.log('\n[检查点 3] 频道列表');
  const listRes = await get('/im/channels', token);
  assert(listRes.json?.code === 0, `列表 code=${listRes.json?.code}`);
  const channels = listRes.json?.data ?? [];
  assert(
    channels.some((c) => c.id === channelId),
    `列表中未找到频道 ${channelId}(共 ${channels.length} 个)`,
  );
  ok('新频道出现在列表', `总数 ${channels.length}`);

  // ---------- 建立 WebSocket 连接并订阅(为检查点 5 做准备) ----------
  console.log('\n[检查点 5 前置] 建立 STOMP 连接并订阅频道');
  const WebSocketImpl = await getWebSocketImpl();
  const ws = new WebSocketImpl(`${WS_URL}?token=${encodeURIComponent(token)}`);

  const frames = [];
  ws.onmessage = (ev) => {
    frames.push(typeof ev.data === 'string' ? ev.data : String(ev.data));
  };
  ws.onerror = (e) => console.error('  [WS error]', e?.message ?? e);

  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('WebSocket 连接超时(10s)')), 10000);
    ws.onopen = () => {
      clearTimeout(t);
      resolve();
    };
    ws.onerror = (e) => {
      clearTimeout(t);
      reject(new Error('WebSocket 连接失败: ' + (e?.message ?? 'unknown')));
    };
  });
  ok('WebSocket 已连接', WS_URL);

  ws.send(frame('CONNECT', { 'accept-version': '1.2', 'heart-beat': '0,0', host: 'localhost' }));

  // 等待 CONNECTED
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline && !frames.some((f) => f.startsWith('CONNECTED'))) {
    await sleep(200);
  }
  assert(
    frames.some((f) => f.startsWith('CONNECTED')),
    `未收到 CONNECTED 帧,已收帧: ${JSON.stringify(frames.slice(0, 3))}`,
  );
  ok('STOMP CONNECTED');

  const topic = `/topic/t-${TENANT}.channel.${channelId}`;
  ws.send(frame('SUBSCRIBE', { id: 'sub-0', destination: topic }));
  await sleep(1000);
  ok('已订阅', topic);

  // ---------- 检查点 4:发消息 ----------
  console.log('\n[检查点 4] 发送消息');
  const content1 = `hello-${Date.now()}`;
  const msgRes = await post('/im/messages', token, {
    channelId,
    content: content1,
    contentType: 'text',
  });
  assert(
    msgRes.status === 200 || msgRes.status === 201,
    `发消息 HTTP ${msgRes.status}: ${JSON.stringify(msgRes.json)}`,
  );
  assert(msgRes.json?.code === 0, `发消息 code=${msgRes.json?.code} ${JSON.stringify(msgRes.json)}`);
  const msgId = msgRes.json?.data?.id;
  assert(!!msgId, `未返回消息 id: ${JSON.stringify(msgRes.json?.data)}`);
  ok('消息发送成功', `id=${msgId}`);

  // ---------- 检查点 5:实时投递(核心) ----------
  console.log('\n[检查点 5] 实时投递(WS 无 REST 请求下收到)');
  const framesBefore = frames.length;
  const content2 = `realtime-${Date.now()}`;
  const msg2Res = await post('/im/messages', token, { channelId, content: content2, contentType: 'text' });
  const msgId2 = msg2Res.json?.data?.id;
  assert(!!msgId2, `未返回第二条消息 id: ${JSON.stringify(msg2Res.json?.data)}`);

  // 等待 MESSAGE 帧
  const dl5 = Date.now() + 8000;
  while (Date.now() < dl5 && !frames.some((f) => f.startsWith('MESSAGE') && f.includes(content2))) {
    await sleep(200);
  }
  const got = frames.find((f) => f.startsWith('MESSAGE') && f.includes(content2));
  assert(!!got, `8s 内未通过 WS 收到消息「${content2}」。已收帧数 ${frames.length}(订阅后新增 ${frames.length - framesBefore})`);
  ok('WS 收到实时消息(未发起任何 REST 查询)', `帧前 80 字符: ${got.slice(0, 80).replace(/\n/g, '\\n')}`);

  // ---------- 检查点 6:未读 ----------
  console.log('\n[检查点 6] 未读与已读');
  const unreadRes = await get(`/im/messages/unread?channelId=${channelId}`, token);
  assert(unreadRes.json?.code === 0, `未读 code=${unreadRes.json?.code} ${JSON.stringify(unreadRes.json)}`);
  const unreadCount = unreadRes.json?.data?.unread_count;
  assert(typeof unreadCount === 'number', `unread_count 非数字: ${JSON.stringify(unreadRes.json?.data)}`);
  ok('未读接口返回', `unread_count=${unreadCount}`);

  // 注意:必须传「最新一条」消息 id —— 传旧 id 时其后产生的消息仍计为未读,属正确行为
  const readRes = await post('/im/messages/read', token, { channelId, lastMessageId: msgId2 });
  assert(readRes.json?.code === 0, `标记已读 code=${readRes.json?.code} ${JSON.stringify(readRes.json)}`);
  const unreadAfter = await get(`/im/messages/unread?channelId=${channelId}`, token);
  const after = unreadAfter.json?.data?.unread_count;
  assert(after === 0, `标记已读后未读数应为 0,实际 ${after}`);
  ok('标记已读后未读归零', `${unreadCount} → ${after}`);

  // ---------- 检查点 7:表情回应 ----------
  console.log('\n[检查点 7] 表情回应');
  const emoji = '👍';
  const addRes = await post(`/im/messages/${msgId}/reactions`, token, { emoji });
  assert(addRes.json?.code === 0, `加表情 code=${addRes.json?.code} ${JSON.stringify(addRes.json)}`);
  const reactList = await get(`/im/messages/${msgId}/reactions`, token);
  assert(reactList.json?.code === 0, `表情列表 code=${reactList.json?.code}`);
  const reactions = reactList.json?.data ?? [];
  assert(
    reactions.some((r) => r.emoji === emoji),
    `表情列表中未找到 ${emoji}: ${JSON.stringify(reactions)}`,
  );
  ok('表情添加成功', `当前表情数 ${reactions.length}`);

  const rmRes = await del(`/im/messages/${msgId}/reactions?emoji=${encodeURIComponent(emoji)}`, token);
  assert(rmRes.json?.code === 0, `取消表情 code=${rmRes.json?.code} ${JSON.stringify(rmRes.json)}`);
  const afterRm = await get(`/im/messages/${msgId}/reactions`, token);
  const left = (afterRm.json?.data ?? []).filter((r) => r.emoji === emoji);
  assert(left.length === 0, `取消后仍存在 ${emoji}: ${JSON.stringify(left)}`);
  ok('表情取消成功');

  // ---------- 检查点 8:cursor 分页 ----------
  console.log('\n[检查点 8] cursor 分页(造 55 条)');
  for (let i = 0; i < 55; i++) {
    await post('/im/messages', token, { channelId, content: `bulk-${i}-${Date.now()}`, contentType: 'text' });
  }
  const page1 = await get(`/im/messages?channelId=${channelId}&limit=50`, token);
  assert(page1.json?.code === 0, `第一页 code=${page1.json?.code}`);
  const p1 = page1.json?.data ?? {};
  assert(Array.isArray(p1.messages), `第一页 messages 非数组: ${JSON.stringify(p1).slice(0, 200)}`);
  assert(p1.messages.length === 50, `第一页应 50 条,实际 ${p1.messages.length}`);
  assert(p1.has_more === true, `has_more 应为 true,实际 ${p1.has_more}`);
  assert(!!p1.next_cursor, `next_cursor 为空: ${JSON.stringify(p1).slice(0, 200)}`);
  ok('第一页', `50 条,has_more=true`);

  const page2 = await get(
    `/im/messages?channelId=${channelId}&limit=50&cursor=${encodeURIComponent(p1.next_cursor)}`,
    token,
  );
  assert(page2.json?.code === 0, `第二页 code=${page2.json?.code}`);
  const p2 = page2.json?.data ?? {};
  assert(Array.isArray(p2.messages) && p2.messages.length > 0, `第二页无消息: ${JSON.stringify(p2).slice(0, 200)}`);
  const ids1 = new Set(p1.messages.map((m) => m.id));
  const overlap = p2.messages.filter((m) => ids1.has(m.id));
  assert(overlap.length === 0, `第二页与第一页有 ${overlap.length} 条重复`, );
  ok('第二页(用 next_cursor)', `${p2.messages.length} 条,与第一页无重复`);

  // ---------- 检查点 9:编辑 ----------
  console.log('\n[检查点 9] 编辑消息');
  const edited = `edited-${Date.now()}`;
  const editRes = await put(`/im/messages/${msgId}`, token, { content: edited });
  assert(editRes.json?.code === 0, `编辑 code=${editRes.json?.code} ${JSON.stringify(editRes.json)}`);
  const afterEdit = await get(`/im/messages?channelId=${channelId}&limit=100`, token);
  const editedMsg = (afterEdit.json?.data?.messages ?? []).find((m) => m.id === msgId);
  assert(!!editedMsg, `未找到消息 ${msgId}`);
  assert(editedMsg.content === edited, `内容未更新: ${editedMsg.content}`);
  assert(!!editedMsg.editedAt, `editedAt 为空: ${JSON.stringify(editedMsg)}`);
  ok('编辑成功', `editedAt=${editedMsg.editedAt}`);

  // ---------- 检查点 10:软删除 ----------
  console.log('\n[检查点 10] 软删除(保留记录)');
  const delRes = await del(`/im/messages/${msgId}`, token);
  assert(delRes.json?.code === 0, `删除 code=${delRes.json?.code} ${JSON.stringify(delRes.json)}`);
  const afterDel = await get(`/im/messages?channelId=${channelId}&limit=100`, token);
  const deletedMsg = (afterDel.json?.data?.messages ?? []).find((m) => m.id === msgId);
  assert(!!deletedMsg, `软删除后消息应仍可查到(保留线程完整性),但未找到 ${msgId}`);
  assert(!!deletedMsg.deletedAt, `deletedAt 应非空: ${JSON.stringify(deletedMsg)}`);
  ok('软删除成功(记录保留)', `deletedAt=${deletedMsg.deletedAt}`);

  // ---------- 收尾 ----------
  try {
    ws.close();
  } catch {
    /* ignore */
  }

  console.log(`\n=== 全部通过: ${passed} 个检查点 ===`);
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error(`\n❌ 验证失败(在第 ${passed + 1} 个检查点):`);
    console.error(err?.stack ?? String(err));
    process.exit(1);
  });
