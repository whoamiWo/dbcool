import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
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
