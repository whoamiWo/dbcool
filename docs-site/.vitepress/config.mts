import { defineConfig } from 'vitepress';

// Wave1/Wave2 T2.2：文档站配置 —— 首页产品化 + 核心 runbook 导航。
// 内容源：docs/ 下核心运维文档（构建时直接引用仓库内 md，避免双份维护）。
export default defineConfig({
  lang: 'zh-CN',
  title: 'DBCool',
  description: '自部署的企业协作低代码平台 — 数据表 · Wiki · IM · 看板 · 工作流 · AI',
  srcDir: '.',
  // 只把 .vitepress 与公开资源目录排除在页面外；页面 = 仓库内选定的 md
  srcExclude: ['**/node_modules/**', '**/archive/**', '**/.vitepress/**'],
  ignoreDeadLinks: true, // 仓库内文档互相引用路径复杂，首版放宽（发布前用链接检查器再收紧）
  themeConfig: {
    siteTitle: 'DBCool 文档',
    nav: [
      { text: '首页', link: '/index' },
      { text: 'GitHub', link: 'https://github.com/whoamiWo/dbcool' },
    ],
    sidebar: [
      {
        text: '开始',
        items: [
          { text: '产品介绍', link: '/index' },
          { text: '安装支持矩阵', link: '/pages/install-support-matrix' },
        ],
      },
      {
        text: '部署与运维',
        items: [
          { text: '私有化交付任务书', link: '/pages/WAVE1_PRIVATE_DELIVERY_TASKS' },
          { text: '升级 Runbook', link: '/pages/upgrade-runbook' },
          { text: '备份与恢复', link: '/pages/backup-restore-runbook' },
          { text: '密钥轮换', link: '/pages/key-rotation-runbook' },
          { text: '发布回滚', link: '/pages/release-rollout-runbook' },
        ],
      },
      {
        text: '安全与质量',
        items: [
          { text: '安全策略', link: '/pages/SECURITY' },
          { text: '租户隔离审计', link: '/pages/tenant-isolation-audit-sample' },
          { text: '排障手册', link: '/pages/TROUBLESHOOTING' },
        ],
      },
      {
        text: '平台与对标',
        items: [
          { text: '七强对标实证（2026-10-30）', link: '/pages/PLATFORM_BENCHMARK_ANALYSIS_2026-10-30' },
          { text: '产品化路线图 v2', link: '/pages/PRODUCTIZATION_ROADMAP' },
          { text: '开发者手册', link: '/pages/DEVELOPMENT_NOTES' },
        ],
      },
    ],
    socialLinks: [{ icon: 'github', link: 'https://github.com/whoamiWo/dbcool' }],
    outline: [2, 3],
  },
});
