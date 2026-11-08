<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText, request } from '../api'
import type { Login } from '../contracts'
import { useSession } from '../session'

const route = useRoute()
const router = useRouter()
const session = useSession()
const register = computed(() => route.path === '/register')
const username = ref('')
const password = ref('')
const nickname = ref('')
const error = ref('')
const submitting = ref(false)

async function submit() {
  if (submitting.value) return
  submitting.value = true
  error.value = ''
  const credentials = { username: username.value.trim(), password: password.value }
  let registered = false
  try {
    if (register.value) {
      await request('/user/register', { method: 'POST', body: { ...credentials, nickname: nickname.value.trim() } })
      registered = true
    }
    const login = await request<Login>('/user/login', { method: 'POST', body: credentials })
    session.signIn(login)
    const next = route.query.next
    const destination = typeof next === 'string' && /^\/(?![/\\])/.test(next) ? next : '/'
    await router.replace(destination)
  } catch (failure) {
    error.value = (registered ? '注册已成功，可前往登录。' : '') + errorText(failure)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="panel padded form-panel">
    <p class="eyebrow">买家账户</p>
    <h1>{{ register ? '创建账户' : '登录商城' }}</h1>
    <p v-if="route.query.expired" class="notice" role="status">登录已失效。重新登录后可返回刚才的页面。</p>
    <form class="stack" @submit.prevent="submit">
      <label class="field">用户名
        <input v-model="username" autocomplete="username" required pattern="[A-Za-z0-9_]{4,50}" maxlength="50">
        <small>4–50 位英文字母、数字或下划线</small>
      </label>
      <label class="field">密码
        <input v-model="password" type="password" :autocomplete="register ? 'new-password' : 'current-password'" required minlength="6" maxlength="20">
        <small>6–20 位</small>
      </label>
      <label v-if="register" class="field">昵称（可选）
        <input v-model="nickname" autocomplete="nickname" maxlength="50">
      </label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button type="submit" :disabled="submitting">{{ submitting ? '正在处理…' : register ? '注册并登录' : '登录' }}</button>
    </form>
    <p class="spaced">
      <RouterLink :to="{ path: register ? '/login' : '/register', query: { next: route.query.next } }">
        {{ register ? '已有账户，去登录' : '没有账户，去注册' }}
      </RouterLink>
    </p>
  </section>
</template>
