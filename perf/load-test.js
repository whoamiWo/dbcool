// ============================================================
//  NocoBase 性能压测脚本（k6）—— 请在 staging 环境执行
//  用法：k6 run --env BASE_URL=... --env TOKEN=... load-test.js
// ============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const listLatency = new Trend('list_latency', true);

export const options = {
  stages: [
    { duration: '1m', target: 20 },  // 爬坡
    { duration: '3m', target: 50 },  // 稳态
    { duration: '1m', target: 0 },   // 下降
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],       // 错误率 < 1%
    http_req_duration: ['p(95)<500', 'p(99)<3000'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';

const params = {
  headers: {
    Authorization: `Bearer ${TOKEN}`,
    'Content-Type': 'application/json',
  },
};

export default function () {
  // 1) 只读：集合列表（最典型的读路径）
  const list = http.get(`${BASE}/api/collections`, params);
  const listOk = check(list, {
    'list status 200': (r) => r.status === 200,
    'list < 500ms': (r) => r.timings.duration < 500,
  });
  errorRate.add(!listOk);
  listLatency.add(list.timings.duration);

  // 2) 健康检查（不依赖登录，用于分离"应用本身"与"鉴权链路"的开销）
  const health = http.get(`${BASE}/actuator/health`);
  check(health, { 'health status 200': (r) => r.status === 200 });

  sleep(1);
}

export function handleSummary(data) {
  const p = (m) => (m ? m.values : {});
  return {
    'summary.json': JSON.stringify(data, null, 2),
    stdout: `
================ 压测结果 ================
请求总数 : ${data.metrics.http_reqs.values.count}
QPS      : ${(data.metrics.http_reqs.values.rate).toFixed(2)}
错误率   : ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
P95      : ${(p(data.metrics.http_req_duration)['p(95)'] || 0).toFixed(0)} ms
P99      : ${(p(data.metrics.http_req_duration)['p(99)'] || 0).toFixed(0)} ms
==========================================
`,
  };
}
