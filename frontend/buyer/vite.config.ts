import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// Preserve the browser Host so Spring sees the same origin on mutations.
const proxy = {
  '^/api/user(/|\\?|$)': { target: process.env.BUYER_USER_ORIGIN ?? 'http://127.0.0.1:8083', changeOrigin: false },
  '^/api/(product|category)(/|\\?|$)': { target: process.env.BUYER_PRODUCT_ORIGIN ?? 'http://127.0.0.1:8081', changeOrigin: false },
  '^/api/(cart|order)(/|\\?|$)': { target: process.env.BUYER_ORDER_ORIGIN ?? 'http://127.0.0.1:8082', changeOrigin: false },
}

const localWechatImage = fileURLToPath(new URL('../../output/local-payment/wechat-collection.jpg', import.meta.url))
const localWechatPath = '/local-payment/wechat-collection.jpg'

export default defineConfig(({ command, isPreview }) => {
  const showLocalWechat = command === 'serve' && !isPreview && existsSync(localWechatImage)
  return {
    plugins: [
      vue(),
      {
        name: 'local-wechat-collection',
        configureServer(server) {
          if (!showLocalWechat) return
          server.middlewares.use((req, res, next) => {
            if (req.url !== localWechatPath || req.method !== 'GET') return next()
            const image = readFileSync(localWechatImage)
            res.setHeader('Content-Type', 'image/jpeg')
            res.setHeader('Cache-Control', 'no-store')
            res.end(image)
          })
        },
      },
    ],
    define: { 'import.meta.env.VITE_LOCAL_WECHAT_QR': JSON.stringify(showLocalWechat ? localWechatPath : '') },
    server: { host: '127.0.0.1', proxy },
    preview: { host: '127.0.0.1', proxy },
  }
})
