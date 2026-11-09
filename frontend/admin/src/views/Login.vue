<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText, request } from '../api'
import { safeNext } from '../navigation'
import { useSession } from '../session'

const route = useRoute()
const router = useRouter()
const session = useSession()
const username = ref('')
const password = ref('')
const busy = ref(false)
const failure = ref('')

async function login() {
  if (busy.value) return
  busy.value = true
  failure.value = ''
  try {
    const result = await request<{ token: string }>('/admin/auth/login', {
      method: 'POST', body: { username: username.value.trim(), password: password.value },
    })
    session.start(result.token)
    await router.replace(safeNext(route.query.next))
  } catch (error) {
    failure.value = errorText(error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <aside class="login-art" aria-label="小Ke运营管理">
      <div class="wordmark"><span>Ke</span> 小Ke运营管理</div>
      <div class="login-copy">
        <p class="eyebrow">XIAOKE OPERATIONS</p>
        <h1>商城运营工作台</h1>
        <p>在一个工作台维护商品、核对库存和处理订单。</p>
      </div>
      <p class="login-foot">小Ke商城 · 管理端</p>
    </aside>
    <section class="login-form">
      <p class="eyebrow">账号访问</p>
      <h2>管理员登录</h2>
      <p class="subtle">使用已分配的后台账号进入工作台。</p>
      <el-alert v-if="route.query.expired" title="登录会话已失效，请重新登录。" type="warning" :closable="false" />
      <form @submit.prevent="login">
        <label for="admin-username">管理员账号</label>
        <el-input id="admin-username" v-model="username" autocomplete="username" required maxlength="50" />
        <label for="admin-password">密码</label>
        <el-input id="admin-password" v-model="password" type="password" autocomplete="current-password" required maxlength="72" show-password />
        <p v-if="failure" class="error-text" role="alert">{{ failure }}</p>
        <el-button type="primary" native-type="submit" :loading="busy">登录管理端</el-button>
      </form>
    </section>
  </main>
</template>
