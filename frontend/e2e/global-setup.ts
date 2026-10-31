/**
 * E2E 全局登录（PHASE108：消除 collab-crdt 偶发 429 的根因）。
 *
 * 背景：`collab-crdt.spec.ts` 是 E2E 里唯一真实打后端登录的用例（其余 spec 用
 * page.route mock）。配置 3 个 project（chromium/firefox/mobile-chromium）并行，
 * 原本每个 project 各登录 1 次 → 同一 IP 在 300s 窗口内累计 3 次真实登录，
 * 叠加同 IP 其他登录即超过 `ratelimit.login=5/300s` → 429（本地 retries:0 故红）。
 *
 * 修法：登录收敛为**全局 1 次**，token 落盘供 3 个 project 复用。
 * - 不减少用例计数（3 个 case 仍各执行 4 条 storageKey 断言），基线 ≥135 不回退；
 * - 断言不弱化：登录失败直接抛错 fail-fast（比原先"某个 case 红"更严格），
 *   不做 skip、不重试洗绿。
 */
import { request } from '@playwright/test';
import fs from 'fs';
import path from 'path';

const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:4173';
const TOKEN_FILE = path.resolve(process.cwd(), 'e2e/.auth/token.json');

async function globalSetup() {
  // Wave4 v2.1.3：CI（ci.yml 的 E2E job）无真实后端 —— collab-crdt 是唯一真实登录
  // 的 spec，CI 以 --grep-invert 排除；其余 spec 全 mock，无需登录。设
  // E2E_SKIP_REAL_LOGIN=1 时跳过（本地跑真实 E2E 仍 fail-fast 校验登录）。
  if (process.env.E2E_SKIP_REAL_LOGIN === '1') {
    console.log('[e2e-global-setup] E2E_SKIP_REAL_LOGIN=1 → 跳过真实登录（CI mock 模式）');
    return;
  }
  const api = await request.newContext();

  const login = await api.post(`${BASE_URL}/api/auth/login`, {
    data: { username: 'admin', password: 'admin123' },
  });

  if (login.status() !== 200) {
    await api.dispose();
    throw new Error(
      `E2E global setup 登录失败：HTTP ${login.status()}（期望 200）。` +
        (login.status() === 429
          ? ' 疑似撞 login 限流 5/300s（基于 IP）：请清理 Redis 限流 key 后重跑，不要靠重试掩盖。'
          : ' 请确认后端已启动且凭据正确。'),
    );
  }

  const body = await login.json();
  const token = body?.data?.access_token as string | undefined;
  if (!token) {
    await api.dispose();
    throw new Error('E2E global setup 登录成功但未取得 access_token');
  }

  fs.mkdirSync(path.dirname(TOKEN_FILE), { recursive: true });
  fs.writeFileSync(TOKEN_FILE, JSON.stringify({ token }));

  await api.dispose();
}

export default globalSetup;
