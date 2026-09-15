import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端独立运行在 5173 端口，后端在 8080。
// 这里配置 /api 代理到后端，避免浏览器跨域（也可依赖后端已开的 CORS）。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
