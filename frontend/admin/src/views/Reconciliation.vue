<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { errorText, request } from '../api'
import { useLoad } from '../useLoad'

const labels = {
  MATCHED: '一致',
  REPAIRED: '已补齐',
  PROCESSING: '处理中',
  NOT_FOUND: '通道查无单据',
  STATE_MISMATCH: '状态不一致，待人工核对',
  AMOUNT_MISMATCH: '金额不一致，待人工核对',
  IDENTITY_MISMATCH: '交易身份异常，待人工核对',
  DUPLICATE_TRADE: '重复交易，待人工核对',
  QUERY_FAILED: '通道查询失败',
  CHANNEL_FAILED: '通道退款失败',
  APPLY_FAILED: '本地处理失败，待重查',
}
type Kind = 'PAYMENT' | 'REFUND'
interface Entry { orderNo: string; kind: Kind; outcome: keyof typeof labels; attempts: number; checkedAt: string }

const base = '/admin/orders'
const fromRoute = useRoute().query.orderNo
const orderNo = ref(typeof fromRoute === 'string' ? fromRoute : '')
const kind = ref<Kind>('PAYMENT')
const differences = ref(false)
const page = ref(1)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const list = useLoad(signal => {
  const params = new URLSearchParams({ differences: String(differences.value), page: String(page.value) })
  if (orderNo.value.trim()) params.set('orderNo', orderNo.value.trim())
  return request<Entry[]>(base + '/reconciliation?' + params, { signal })
})
onMounted(list.refresh)

function search(next = 1) {
  page.value = next
  void list.refresh()
}

async function run(target?: string, type = kind.value) {
  if (busy.value) return
  if (target !== undefined && !/^\d{14}$/.test(target)) {
    error.value = '请输入完整的 14 位订单号。'
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    if (target !== undefined) {
      const result = await request<Entry>(base + '/' + target + '/reconciliation/' + type, { method: 'POST' })
      notice.value = result.orderNo + ' · ' + labels[result.outcome]
    } else {
      const results = await request<Entry[]>(base + '/reconciliation/run', { method: 'POST', timeoutMs: 30_000 })
      notice.value = results.length ? '本批已核对 ' + results.length + ' 张单据。' : '暂无交易单据。'
    }
  } catch (failure) {
    error.value = errorText(failure) + ' 可刷新已保存的核对记录。'
  } finally {
    page.value = 1
    await list.refresh()
    busy.value = false
  }
}
</script>

<template>
  <header class="page-head">
    <div><h1>交易对账</h1><p>按支付单或退款单查询原通道结果。批次优先处理未核对和最久未核对的单据。</p></div>
    <el-button type="primary" :loading="busy" @click="run()">运行下一批</el-button>
  </header>
  <section class="panel">
    <form class="toolbar" @submit.prevent="search()">
      <el-input v-model="orderNo" aria-label="订单号" placeholder="完整订单号" maxlength="14" clearable />
      <el-select v-model="kind" aria-label="单据类型">
        <el-option value="PAYMENT" label="支付单" />
        <el-option value="REFUND" label="退款单" />
      </el-select>
      <el-checkbox v-model="differences" @change="search()">仅显示差异</el-checkbox>
      <el-button native-type="submit" :disabled="busy">筛选记录</el-button>
      <el-button :disabled="busy || !orderNo.trim()" @click="run(orderNo.trim())">按订单号核对</el-button>
    </form>
    <p v-if="error || list.error.value" class="error-text" role="alert">{{ error || list.error.value }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <el-table v-loading="list.loading.value" :data="list.data.value ?? []" empty-text="暂无对账记录">
      <el-table-column prop="orderNo" label="订单号" min-width="150" />
      <el-table-column label="单据" width="90">
        <template #default="{ row }">{{ row.kind === 'PAYMENT' ? '支付单' : '退款单' }}</template>
      </el-table-column>
      <el-table-column label="最近结果" min-width="200">
        <template #default="{ row }">{{ labels[(row as Entry).outcome] }}</template>
      </el-table-column>
      <el-table-column prop="attempts" label="核对次数" width="100" />
      <el-table-column label="核对时间（UTC）" min-width="170">
        <template #default="{ row }">{{ row.checkedAt.replace('T', ' ').slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="busy" @click="run(row.orderNo, row.kind)">重查</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button :disabled="page === 1 || list.loading.value || busy" @click="search(page - 1)">上一页</el-button>
    <el-button :disabled="(list.data.value?.length ?? 0) < 50 || list.loading.value || busy" @click="search(page + 1)">下一页</el-button>
  </section>
</template>

<style scoped>
.toolbar .el-input, .toolbar .el-select { width: min(100%, 200px); }
</style>
