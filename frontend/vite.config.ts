import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': resolve(__dirname, 'src') },
  },
  server: {
    proxy: {
      // 后端统一走网关（docker 发布 8000），api 容器不对宿主机开放端口
      '/api': 'http://localhost:8000',
      // 图片静态资源由 nginx 容器提供（docker 发布 80）
      '/files': 'http://localhost:80',
    },
  },
})
