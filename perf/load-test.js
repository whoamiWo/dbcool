// ============================================================
//  NocoBase 性能压测脚本（k6）—— 请在 staging 环境执行
//  用法（阶梯全程）：k6 run --env BASE_URL=... load-test.js
//  用法（固定并发单档，供明细表）：k6 run --env VUS=20 --env DURATION=2m ...
//
//  说明：
//  - 默认打 /api/health（公开端点）——登录接口有速率限制（实测 429），
//    认证接口 (/api/collections) 无法在压测中持续获取 Token；
//  - 因此本基线仅反映"框架开销 + 健康检查链路"，不代表业务接口吞吐；
//  - 空库 + 公开端点的 QPS 只能作为回归对照基线，不能作为生产容量依据。
// ============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';

const VUS = Number(__ENV.VUS || 0);
const DURATION = __ENV.DURATION || '';

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

export default function () {
  const health = http.get(`${BASE}/api/health`);
  check(health, {
    'health status 200': (r) => r.status === 200,
    'health < 500ms': (r) => r.timings.duration < 500,
  });

  sleep(1);
}
