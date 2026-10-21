import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import type { Plugin, ViteDevServer } from 'vite';
import path from 'path';
import { VitePWA } from 'vite-plugin-pwa';
import crypto from 'crypto';

/**
 * PHASE130: Vite 构建期 nonce 生成与注入插件
 * 
 * 目的：为 CSP 的 style-src 提供 nonce 机制，从而移除 'unsafe-inline'
 * 
 * 流程：
 * 1. 构建开始时生成随机 nonce（base64 编码）
 * 2. 通过 transformIndexHtml 钩子注入到 index.html
 * 3. 写入 dist/.nonce 文件，供 render_nginx.py 读取并填入响应头
 */
function noncePlugin(): Plugin {
  let nonce: string;

  return {
    name: 'vite-plugin-nonce',
    
    // 构建开始前生成 nonce
    buildStart() {
      nonce = crypto.randomBytes(16).toString('base64');
      console.log(`[nonce] Generated nonce: ${nonce}`);
    },
    
    // 注入到 index.html（作为 meta 标签，供运行时读取）
    transformIndexHtml(html: string) {
      return html.replace('<head>', `<head>\n    <meta name="csp-nonce" content="${nonce}" />\n`);
    },
    
    // 写 nonce 文件（使用 emitFile）
    generateBundle(_outputOptions, _bundle, isWrite) {
      if (!isWrite) return;
      this.emitFile({
        type: 'asset',
        fileName: '.nonce',
        source: nonce,
      });
      console.log(`[nonce] Emitted as asset: .nonce`);
    },
    
    // 开发模式也注入（供本地调试）
    configureServer(server: ViteDevServer) {
      server.middlewares.use((req, res, next) => {
        if (req.url === '/') {
          const currentNonce = crypto.randomBytes(16).toString('base64');
          res.setHeader('X-CSP-Nonce', currentNonce);
        }
        next();
      });
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
