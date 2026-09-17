import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { resolve } from 'path'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      resolvers: [ElementPlusResolver()],
    }),
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
      '@/app': resolve(__dirname, 'src/app'),
      '@/pages': resolve(__dirname, 'src/pages'),
      '@/widgets': resolve(__dirname, 'src/widgets'),
      '@/features': resolve(__dirname, 'src/features'),
      '@/entities': resolve(__dirname, 'src/entities'),
      '@/shared': resolve(__dirname, 'src/shared'),
    },
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
