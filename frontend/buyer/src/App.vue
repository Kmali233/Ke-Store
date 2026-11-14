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
    <div class="shell store-header">
      <RouterLink class="brand" to="/"><span class="brand-mark" aria-hidden="true">Ke</span><span>小Ke商城</span></RouterLink>
      <nav class="store-nav" aria-label="商城导航">
        <RouterLink to="/">商品目录</RouterLink>
        <RouterLink to="/orders">我的订单</RouterLink>
        <RouterLink to="/cart">购物车</RouterLink>
      </nav>
      <form class="search store-search" role="search" @submit.prevent="search">
        <input v-model="keyword" type="search" maxlength="128" aria-label="商品关键词" placeholder="搜索商品">
        <button type="submit" aria-label="搜索">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
            <circle cx="10.5" cy="10.5" r="6"/><path d="m15 15 4.5 4.5" stroke-linecap="round"/>
          </svg>
        </button>
      </form>
      <nav class="account-nav" aria-label="账户">
        <template v-if="session.identity">
          <span class="account-name">{{ session.identity.userInfo.nickname || session.identity.userInfo.username }}</span>
          <button type="button" class="quiet" @click="logout">退出</button>
        </template>
        <template v-else>
          <RouterLink to="/login">登录</RouterLink>
          <RouterLink to="/register">注册</RouterLink>
        </template>
      </nav>
    </div>
  </header>
  <main id="content" class="content" :class="route.path === '/' ? 'content-home' : 'shell'" tabindex="-1">
    <RouterView v-slot="{ Component }">
      <component :is="Component" :key="route.path" />
    </RouterView>
  </main>
  <footer class="site-footer"><div class="shell">小Ke商城</div></footer>
</template>
