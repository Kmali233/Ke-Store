<script setup lang="ts">
import { computed, ref } from 'vue'
import { request } from '../api'
import { useLoad } from '../useLoad'

type Value = string | number | boolean | number[] | null
type Change = { before: Value; after: Value }
interface Entry {
  id: number
  actorType: string
  actorId: number | null
  action: string
  targetType: string
  targetId: string
  result: string
  changes: Record<string, Change> | null
  traceId: string
  source: string
  createdAt: string
  integrityValid: boolean
}
interface AuditPage {
  total: number
  pageNum: number
  pageSize: number
  records: Entry[]
}

const actions: Record<string, string> = {
  PRODUCT_CREATE: '新建商品', PRODUCT_UPDATE: '修改商品', PRODUCT_DELETE: '删除商品',
  PRODUCT_PRICE_CHANGE: '修改商品价格', PRODUCT_STATUS_CHANGE: '修改商品状态', PRODUCT_ATTR_REPLACE: '更新商品属性',
  CATEGORY_CREATE: '新建分类', CATEGORY_UPDATE: '修改分类', CATEGORY_DELETE: '删除分类',
  SKU_CREATE: '新建 SKU', SKU_UPDATE: '修改 SKU', SKU_DELETE: '删除 SKU',
  SKU_PRICE_CHANGE: '修改 SKU 价格', SKU_STATUS_CHANGE: '修改 SKU 状态',
  ADMIN_CREATE: '新建账号', ADMIN_ROLES_CHANGE: '分配角色', ADMIN_STATUS_CHANGE: '修改账号状态',
  ADMIN_CREDENTIAL_RECOVER: '恢复管理凭证', ORDER_SHIP: '订单发货',
  INVENTORY_ADJUST: '库存调整', REFUND_REVIEW: '退款审核',
}
const targets: Record<string, string> = {
  PRODUCT: '商品', SKU: 'SKU', CATEGORY: '分类', ORDER: '订单', ADMIN_ACCOUNT: '后台账号',
}
const fields: Record<string, string> = {
  PRICE: '价格', STATUS: '状态', STOCK: '库存', DELETED: '删除标记', ROLE_IDS: '角色 ID',
  PARENT_ID: '上级分类 ID', CATEGORY_ID: '分类 ID', PROFILE_CHANGED: '资料变更',
  ATTR_COUNT: '属性数量', SESSION_REVOKED: '撤销会话',
}
const emptyFilters = () => ({ action: '', targetType: '', targetId: '', actorId: '', traceId: '', from: '', to: '' })
const form = ref(emptyFilters())
const filters = ref<Record<string, string>>({})
const page = ref(1)
const pageSize = 15
const filterError = ref('')
const inspected = ref<Entry>()
const { data, loading, error, refresh } = useLoad((signal) => {
  const query = new URLSearchParams({ ...filters.value, pageNum: String(page.value), pageSize: String(pageSize) })
  return request<AuditPage>('/admin/audit/page?' + query.toString(), { signal })
})
const pageCount = computed(() => Math.max(1, Math.min(1000, Math.ceil((data.value?.total ?? 0) / pageSize))))
const timeFormat = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium', hour12: false })

function localTime(value: string) {
  const time = new Date(value + 'Z')
  return Number.isNaN(time.getTime()) ? value + ' UTC' : timeFormat.format(time)
}

function display(value: Value) {
  if (value === null) return '—'
  if (Array.isArray(value)) return value.length ? value.join('、') : '空列表'
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value)
}

async function applyFilters() {
  filterError.value = ''
  const { from, to, ...basic } = form.value
  const next = Object.fromEntries(
    Object.entries(basic).map(([name, value]) => [name, (value ?? '').trim()]).filter(([, value]) => value),
  )
  if (next.targetId && !/^[1-9]\d{0,18}$/.test(next.targetId)) {
    filterError.value = '对象 ID 须为正整数，最多 19 位。'
    return
  }
  if (next.actorId && (!/^[1-9]\d{0,18}$/.test(next.actorId) || BigInt(next.actorId) > 9223372036854775807n)) {
    filterError.value = '操作者 ID 须为有效的正整数。'
    return
  }
  if (next.traceId && !/^[a-f0-9]{32}$/.test(next.traceId)) {
    filterError.value = '请求追踪号须为 32 位小写十六进制字符。'
    return
  }
  const start = from ? Date.parse(from) : undefined
  const end = to ? Date.parse(to) : Date.now()
  if (Number.isNaN(end) || (start !== undefined && (Number.isNaN(start) || start >= end
    || end - start > 31 * 24 * 60 * 60 * 1000))) {
    filterError.value = '起始时间须早于结束时间，查询跨度最多 31 天。'
    return
  }
  if (start !== undefined) next.from = new Date(start).toISOString()
  if (to) next.to = new Date(end).toISOString()

  filters.value = next
  page.value = 1
  await refresh()
}

function resetFilters() {
  form.value = emptyFilters()
  void applyFilters()
}

function goToPage(next: number) {
  if (loading.value || page.value === next) return
  page.value = next
  void refresh()
}

void refresh()
</script>

<template>
  <section class="page-head">
    <div>
      <h1>审计日志</h1>
      <p>查询已记录的管理操作，并查看服务端校验过的变更摘要。</p>
    </div>
  </section>

  <section class="panel" aria-label="审计查询">
    <form class="audit-filters" @submit.prevent="applyFilters">
      <div class="toolbar audit-toolbar">
        <label class="audit-field">操作
          <el-select v-model="form.action" placeholder="全部操作" clearable aria-label="操作">
            <el-option v-for="(label, code) in actions" :key="code" :label="label" :value="code" />
          </el-select>
        </label>
        <label class="audit-field">对象类型
          <el-select v-model="form.targetType" placeholder="全部对象" clearable aria-label="对象类型">
            <el-option v-for="(label, code) in targets" :key="code" :label="label" :value="code" />
          </el-select>
        </label>
        <label class="audit-field" for="audit-target-id">对象 ID
          <el-input id="audit-target-id" v-model="form.targetId" placeholder="例如订单编号" inputmode="numeric" maxlength="19" />
        </label>
        <div class="row-actions audit-actions">
          <el-button type="primary" native-type="submit" :loading="loading">查询</el-button>
          <el-button native-type="button" :disabled="loading" @click="resetFilters">重置</el-button>
        </div>
      </div>
      <details>
        <summary>更多筛选</summary>
        <div class="audit-advanced">
          <label class="audit-field" for="audit-actor-id">操作者 ID
            <el-input id="audit-actor-id" v-model="form.actorId" inputmode="numeric" maxlength="19" />
          </label>
          <label class="audit-field" for="audit-trace-id">请求追踪号
            <el-input id="audit-trace-id" v-model="form.traceId" maxlength="32" spellcheck="false" />
          </label>
          <label class="audit-field" for="audit-from">起始时间
            <input id="audit-from" v-model="form.from" type="datetime-local">
          </label>
          <label class="audit-field" for="audit-to">结束时间
            <input id="audit-to" v-model="form.to" type="datetime-local">
          </label>
        </div>
      </details>
      <p v-if="filterError" class="error-text" role="alert">{{ filterError }}</p>
    </form>

    <div v-if="error" class="audit-failure">
      <p class="error-text" role="alert">{{ error }}</p>
      <el-button :loading="loading" @click="refresh">重试</el-button>
    </div>
    <el-table v-else v-loading="loading" :data="data?.records ?? []" empty-text="没有符合条件的审计记录" row-key="id">
      <el-table-column label="时间（本地）" min-width="160">
        <template #default="{ row }">{{ localTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="195">
        <template #default="{ row }">
          <strong>{{ actions[row.action] ?? row.action }}</strong>
          <div class="audit-secondary">{{ row.action }}</div>
        </template>
      </el-table-column>
      <el-table-column label="对象" min-width="155">
        <template #default="{ row }">{{ targets[row.targetType] ?? row.targetType }} #{{ row.targetId }}</template>
      </el-table-column>
      <el-table-column label="操作者" width="125">
        <template #default="{ row }">{{ row.actorId == null ? '系统' : '后台账号 #' + row.actorId }}</template>
      </el-table-column>
      <el-table-column label="摘要校验" width="115">
        <template #default="{ row }">
          <el-tag :type="row.integrityValid ? 'success' : 'danger'" effect="plain">
            {{ row.integrityValid ? '通过' : '未通过' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="查看" width="120">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="loading" @click="inspected = row as Entry">查看变更</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="data && !error" class="audit-footer">
      <span class="audit-secondary">共 {{ data.total }} 条<span v-if="pageCount === 1000">，最多浏览前 1000 页</span></span>
      <el-pagination :current-page="page" :page-count="pageCount" :pager-count="5"
        :disabled="loading" layout="prev, pager, next" @update:current-page="goToPage" />
    </div>
  </section>

  <el-dialog :model-value="!!inspected" title="审计变更" width="min(590px, calc(100vw - 32px))"
    @update:model-value="value => { if (!value) inspected = undefined }">
    <template v-if="inspected">
      <dl class="audit-detail">
        <dt>操作</dt><dd>{{ actions[inspected.action] ?? inspected.action }}</dd>
        <dt>对象</dt><dd>{{ targets[inspected.targetType] ?? inspected.targetType }} #{{ inspected.targetId }}</dd>
        <dt>来源</dt><dd>{{ inspected.source }}</dd>
        <dt>请求追踪号</dt><dd><code>{{ inspected.traceId }}</code></dd>
      </dl>
      <p v-if="!inspected.integrityValid" class="error-text" role="alert">服务端摘要校验未通过，变更内容已隐藏。</p>
      <template v-else>
        <h3>变更字段</h3>
        <dl v-if="inspected.changes && Object.keys(inspected.changes).length" class="audit-detail">
          <template v-for="(change, field) in inspected.changes" :key="field">
            <dt>{{ fields[field] ?? field }}</dt>
            <dd>{{ display(change.before) }} <span aria-label="变更为">→</span> {{ display(change.after) }}</dd>
          </template>
        </dl>
        <p v-else>该条记录没有字段差异。</p>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.audit-filters { padding: 1rem; border-bottom: 1px solid #dfe7f1; }
.audit-toolbar { padding: 0; align-items: flex-end; }
.audit-field { min-width: 0; display: grid; flex: 1 1 145px; gap: .35rem; color: #37475e; font-size: .83rem; }
.audit-actions { align-self: end; }
.audit-filters details { margin-top: .75rem; }
.audit-filters summary { width: fit-content; cursor: pointer; color: #305686; font-size: .86rem; }
.audit-advanced { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 190px), 1fr)); gap: .75rem; margin-top: .7rem; }
.audit-field > input { min-width: 0; width: 100%; padding: .35rem .65rem; border: 1px solid #d2dce9; border-radius: 4px; color: inherit; font: inherit; background: #fff; }
.audit-secondary { color: #526176; font-size: .8rem; }
.audit-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: .55rem; padding: .75rem 1rem; }
.audit-failure { padding: 1rem; }
.audit-detail { display: grid; grid-template-columns: minmax(5rem, auto) minmax(0, 1fr); gap: .45rem .75rem; margin: 0 0 1rem; }
.audit-detail dt { color: #526176; }
.audit-detail dd { margin: 0; overflow-wrap: anywhere; }
</style>
