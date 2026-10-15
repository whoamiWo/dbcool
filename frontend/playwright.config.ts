/**
 * Playwright E2E 配置(Week 39 Step C).
 *
 * 策略: 前端 E2E + API mock(不启 Spring)
 * - webServer: vite preview(serve build/)
 * - 全局 mock /api/* 请求用 page.route()
 * - 优点: 快(不启后端)、稳(API 行为固定)、CI 友好
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'list',
  // PHASE108：真实登录收敛为全局 1 次（collab-crdt 是唯一真实打后端的 spec），
  // token 落盘供 3 个 project 复用，避免同 IP 300s 内累计登录超 5 次触发 429。
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    // 用 iPhone 13 的视口，但**强制 chromium 引擎**：
    // devices['iPhone 13'] 默认走 WebKit，本机未安装 WebKit 会导致该项目的
    // 用例全部以 "browserType.launch: Executable doesn't exist" 失败
    // （看起来像适配没做，实际是浏览器没装）。
    { name: 'mobile-chromium', use: { ...devices['iPhone 13'], browserName: 'chromium' } },
  ],
  webServer: {
    command: 'VITE_E2E=true pnpm build && VITE_E2E=true pnpm preview --port 4173',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
