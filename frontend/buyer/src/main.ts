import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import { useSession } from './session'
import './style.css'

const pinia = createPinia()
const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: (to, _from, savedPosition) => savedPosition ?? (to.hash ? { el: to.hash } : { top: 0 }),
  routes: [
    { path: '/', component: () => import('./views/Catalog.vue') },
    { path: '/products/:id(\\d+)', component: () => import('./views/Product.vue') },
    { path: '/login', component: () => import('./views/SignIn.vue') },
    { path: '/register', component: () => import('./views/SignIn.vue') },
    { path: '/cart', component: () => import('./views/Cart.vue'), meta: { buyer: true } },
    { path: '/addresses', component: () => import('./views/Addresses.vue'), meta: { buyer: true } },
    { path: '/checkout', component: () => import('./views/Checkout.vue'), meta: { buyer: true } },
    { path: '/orders', component: () => import('./views/Orders.vue'), meta: { buyer: true } },
    { path: '/orders/:number(\\d+)/demo-checkout', component: () => import('./views/DemoCheckout.vue'), meta: { buyer: true } },
    { path: '/orders/:number(\\d+)', component: () => import('./views/Order.vue'), meta: { buyer: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(to => {
  if (to.meta.buyer && !useSession(pinia).identity) {
    return { path: '/login', query: { next: to.fullPath } }
  }
})

createApp(App).use(pinia).use(router).mount('#app')
