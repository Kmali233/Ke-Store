<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useRoute, useRouter } from 'vue-router'
import { errorText, request } from './api'
import { sections } from './navigation'
import { useSession } from './session'
import NavIcon from './NavIcon.vue'

const route = useRoute()
const router = useRouter()
const { access, token, clear } = useSession()
const navOpen = ref(false)
const signingOut = ref(false)
const logoutError = ref('')
const navigation = computed(() => sections.map(section => ({
  name: section.name,
  entries: section.entries.filter(entry => access.value?.permissions.includes(entry.permission)),
})).filter(section => section.entries.length))

watch(() => route.fullPath, () => { navOpen.value = false })
watch(token, current => {
  if (!current && route.path !== '/login') {
    void router.replace({ path: '/login', query: { expired: '1', next: route.fullPath } })
  }
})
watch(access, value => {
  if (value && typeof route.meta.permission === 'string' && !value.permissions.includes(route.meta.permission)) {
    void router.replace('/forbidden')
  }
})

async function logout() {
  if (signingOut.value) return
  signingOut.value = true
  logoutError.value = ''
  try {
    await request('/admin/auth/logout', { method: 'POST' })
    await router.replace('/login')
    clear()
  } catch (error) {
    if (token.value) logoutError.value = errorText(error)
  } finally {
    signingOut.value = false
  }
}
</script>

<template>
  <el-config-provider :locale="zhCn">
  <RouterView v-if="route.path === '/login'" />
  <div v-else class="console" :class="{ 'nav-open': navOpen }">
    <a class="skip" href="#workspace">跳到工作区</a>
    <aside id="console-nav" class="sidebar">
      <RouterLink class="wordmark" to="/"><span>Ke</span> 小Ke运营管理</RouterLink>
      <nav aria-label="管理导航">
        <RouterLink class="nav-link" to="/"><NavIcon name="home" />工作台</RouterLink>
        <div v-for="section in navigation" :key="section.name" class="nav-group">
          <p>{{ section.name }}</p>
          <RouterLink v-for="entry in section.entries" :key="entry.path" class="nav-link" :to="entry.path">
            <NavIcon :name="entry.icon" />{{ entry.title }}
          </RouterLink>
        </div>
      </nav>
      <span class="side-foot">XIAOKE OPERATIONS</span>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <button class="menu-toggle" type="button" aria-controls="console-nav" :aria-expanded="navOpen"
          @click="navOpen = !navOpen">{{ navOpen ? '收起菜单' : '打开菜单' }}</button>
        <span class="breadcrumb">运营管理 <span aria-hidden="true">/</span> <strong>{{ route.meta.title }}</strong></span>
        <div v-if="token" class="identity">
          <span v-if="access" class="avatar" aria-hidden="true">{{ access.username.slice(0, 1).toUpperCase() }}</span>
          <div v-if="access" class="identity-info"><strong>{{ access.username }}</strong><small>{{ access.roles.map(role => role.name).join(' · ') }}</small></div>
          <el-button :loading="signingOut" @click="logout">退出登录</el-button>
        </div>
      </header>
      <main id="workspace" class="content" tabindex="-1">
        <el-alert v-if="logoutError" :title="logoutError" type="error" show-icon />
        <RouterView />
      </main>
    </div>
  </div>
  </el-config-provider>
</template>
