import { expect, test } from '@playwright/test';

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
    // 自行登录，避免依赖外部环境变量；失败即 fail，不 skip
    const login = await request.post(`${baseURL}/api/auth/login`, {
      data: { username: 'admin', password: 'admin123' },
    });
    expect(login.status(), '登录必须成功').toBe(200);
    const token = (await login.json()).data.access_token as string;
    expect(token).toBeTruthy();

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
