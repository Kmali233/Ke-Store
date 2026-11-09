import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const proxy = {
  '^/api/(admin/catalog|product|category)(/|\\?|$)': {
    target: process.env.ADMIN_PRODUCT_ORIGIN ?? 'http://127.0.0.1:8081', changeOrigin: false,
  },
  '^/api/(admin/orders|order)(/|\\?|$)': {
    target: process.env.ADMIN_ORDER_ORIGIN ?? 'http://127.0.0.1:8082', changeOrigin: false,
  },
  '^/api/admin/(auth|access|audit)(/|\\?|$)': {
    target: process.env.ADMIN_SERVICE_ORIGIN ?? 'http://127.0.0.1:8085', changeOrigin: false,
  },
}

export default defineConfig({
  plugins: [vue()],
  server: { host: '127.0.0.1', port: 5174, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 4174, strictPort: true, proxy },
})
