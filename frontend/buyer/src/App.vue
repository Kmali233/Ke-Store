<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from './session'

const session = useSession()
const route = useRoute()
const router = useRouter()
const keyword = ref('')

watch(() => route.query.q, query => {
  keyword.value = typeof query === 'string' ? query : ''
}, { immediate: true })

watch(() => session.identity, identity => {
  if (!identity && route.meta.buyer) {
    void router.replace({ path: '/login', query: { next: route.fullPath, expired: '1' } })
  }
})

function search() {
  const q = keyword.value.trim()
  void router.push({ path: '/', query: q ? { q } : {}, hash: '#catalog' })
}

async function logout() {
  await router.push('/')
  session.signOut()
}
</script>

<template>
  <a class="skip-link" href="#content">跳到内容</a>
  <header class="site-header">
    <div class="utility">
      <div class="shell utility-inner">
        <RouterLink class="utility-home" to="/">小Ke商城</RouterLink>
        <nav class="utility-nav" aria-label="账户与订单">
          <template v-if="session.identity">
            <span class="account-name">{{ session.identity.userInfo.nickname || session.identity.userInfo.username }}</span>
            <button type="button" class="utility-logout" @click="logout">退出</button>
            <RouterLink to="/addresses">收货地址</RouterLink>
          </template>
          <template v-else>
            <RouterLink to="/login">登录</RouterLink>
            <RouterLink to="/register">注册</RouterLink>
          </template>
          <RouterLink to="/orders">我的订单</RouterLink>
          <RouterLink to="/cart" class="utility-cart">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
              <path d="M2 4h3l2.5 11h12l2-8H6M9 20h.01M18 20h.01" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            购物车
          </RouterLink>
        </nav>
      </div>
    </div>
    <div class="shell store-header">
      <RouterLink class="brand" to="/"><span class="brand-mark">Ke</span><span>小Ke商城</span></RouterLink>
      <nav class="store-nav" aria-label="商城导航">
        <RouterLink to="/">商城首页</RouterLink>
        <RouterLink :to="{ path: '/', hash: '#catalog' }">全部商品</RouterLink>
      </nav>
      <form class="search store-search" role="search" @submit.prevent="search">
        <input v-model="keyword" type="search" maxlength="128" aria-label="商品关键词" placeholder="搜索商品">
        <button type="submit" aria-label="搜索">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
            <circle cx="10.5" cy="10.5" r="6"/><path d="m15 15 4.5 4.5" stroke-linecap="round"/>
          </svg>
        </button>
      </form>
    </div>
  </header>
  <main id="content" class="shell content" :class="{ 'content-home': route.path === '/' }" tabindex="-1">
    <RouterView v-slot="{ Component }">
      <component :is="Component" :key="route.path" />
    </RouterView>
  </main>
  <footer class="site-footer"><div class="shell">小Ke商城 <span>买家端 · 模拟支付环境</span></div></footer>
</template>
