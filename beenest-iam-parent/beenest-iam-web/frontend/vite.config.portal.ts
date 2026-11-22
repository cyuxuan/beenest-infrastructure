import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

/**
 * 门户产物:portal.js + portal.css,构建目录即 Web 模块的门户静态资源目录。
 * 与控制台(vite.config.ts)共用依赖、别名与 lib/,入口/出口各自独立:
 * 两端服务路径不同(/portal-static 与 /console-static),不共享 chunk 加载。
 */
export default defineConfig({
  base: '/portal-static/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: '../src/main/resources/portal/static',
    emptyOutDir: true,
    cssCodeSplit: false,
    sourcemap: false,
    target: 'es2022',
    rollupOptions: {
      input: fileURLToPath(new URL('./src/portal.ts', import.meta.url)),
      output: {
        entryFileNames: 'portal.js',
        chunkFileNames: 'chunks/[name]-[hash].js',
        assetFileNames: (assetInfo) => {
          const name = assetInfo.names?.[0] || assetInfo.name || '';
          return name.endsWith('.css') ? 'portal.css' : 'assets/[name]-[hash][extname]';
        },
      },
    },
  },
});
