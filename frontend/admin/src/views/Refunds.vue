<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { errorText, request } from '../api'
import { useLoad } from '../useLoad'

const labels = {
  REQUESTED: '待审核',
  PROCESSING: '通道处理中',
  REJECTED: '已拒绝',
  SUCCEEDED: '已退款',
  FAILED: '通道失败，待核对',
}
type State = keyof typeof labels
interface Refund {
  orderNo: string
  refundNo: string
  amount: number
  status: State
  updatedAt: string
}

const base = '/admin/orders'
const orderNo = ref('')
const status = ref<State>()
const page = ref(1)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })
const list = useLoad(signal => {
  const params = new URLSearchParams({ page: String(page.value) })
  if (status.value) params.set('status', status.value)
  if (orderNo.value.trim()) params.set('orderNo', orderNo.value.trim())
  return request<Refund[]>(base + '/refunds?' + params, { signal })
})
onMounted(list.refresh)

function search(next = 1) {
  page.value = next
  void list.refresh()
}

async function review(row: Refund, action: 'approve' | 'reject' | 'retry') {
  if (busy.value) return
  const prompt = action === 'approve' ? '同意将实付金额全额退回？'
    : action === 'reject' ? '拒绝这笔整单退款申请？' : '先查询通道，查无退款时按原退款请求号重试？'
  if (!window.confirm(row.orderNo + ' · ' + prompt)) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await request<{ status: State }>(base + '/' + row.orderNo + '/refund/' + action, { method: 'POST', timeoutMs: 25_000 })
    notice.value = row.orderNo + ' · ' + labels[result.status]
  } catch (failure) {
    error.value = errorText(failure) + ' 请查看退款当前状态。'
  } finally {
    await list.refresh()
    busy.value = false
  }
}
</script>

<template>
  <header class="page-head"><div><h1>退款审核</h1><p>处理整单退款申请。</p></div></header>
  <section class="panel">
    <form class="toolbar" @submit.prevent="search()">
      <el-input v-model="orderNo" aria-label="订单号" placeholder="完整订单号" maxlength="14" clearable />
      <el-select v-model="status" aria-label="退款状态" placeholder="所有状态" clearable>
        <el-option v-for="(label, key) in labels" :key="key" :value="key" :label="label" />
      </el-select>
      <el-button type="primary" native-type="submit" :disabled="busy">查询</el-button>
    </form>
    <p v-if="error || list.error.value" class="error-text" role="alert">{{ error || list.error.value }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <el-table v-loading="list.loading.value" :data="list.data.value ?? []" row-key="refundNo" empty-text="暂无退款申请">
      <el-table-column prop="orderNo" label="订单号" min-width="150" />
      <el-table-column prop="refundNo" label="退款请求号" min-width="235" />
      <el-table-column label="退款金额" width="110">
        <template #default="{ row }">{{ money.format(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="状态" min-width="135">
        <template #default="{ row }">{{ labels[(row as Refund).status] }}</template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="160">
        <template #default="{ row }">{{ row.updatedAt.replace('T', ' ') }}</template>
      </el-table-column>
      <el-table-column label="操作" width="155">
        <template #default="{ row }">
          <template v-if="row.status === 'REQUESTED'">
            <el-button link type="primary" :disabled="busy" @click="review(row as Refund, 'approve')">同意退款</el-button>
            <el-button link type="danger" :disabled="busy" @click="review(row as Refund, 'reject')">拒绝</el-button>
          </template>
          <el-button v-else-if="row.status === 'PROCESSING'" link type="primary" :disabled="busy"
                     @click="review(row as Refund, 'retry')">重查或重试原请求</el-button>
          <RouterLink v-else-if="row.status === 'FAILED'" :to="{ path: '/reconciliation', query: { orderNo: row.orderNo } }">去对账</RouterLink>
        </template>
      </el-table-column>
    </el-table>
    <el-button :disabled="page === 1 || list.loading.value || busy" @click="search(page - 1)">上一页</el-button>
    <el-button :disabled="(list.data.value?.length ?? 0) < 50 || list.loading.value || busy" @click="search(page + 1)">下一页</el-button>
  </section>
</template>

<style scoped>
.toolbar .el-input, .toolbar .el-select { width: min(100%, 220px); }
</style>
