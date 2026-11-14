<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { request } from '../api'
import { useLoad } from '../useLoad'

interface OrderSummary {
  orderNo: string
  status: number
  statusDesc: string
  totalAmount: number
  createTime: string
}
interface OrderItem {
  productName: string
  skuName: string | null
  quantity: number
  amount: number
}
interface OrderDetail extends Omit<OrderSummary, 'createTime'> {
  remark: string | null
  items: OrderItem[]
}
interface Page<T> { records: T[]; total: number }

const statuses = ['待付款', '已付款', '历史备货', '历史发货', '历史签收', '已取消']
const orderNo = ref('')
const status = ref<number>()
const filters = ref<{ orderNo: string; status?: number }>({ orderNo: '' })
const page = ref(1)
const pageSize = 10
const selectedOrderNo = ref('')
const detailOpen = ref(false)
const filterError = ref('')

const list = useLoad(signal => {
  const params = new URLSearchParams({ pageNum: String(page.value), pageSize: String(pageSize) })
  if (filters.value.orderNo) params.set('orderNo', filters.value.orderNo)
  if (filters.value.status !== undefined) params.set('status', String(filters.value.status))
  return request<Page<OrderSummary>>('/admin/orders/page?' + params, { signal })
})
const detail = useLoad(signal => request<OrderDetail>(detailPath(selectedOrderNo.value), { signal }))
const selected = computed(() =>
  detail.data.value?.orderNo === selectedOrderNo.value ? detail.data.value : undefined,
)
const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })

onMounted(list.refresh)
watch(page, list.refresh)

function detailPath(number: string) { return '/admin/orders/' + encodeURIComponent(number) }
function dateTime(value: string | null) { return value ? value.replace('T', ' ').slice(0, 19) : '—' }
function statusType(status: number) {
  if (status === 1 || status === 2) return 'warning'
  if (status === 3 || status === 4) return 'success'
  return 'info'
}

function search() {
  const number = orderNo.value.trim()
  filterError.value = ''
  if (number && !/^\d{14}$/.test(number)) {
    filterError.value = '订单号须为 14 位数字。'
    return
  }
  filters.value = { orderNo: number, status: typeof status.value === 'number' ? status.value : undefined }
  if (page.value === 1) void list.refresh()
  else page.value = 1
}

function openDetail(row: OrderSummary) {
  selectedOrderNo.value = row.orderNo
  detailOpen.value = true
  void detail.refresh()
}

</script>

<template>
  <header class="page-head">
    <div><h1>订单查询</h1></div>
  </header>

  <section class="panel order-workspace" aria-label="订单列表">
    <form class="toolbar" @submit.prevent="search">
      <el-input v-model="orderNo" aria-label="订单号" placeholder="输入完整订单号" maxlength="14" clearable />
      <el-select v-model="status" clearable aria-label="订单状态" placeholder="全部状态">
        <el-option v-for="(label, value) in statuses" :key="value" :value="value" :label="label" />
      </el-select>
      <el-button type="primary" native-type="submit" :loading="list.loading.value">查询订单</el-button>
    </form>
    <p v-if="filterError" class="error-text" role="alert">{{ filterError }}</p>
    <div v-if="list.error.value" class="error-text" role="alert">{{ list.error.value }}
      <el-button link type="primary" @click="list.refresh">重新加载</el-button>
    </div>
    <el-table v-else v-loading="list.loading.value" :data="list.data.value?.records ?? []" row-key="orderNo" empty-text="没有符合条件的订单">
      <el-table-column prop="orderNo" label="订单号" min-width="155" />
      <el-table-column label="状态" width="108">
        <template #default="{ row }"><el-tag :type="statusType(row.status)" effect="light">{{ row.statusDesc }}</el-tag></template>
      </el-table-column>
      <el-table-column label="订单金额" min-width="125" align="right">
        <template #default="{ row }">{{ money.format(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="下单时间" min-width="170">
        <template #default="{ row }">{{ dateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="145">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row as OrderSummary)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="(list.data.value?.total ?? 0) > pageSize" v-model:current-page="page"
      layout="prev, pager, next" :page-size="pageSize" :total="list.data.value?.total ?? 0" />
  </section>

  <el-dialog v-model="detailOpen" title="订单详情" width="min(690px, calc(100vw - 28px))">
    <p v-if="detail.loading.value" role="status">正在读取订单详情…</p>
    <div v-else-if="detail.error.value" class="error-text" role="alert">{{ detail.error.value }}
      <el-button link type="primary" @click="detail.refresh">重新加载</el-button>
    </div>
    <template v-else-if="selected">
      <div class="order-detail-head"><strong>{{ selected.orderNo }}</strong>
        <el-tag :type="statusType(selected.status)">{{ selected.statusDesc }}</el-tag>
      </div>
      <dl class="order-summary">
        <div v-if="selected.remark"><dt>买家备注</dt><dd>{{ selected.remark }}</dd></div>
        <div><dt>订单金额</dt><dd>{{ money.format(selected.totalAmount) }}</dd></div>
      </dl>
      <h3>商品明细</h3>
      <el-table :data="selected.items" empty-text="暂无商品明细">
        <el-table-column prop="productName" label="商品" min-width="150" />
        <el-table-column prop="skuName" label="规格" min-width="120" />
        <el-table-column prop="quantity" label="数量" width="75" align="right" />
        <el-table-column label="小计" min-width="100" align="right">
          <template #default="{ row }">{{ money.format(row.amount) }}</template>
        </el-table-column>
      </el-table>
    </template>
    <template #footer>
      <el-button @click="detailOpen = false">关闭</el-button>
    </template>
  </el-dialog>

</template>

<style scoped>
.order-workspace { min-width: 0; }
.toolbar .el-input, .toolbar .el-select { width: min(100%, 225px); }
.order-detail-head { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.order-summary { display: grid; gap: 10px; margin: 18px 0; }
.order-summary div { display: grid; grid-template-columns: 68px minmax(0, 1fr); gap: 10px; }
.order-summary dt { color: var(--el-text-color-secondary); }
.order-summary dd { margin: 0; overflow-wrap: anywhere; }
.el-pagination { margin-top: 12px; }
</style>
