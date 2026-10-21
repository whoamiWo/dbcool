import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'vite.svg'],
      manifest: {
        name: 'NocoBase · 低代码平台',
        short_name: 'NocoBase',
        description: '可视化低代码数据平台，支持表单、视图、Wiki、即时通讯与 BI',
        theme_color: '#1976D2',
        background_color: '#F5F7FA',
        display: 'standalone',
        start_url: '/',
        lang: 'zh-CN',
        categories: ['productivity', 'business', 'developer tools'],
        icons: [
          { src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: '/pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          { src: '/maskable-icon-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
        shortcuts: [
          {
            name: '工作台',
            short_name: '工作台',
            url: '/workbench',
            icons: [{ src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png' }],
          },
          {
            name: '即时通讯',
            short_name: 'IM',
            url: '/im',
            icons: [{ src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png' }],
          },
        ],
        screenshots: [],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,jpg,jpeg,gif,webp,woff,woff2,ttf,eot,otf}'],
        navigateFallback: '/index.html',
        // 显式不注册任何 /api/** runtime caching 规则
        // 确保业务接口数据不进入离线缓存
      },
      devOptions: {
        enabled: false,
      },
    }),
  ],
  // sockjs-client 在浏览器引用 Node 全局变量 global,需 polyfill
  define: {
    global: 'globalThis',
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    host: true,
    proxy: {
      // 开发时代理 API 请求到后端
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '/api'),
      },
      // Python 后端单独的 AI 端点
      '/api/ai': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/ai/, '/api/ai'),
      },
      // 统一实时消息总线(STOMP over WebSocket)
      // ws:true 必须显式开启 —— 否则开发环境 WebSocket 升级请求无法通过代理
      '/ws': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
      // Huddle 音视频信令通道（独立 WebSocket）
      '/ws/huddle': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    // R12: 代码分割 — 将大型依赖拆到独立 chunk,首屏只加载 React + Router
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'reactflow-vendor': ['reactflow'],
          'form-vendor': ['react-hook-form', '@hookform/resolvers', 'zod'],
          'state-vendor': ['zustand', '@tanstack/react-query'],
          'ui-vendor': ['lucide-react'],
        },
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    // H1：跨文件污染治理 —— 每个测试文件独立子进程/模块注册表
    pool: 'forks',
    isolate: true,
    sequence: { concurrent: false },
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    exclude: ['node_modules', 'dist', '.vscode-server'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      reportsDirectory: './coverage',
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.{ts,tsx}',
        'src/**/*.spec.{ts,tsx}',
        'src/test-setup.ts',
        'src/main.tsx',
        'src/router.tsx',
        'src/types/**',
        'src/pages/*.tsx',     // 页面组件大多未测,先不计入覆盖率
      ],
    },
  },
});
