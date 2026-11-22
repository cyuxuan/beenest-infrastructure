import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

/**
 * 产物固定命名:console/index.html(Thymeleaf)直接引用 /console-static/app.js 与 console.css,
 * 避免模板里维护 hash 文件名。构建目录即 Web 模块的静态资源目录。
 */
export default defineConfig({
  base: '/console-static/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: '../src/main/resources/console/static',
    emptyOutDir: true,
    cssCodeSplit: false,
    sourcemap: false,
    target: 'es2022',
    rollupOptions: {
      input: fileURLToPath(new URL('./src/main.ts', import.meta.url)),
      output: {
        entryFileNames: 'app.js',
        chunkFileNames: 'chunks/[name]-[hash].js',
        assetFileNames: (assetInfo) => {
          const name = assetInfo.names?.[0] || assetInfo.name || '';
          return name.endsWith('.css') ? 'console.css' : 'assets/[name]-[hash][extname]';
        },
      },
    },
  },
  server: {
    port: 5175,
    proxy: {
      '/api': {
        target: process.env.IAM_CONSOLE_PROXY || 'http://localhost:8081',
        changeOrigin: false,
      },
    },
  },
});
