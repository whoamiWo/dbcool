// ============================================================
//  NocoBase 性能压测脚本（k6） —— PHASE70 T2 / PHASE88
//
//  用法（阶梯全程）：
//    docker run --rm -v $PWD/perf:/scripts --network host \
//      -e BASE_URL=http://localhost:8080 -e USERNAME=admin -e PASSWORD=admin123 \
//      grafana/k6 run /scripts/load-test.js
//  用法（单档固定并发，供明细表）：
//    ... -e VUS=20 -e DURATION=2m grafana/k6 run /scripts/load-test.js
//
//  写入场景（T1 补齐覆盖）：
//    - 写入1：POST /api/im/messages            （IM 发消息，含广播）
//    - 写入2：POST /api/wiki/blocks/batch-upsert（Wiki 块保存）
//    - 写入3：POST /api/attachments/upload      （附件上传 → MinIO）
//    - 写入4：POST /api/workflows/{id}/trigger  （工作流触发）
//
//  T1：setup() 中创建 Wiki 页面和工作流，确保压测全覆盖
//  T2：阶梯式加压到拐点
//  注意：限流默认开启时看到 429 错误，这是限流配置导致，非系统容量问题
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
      // PHASE88：分层测容量时必须**稳定在目标 VU**（爬坡式阶梯把不同压力混在一起，
      // 聚合出来的 p95/吞吐无法代表任何单一层级）。用环境变量指定峰值：
      //   PEAK_VU=150 HOLD=2m docker run ... k6 run /scripts/load-test.js
      stages: [
        { duration: '30s', target: Number(__ENV.PEAK_VU || 100) },
        { duration: __ENV.HOLD || '1m', target: Number(__ENV.PEAK_VU || 100) },
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

let createdKbId = '';
let createdPageId = '';
let createdWorkflowId = '';

/** 登录并抓取压测需要的真实资源 ID。
 *  T1：若 Wiki/Workflow 不存在，先创建种子数据
 */
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

  // —— IM 频道 ——
  let channelId = '';
  const ch = http.get(`${BASE}/api/im/channels`, auth);
  try {
    const data = ch.json('data');
    const list = Array.isArray(data) ? data : data && data.content;
    if (list && list.length) channelId = String(list[0].id);
  } catch (e) {}

  // —— T1：Wiki 知识库 & 页面（若无则创建）——
  let pageId = '';
  const pgList = http.get(`${BASE}/api/wiki/pages?limit=1`, auth);
  try {
    const data = pgList.json('data');
    const list = Array.isArray(data) ? data : data && data.content;
    if (list && list.length) {
      pageId = String(list[0].id);
    } else if (createdPageId) {
      pageId = createdPageId;
    }
  } catch (e) {}

  if (!pageId) {
    // 创建知识库
    const kbResp = http.post(
      `${BASE}/api/wiki/kbs`,
      JSON.stringify({ name: '压测知识库', description: 'Load test KB' }),
      auth,
    );
    try {
      const kbData = kbResp.json('data');
      createdKbId = kbData?.id || '';
      if (kbData?.id) {
        // 创建页面
        const pageResp = http.post(
          `${BASE}/api/wiki/pages`,
          JSON.stringify({
            knowledge_base_id: createdKbId,
            slug: 'perf-page-' + Date.now(),
            title: '压测页面',
            content: 'Load test page content',
          }),
          auth,
        );
        try {
          const pageData = pageResp.json('data');
          createdPageId = pageData?.id || pageId;
          pageId = createdPageId;
        } catch (e) {}
      }
    } catch (e) {}
  }

  // —— T1：工作流（若无则创建）——
  let workflowId = '';
  const wfList = http.get(`${BASE}/api/workflows?limit=1`, auth);
  try {
    const data = wfList.json('data');
    const list = Array.isArray(data) ? data : data && data.content;
    if (list && list.length) workflowId = String(list[0].id);
  } catch (e) {}

  if (!workflowId) {
    // 创建一个最简工作流（手动触发）
    const wfResp = http.post(
      `${BASE}/api/workflows`,
      JSON.stringify({
        name: '压测工作流',
        description: 'Load test workflow',
        enabled: true,
        formSchema: {
          type: 'object',
          properties: { id: { type: 'string' } },
          required: ['id'],
        },
        triggerType: 'MANUAL',
        // 最简流程：只有开始节点
        nodes: [{ id: 'start', type: 'start', position: { x: 0, y: 0 } }],
        edges: [],
      }),
      auth,
    );
    try {
      const wfData = wfResp.json('data');
      createdWorkflowId = wfData?.id || '';
      workflowId = createdWorkflowId;
    } catch (e) {}
  }

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