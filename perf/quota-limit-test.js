/**
 * PHASE92：租户 API 配额的**真实流量**验证。
 *
 * <p>为什么单独写这个脚本：配额是否生效，**单测测不出来**。
 * 单测调用 Service 能验证计算逻辑，但验证不了
 * 「GlobalRateLimitFilter 真的在真实请求链路上拦住了流量」
 * （本项目 PHASE60 就踩过"过滤器定义了但没进链"的坑）。
 * 所以必须发真实 HTTP 请求，看到 429 才算数。
 *
 * <p>用法：
 * <pre>
 *   docker run --rm -v $PWD/perf:/scripts --network host \
 *     -e BASE_URL=http://localhost:8080 \
 *     grafana/k6 run /scripts/quota-limit-test.js
 * </pre>
 *
 * <p>判定：输出里的 quota_429 计数必须 > 0。
 * 若全程 0，说明配额**没有真的拦住** —— 要么过滤器没生效，
 * 要么配额值定得比实际流量还大（PHASE92 初稿就是后者：
 * 默认 60000/min = 1000 req/s，而单机总容量只有 827 req/s）。
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const quota429 = new Counter('quota_429');
const ok200 = new Counter('resp_200');

export const options = {
  scenarios: {
    quota: {
      executor: 'constant-vus',
      vus: 100,
      duration: '1m',
    },
  },
  thresholds: {
    // 只要出现过 429 就说明配额生效（不设"必须有多少"，避免脆弱）
    quota_429: ['count>0'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
  const r = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ username: 'admin', password: 'admin123' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const token = JSON.parse(r.body).data?.access_token;
  if (!token) throw new Error('登录失败，取不到 token');
  return { token };
}

export default function (data) {
  const res = http.get(`${BASE}/api/collections`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });
  if (res.status === 429) {
    quota429.add(1);
  } else if (res.status === 200) {
    ok200.add(1);
  }
  check(res, {
    'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
  });
  sleep(0.05);
}
