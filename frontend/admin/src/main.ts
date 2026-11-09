import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import {
  ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElConfigProvider, ElDialog, ElDrawer,
  ElForm, ElFormItem, ElInput, ElInputNumber, ElLoading, ElOption, ElPagination, ElSelect,
  ElTable, ElTableColumn, ElTag,
} from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { ApiError } from './api'
import { sections } from './navigation'
import { useSession } from './session'
import './style.css'

const views = {
  '/products': () => import('./views/Catalog.vue'),
  '/categories': () => import('./views/Categories.vue'),
  '/inventory': () => import('./views/Inventory.vue'),
  '/orders': () => import('./views/Orders.vue'),
  '/audit': () => import('./views/Audit.vue'),
  '/accounts': () => import('./views/Accounts.vue'),
}
const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/', component: () => import('./views/Overview.vue'), meta: { title: '工作台' } },
    ...sections.flatMap(section => section.entries.map(entry => ({
      path: entry.path,
      component: views[entry.path as keyof typeof views],
      meta: { title: entry.title, permission: entry.permission },
    }))),
    { path: '/login', component: () => import('./views/Login.vue'), meta: { public: true, title: '管理登录' } },
    { path: '/forbidden', component: () => import('./views/Overview.vue'), meta: { title: '访问受限' } },
    { path: '/unavailable', component: () => import('./views/Overview.vue'), meta: { public: true, title: '服务暂不可用' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async to => {
  if (to.meta.public) return
  const session = useSession()
  if (!session.token.value) return { path: '/login', query: { next: to.fullPath } }
  try {
    const access = await session.refreshAccess()
    if (!session.token.value) return { path: '/login' }
    if (typeof to.meta.permission === 'string' && !access.permissions.includes(to.meta.permission)) {
      return { path: '/forbidden' }
    }
  } catch (failure) {
    if (failure instanceof ApiError && failure.code === 401) {
      return { path: '/login', query: { expired: '1', next: to.fullPath } }
    }
    return { path: '/unavailable', query: { next: to.fullPath } }
  }
})

const app = createApp(App).use(router).use(ElLoading)
for (const component of [
  ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElConfigProvider, ElDialog, ElDrawer,
  ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElPagination, ElSelect,
  ElTable, ElTableColumn, ElTag,
]) {
  app.use(component)
}
app.mount('#app')
