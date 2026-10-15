import { expect, test } from '@playwright/test';
import fs from 'fs';
import path from 'path';

/**
 * 复用 global-setup 已登录取得的 token（PHASE108）。
 *
 * 原先每个 project 各登录一次 → 3 次真实登录撞 login 限流 5/300s。
 * 现在登录由 globalSetup 统一做 1 次并 fail-fast；此处只读 token，
 * 4 条 storageKey 安全断言保持真实后端校验不变。
 */
function adminToken(): string {
  const file = path.resolve(process.cwd(), 'e2e/.auth/token.json');
  const raw = fs.readFileSync(file, 'utf-8');
  const token = JSON.parse(raw).token as string;
  if (!token) throw new Error('global setup 未写入有效 token');
  return token;
}

/**
 * PHASE70 协同/存储安全 E2E。
 *
 * 变更说明（CodeBuddy 收尾）：
 * - 删除了原先两个"把 computeDiff 实现复制进 spec 再测副本"的用例 ——
 *   它们与组件行为完全隔离（组件改回整篇替换也全绿），零约束力。
 *   真实 diff 与并发合并语义已由 src/features/realtime/CollabEditor.test.tsx
 *   （import 真实 computeDiff / applyDiffToText）覆盖，含"整篇替换会产生重复
 *   内容"的反向验证。
 * - 删除了原先的 `if (!adminToken) test.skip(...)`：未配 token 就跳过会让用例
 *   假绿。改为在测试内自行登录取 token，取不到即 fail（不 skip）。
 */
test.describe('Storage key 安全（PHASE69 R2 回归）', () => {
  test('非法 storageKey 一律拒绝', async ({ request, baseURL }) => {
    // 登录由 globalSetup 统一完成一次（失败即 fail-fast，不 skip、不重试洗绿）
    const token = adminToken();
    expect(token, 'global setup 必须已取得有效 token').toBeTruthy();

    const auth = { headers: { Authorization: `Bearer ${token}` } };

    // 跨租户
    const crossTenant = await request.get(
      `${baseURL}/api/attachments/download?storageKey=other_tenant/test.txt`,
      auth,
    );
    expect(crossTenant.status()).toBe(403);

    // 路径穿越
    const traversal = await request.get(
      `${baseURL}/api/attachments/download?storageKey=../etc/passwd`,
      auth,
    );
    expect(traversal.status()).toBe(400);

    // 绝对路径
    const absPath = await request.get(
      `${baseURL}/api/attachments/download?storageKey=/etc/passwd`,
      auth,
    );
    expect(absPath.status()).toBe(400);

    // 反斜杠
    const backslash = await request.get(
      `${baseURL}/api/attachments/download?storageKey=tenant\\..\\test`,
      auth,
    );
    expect(backslash.status()).toBe(400);
  });
});
