// ============================================================
//  NocoBase 性能压测脚本（k6）—— 请在 staging 环境执行
//  用法（阶梯全程）：k6 run --env BASE_URL=... load-test.js
//  用法（固定并发单档，供明细表）：k6 run --env VUS=20 --env DURATION=2m ...
//
//  说明：
//  - 目标接口：GET /api/collections（业务接口，走 DB + ACL）
//  - 数据量：空库（仅 6 个用户，无业务记录）
//  - 登录限流：压测前临时调高 RATELIMIT_LOGIN_LIMIT=1000，压测后必须恢复默认 5
// ============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const VUS = Number(__ENV.VUS || 0);
const DURATION = __ENV.DURATION || '';
const TOKEN = __ENV.TOKEN || '';

export const options = VUS > 0
  ? {
      // 单档固定并发模式
      vus: VUS,
      duration: DURATION,
      thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500', 'p(99)<3000'],
      },
      summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
    }
  : {
      // 阶梯全程模式：20 → 50 → 100
      stages: [
        { duration: '1m', target: 20 },
        { duration: '2m', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '1m', target: 0 },
      ],
      thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500', 'p(99)<3000'],
      },
      summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
    };

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const params = {
  headers: {
    Authorization: `Bearer ${TOKEN}`,
    'Content-Type': 'application/json',
  },
};

export default function () {
  // 业务接口：GET /api/collections（走 DB + ACL，有查询成本）
  const collections = http.get(`${BASE}/api/collections?limit=10`, params);
  check(collections, {
    'collections status 200': (r) => r.status === 200,
    'collections < 500ms': (r) => r.timings.duration < 500,
  });

  sleep(1);
}
