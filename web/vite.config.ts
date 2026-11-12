import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [
      vue(),
      {
        name: 'management-entry',
        configureServer(server) {
          server.middlewares.use('/manage-entry', (_request, response) => {
            response.writeHead(302, { Location: '/manage' })
            response.end()
          })
        },
      },
    ],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: {
      host: '127.0.0.1',
      port: Number(env.VITE_DEV_PORT || 5178),
      proxy: {
        '/api/v1': env.VITE_BACKEND_PROXY_TARGET || 'http://127.0.0.1:19180',
      },
    },
  }
})
