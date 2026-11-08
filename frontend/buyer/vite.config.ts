import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// Preserve the browser Host so Spring sees the same origin on mutations.
const proxy = {
  '^/api/(user|address)(/|\\?|$)': { target: process.env.BUYER_USER_ORIGIN ?? 'http://127.0.0.1:8083', changeOrigin: false },
  '^/api/(product|category)(/|\\?|$)': { target: process.env.BUYER_PRODUCT_ORIGIN ?? 'http://127.0.0.1:8081', changeOrigin: false },
  '^/api/(cart|order)(/|\\?|$)': { target: process.env.BUYER_ORDER_ORIGIN ?? 'http://127.0.0.1:8082', changeOrigin: false },
}

export default defineConfig({
  plugins: [vue()],
  server: { host: '127.0.0.1', proxy },
  preview: { host: '127.0.0.1', proxy },
})
