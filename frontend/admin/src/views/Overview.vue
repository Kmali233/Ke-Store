<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { safeNext, sections } from '../navigation'
import { useSession } from '../session'
import NavIcon from '../NavIcon.vue'

const route = useRoute()
const { access } = useSession()
const entries = computed(() => sections.flatMap(section => section.entries)
  .filter(entry => access.value?.permissions.includes(entry.permission)))
</script>

<template>
  <header class="page-head">
    <div><p class="eyebrow">OPERATIONS / 工作台</p><h1>运营工作台</h1><p>选择任务进入对应管理页面。</p></div>
  </header>
  <el-alert v-if="route.path === '/forbidden'" title="当前账号没有访问该页面的权限。" type="warning" :closable="false" />
  <section v-if="route.path === '/unavailable'" class="panel state">
    <h2>暂时无法读取管理权限</h2><p>请检查后台服务后重试。</p>
    <RouterLink class="primary-link" :to="safeNext(route.query.next)">重试访问</RouterLink>
  </section>
  <div v-else-if="entries.length" class="work-grid">
    <RouterLink v-for="entry in entries" :key="entry.path" class="work-card" :to="entry.path">
      <span class="work-icon"><NavIcon :name="entry.icon" /></span>
      <div><h2>{{ entry.title }}</h2><p>{{ entry.summary }}</p></div><span class="arrow" aria-hidden="true">→</span>
    </RouterLink>
  </div>
  <section v-else class="panel state"><h2>暂无可处理的管理任务</h2><p>工作台按已分配的权限开放入口。退款审核将在后续阶段接入。</p></section>
</template>
