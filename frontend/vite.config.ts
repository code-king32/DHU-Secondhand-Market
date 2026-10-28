import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // 前端只请求同源 /api，由 Vite 转发到后端，避开跨域且 Cookie 同源
      '/api': {
        target: 'http://localhost:8081', // 后端端口；用验收库演示账号时改成 8081
		// 我改了
        changeOrigin: false,
      },
    },
  },
})
