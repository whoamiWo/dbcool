import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import type { Plugin } from 'vite';
import path from 'path';
import { VitePWA } from 'vite-plugin-pwa';

/**
 * PHASE130: CSP nonce 占位符注入插件
 *
 * 目的：为 CSP 的 style-src 提供 nonce 机制，从而移除 'unsafe-inline'
 *
 * 设计：构建期只注入占位符 `__CSP_NONCE__`，真实 nonce 由容器启动时
 * （render_nginx.py）生成并替换。这样每次容器启动 nonce 都会变化，
 * 而非烧进镜像被所有部署/会话长期共享。
 *
 * 回退：若容器启动替换失败，占位符会残留在 meta 中，但 render_nginx.py
 * 会同时把 CSP 降级为 'unsafe-inline'，此时 Emotion 注入的 style 仍被放行，
 * 不会出现白屏。
 */
function noncePlugin(): Plugin {
  const NONCE_PLACEHOLDER = '__CSP_NONCE__';

  return {
    name: 'vite-plugin-nonce',

    transformIndexHtml(html: string) {
      return html.replace(
        '<head>',
        `<head>\n    <meta name="csp-nonce" content="${NONCE_PLACEHOLDER}" />\n`,
      );
    },
  };
}

export default defineConfig({
  plugins: [
    react(),
    noncePlugin(),
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
      },
      devOptions: {
        enabled: false,
      },
    }),
  ],
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
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '/api'),
      },
      '/api/ai': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/ai/, '/api/ai'),
      },
      '/ws': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
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
        'src/pages/*.tsx',
      ],
    },
  },
});
