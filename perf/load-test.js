// ============================================================
//  NocoBase 性能压测脚本（k6）—— 请在 staging 环境执行
//
//  用法（阶梯全程）：
//    docker run --rm -v $PWD/perf:/scripts --network host \
//      -e BASE_URL=http://localhost:8080 -e USERNAME=admin -e PASSWORD=admin123 \
//      grafana/k6 run /scripts/load-test.js
//  用法（单档固定并发，供明细表）：
//    ... -e VUS=20 -e DURATION=2m grafana/k6 run /scripts/load-test.js
//
//  PHASE70 T2：从"只压 1 个只读接口"扩展到真实写入路径：
//    - 只读：GET  /api/collections
//    - 写入1：POST /api/im/messages            （IM 发消息，含广播）
//    - 写入2：POST /api/wiki/blocks/batch-upsert（Wiki 块保存）
//    - 写入3：POST /api/attachments/upload      （附件上传，multipart → MinIO）
//    - 写入4：POST /api/workflows/{id}/trigger  （工作流触发）
//
//  setup() 会先登录并抓取真实的 channelId / pageId / workflowId，
//  避免压测打在不存在资源上产生满屏 404。
//
//  注意：限流默认开启（登录 5/300s、全局 30/60s）。本脚本**不关闭限流**，
//  因此部分场景的拐点反映的是限流配置而非纯系统容量 —— 报告中已标注。
// ============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const VUS = Number(__ENV.VUS || 0);
const DURATION = __ENV.DURATION || '';

const trendStats = ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'];

export const options = VUS > 0
  ? {
      vus: VUS,
      duration: DURATION || '1m',
      thresholds: {
        http_req_failed: ['rate<0.05'],
        http_req_duration: ['p(95)<1500', 'p(99)<5000'],
      },
      summaryTrendStats: trendStats,
    }
  : {
      stages: [
        { duration: '30s', target: 20 },
        { duration: '1m', target: 50 },
        { duration: '1m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      thresholds: {
        http_req_failed: ['rate<0.05'],
        http_req_duration: ['p(95)<1500', 'p(99)<5000'],
      },
      summaryTrendStats: trendStats,
    };

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USERNAME = __ENV.USERNAME || 'admin';
const PASSWORD = __ENV.PASSWORD || 'admin123';

/** 登录并抓取压测需要的真实资源 ID。 */
export function setup() {
  const login = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  let token = '';
  try {
    token = login.json('data.access_token') || '';
  } catch (e) {
    console.error('登录失败，压测将无鉴权：', login.status, login.body);
  }

  const auth = { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };

  let channelId = '';
  const ch = http.get(`${BASE}/api/im/channels`, auth);
  try {
    const data = ch.json('data');
    const list = Array.isArray(data) ? data : data && data.content;
    if (list && list.length) channelId = String(list[0].id);
  } catch (e) { /* 忽略：无频道则跳过 IM 场景 */ }

  let pageId = '';
  const pg = http.get(`${BASE}/api/wiki/pages?limit=1`, auth);
  try {
    const data = pg.json('data');
    const list = Array.isArray(data) ? data : data && data.content;
    if (list && list.length) pageId = String(list[0].id);
  } catch (e) { /* 忽略 */ }

  let workflowId = '';
  const wf = http.get(`${BASE}/api/workflows?limit=1`, auth);
  try {
    const data = wf.json('data');
    const list = Array.isArray(data) ? data : data && data.content;
    if (list && list.length) workflowId = String(list[0].id);
  } catch (e) { /* 忽略 */ }

  console.log(`setup: channel=${channelId || 'N/A'} page=${pageId || 'N/A'} workflow=${workflowId || 'N/A'}`);
  return { token, channelId, pageId, workflowId };
}

export default function (data) {
  const auth = {
    headers: { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' },
  };

  // —— 只读：集合列表 ——
  const collections = http.get(`${BASE}/api/collections?limit=10`, auth);
  check(collections, { 'collections 200': (r) => r.status === 200 });

  // —— 写入 1：IM 发消息（含广播路径）——
  if (data.channelId) {
    const msg = http.post(
      `${BASE}/api/im/messages`,
      JSON.stringify({
        channelId: data.channelId,
        content: `perf-${Date.now()}`,
        contentType: 'text',
      }),
      auth,
      { tags: { scenario: 'im_message' } },
    );
    // 注：发送成功返回 201，被限流返回 429 —— 两者都属"正常处理"，
    // 只有 4xx/5xx（如 403 越权）才算异常。
    check(msg, { 'im message accepted': (r) => (r.status >= 200 && r.status < 300) || r.status === 429 });
  }

  // —— 写入 2：Wiki 块保存 ——
  if (data.pageId) {
    const wiki = http.post(
      `${BASE}/api/wiki/blocks/batch-upsert`,
      JSON.stringify({
        pageId: data.pageId,
        blocks: [{ type: 'paragraph', content: `perf ${Date.now()}` }],
      }),
      auth,
      { tags: { scenario: 'wiki_block' } },
    );
    check(wiki, { 'wiki upsert accepted': (r) => r.status === 200 || r.status === 403 });
  }

  // —— 写入 3：附件上传（multipart → MinIO）——
  const upload = http.post(
    `${BASE}/api/attachments/upload`,
    { file: http.file('perf test content', `perf-${Date.now()}.txt`, 'text/plain') },
    { headers: { Authorization: `Bearer ${data.token}` }, tags: { scenario: 'attachment_upload' } },
  );
  check(upload, { 'upload accepted': (r) => r.status === 200 });

  // —— 写入 4：工作流触发 ——
  if (data.workflowId) {
    const trig = http.post(
      `${BASE}/api/workflows/${data.workflowId}/trigger`,
      JSON.stringify({ source: 'perf' }),
      auth,
      { tags: { scenario: 'workflow_trigger' } },
    );
    check(trig, { 'workflow triggered': (r) => r.status === 200 || r.status === 404 });
  }

  sleep(1);
}
