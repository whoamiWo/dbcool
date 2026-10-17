// 临时诊断脚本：按 endpoint tag 拆分 http_req_failed，定位 25% 失败来源
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USERNAME = __ENV.USERNAME || 'admin';
const PASSWORD = __ENV.PASSWORD || 'admin123';

export const options = {
  vus: Number(__ENV.VUS || 50),
  duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'p(95)'],
};

export function setup() {
  const login = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { ep: 'login' } },
  );
  const token = login.json('data.access_token') || '';
  const auth = { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };

  let channelId = '';
  const ch = http.get(`${BASE}/api/im/channels`, { ...auth, tags: { ep: 'setup_channels' } });
  try {
    const data = ch.json('data');
    const list = Array.isArray(data) ? data : data && data.content;
    if (list && list.length) channelId = String(list[0].id);
  } catch (e) {}

  let pageId = '';
  const pg = http.get(`${BASE}/api/wiki/pages?limit=1`, { ...auth, tags: { ep: 'setup_pages' } });
  try {
    const data = pg.json('data');
    const list = Array.isArray(data) ? data : data && data.content;
    if (list && list.length) pageId = String(list[0].id);
  } catch (e) {}

  let workflowId = '';
  const wf = http.get(`${BASE}/api/workflows?limit=1`, { ...auth, tags: { ep: 'setup_workflows' } });
  try {
    const data = wf.json('data');
    const list = Array.isArray(data) ? data : data && data.content;
    if (list && list.length) workflowId = String(list[0].id);
  } catch (e) {}

  console.log(`setup: channel=${channelId || 'N/A'} page=${pageId || 'N/A'} workflow=${workflowId || 'N/A'}`);
  return { token, channelId, pageId, workflowId };
}

export default function (data) {
  const auth = {
    headers: { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' },
  };

  const collections = http.get(`${BASE}/api/collections?limit=10`, { ...auth, tags: { ep: 'collections' } });
  check(collections, { 'collections 200': (r) => r.status === 200 });

  if (data.channelId) {
    const msg = http.post(
      `${BASE}/api/im/messages`,
      JSON.stringify({ channelId: data.channelId, content: `perf-${Date.now()}`, contentType: 'text' }),
      { ...auth, tags: { ep: 'im_message' } },
    );
    check(msg, { 'im accepted': (r) => (r.status >= 200 && r.status < 300) || r.status === 429 });
  }

  if (data.pageId) {
    const wiki = http.post(
      `${BASE}/api/wiki/blocks/batch-upsert`,
      JSON.stringify({ pageId: data.pageId, blocks: [{ type: 'paragraph', content: `perf ${Date.now()}` }] }),
      { ...auth, tags: { ep: 'wiki_upsert' } },
    );
    check(wiki, { 'wiki accepted': (r) => r.status === 200 || r.status === 403 });
  }

  const upload = http.post(
    `${BASE}/api/attachments/upload`,
    { file: http.file('perf test content', `perf-${Date.now()}.txt`, 'text/plain') },
    { headers: { Authorization: `Bearer ${data.token}` }, tags: { ep: 'attachment_upload' } },
  );
  check(upload, { 'upload accepted': (r) => r.status === 200 });

  if (data.workflowId) {
    const trig = http.post(
      `${BASE}/api/workflows/${data.workflowId}/trigger`,
      JSON.stringify({ source: 'perf' }),
      { ...auth, tags: { ep: 'workflow_trigger' } },
    );
    check(trig, { 'workflow accepted': (r) => r.status === 200 || r.status === 404 });
  }
}
