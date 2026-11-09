<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError, errorText, request } from '../api'
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
  receiverName: string
  receiverPhone: string
  receiverAddress: string
  remark: string | null
  logisticsCompany: string | null
  trackingNo: string | null
  shipTime: string | null
  items: OrderItem[]
}
interface Shipment { logisticsCompany: string; trackingNo: string }
interface Page<T> { records: T[]; total: number }

const statuses = ['待付款', '已付款', '备货中', '已发货', '已签收', '已取消']
const orderNo = ref('')
const status = ref<number>()
const filters = ref<{ orderNo: string; status?: number }>({ orderNo: '' })
const page = ref(1)
const pageSize = 10
const selectedOrderNo = ref('')
const detailOpen = ref(false)
const shipOpen = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const noticeType = ref<'success' | 'warning'>('success')
const filterError = ref('')
const shipment = ref<Shipment>({ logisticsCompany: '', trackingNo: '' })
const pending = ref<{ orderNo: string; shipment: Shipment }>()

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
function isShippable(status: number) { return status === 1 || status === 2 }
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

function openShipment(number: string) {
  if (pending.value && pending.value.orderNo !== number) {
    noticeType.value = 'warning'
    notice.value = '请先核对待确认的发货操作。'
    return
  }
  selectedOrderNo.value = number
  if (!pending.value) {
    shipment.value = { logisticsCompany: '', trackingNo: '' }
    error.value = ''
  }
  notice.value = ''
  detailOpen.value = false
  shipOpen.value = true
}

function confirmShipment(message: string) {
  pending.value = undefined
  shipOpen.value = false
  noticeType.value = 'success'
  notice.value = message
  void list.refresh()
  if (detailOpen.value) void detail.refresh()
}

async function reconcileShipment() {
  const operation = pending.value
  if (!operation || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const latest = await request<OrderDetail>(detailPath(operation.orderNo))
    const { logisticsCompany, trackingNo } = operation.shipment
    if ([3, 4].includes(latest.status) &&
        latest.logisticsCompany === logisticsCompany && latest.trackingNo === trackingNo) {
      confirmShipment('已核对订单 ' + operation.orderNo + ' 的发货信息。')
    } else if (isShippable(latest.status)) {
      pending.value = undefined
      error.value = '订单仍为' + latest.statusDesc + '。请再次确认物流信息后提交。'
      void list.refresh()
    } else {
      pending.value = undefined
      shipOpen.value = false
      noticeType.value = 'warning'
      notice.value = '订单当前为' + latest.statusDesc + '，请查看订单详情核对物流信息。'
      void list.refresh()
    }
  } catch (failure) {
    error.value = errorText(failure) + '。核对完成后才能再次提交。'
  } finally {
    busy.value = false
  }
}

async function submitShipment() {
  if (busy.value || pending.value || !selectedOrderNo.value) return
  error.value = ''
  const number = selectedOrderNo.value
  const logisticsCompany = shipment.value.logisticsCompany.trim()
  const trackingNo = shipment.value.trackingNo.trim()
  if (!logisticsCompany || !trackingNo || logisticsCompany.length > 50 || trackingNo.length > 64) {
    error.value = '请填写有效的物流公司和运单号。'
    return
  }
  const payload = { logisticsCompany, trackingNo }
  busy.value = true
  try {
    await request('/order/' + encodeURIComponent(number) + '/ship', { method: 'POST', body: payload })
    confirmShipment('订单 ' + number + ' 已发货。')
  } catch (failure) {
    const description = errorText(failure)
    if (failure instanceof ApiError && failure.businessResponse && [400, 401, 403, 404, 422].includes(failure.code)) {
      error.value = description
    } else {
      pending.value = { orderNo: number, shipment: payload }
      error.value = description + '。请先核对订单的发货状态。'
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <header class="page-head">
    <div><h1>订单履约</h1><p>筛选订单，查看收货与物流信息，处理待发货订单。</p></div>
    <el-button v-if="pending" type="warning" plain @click="openShipment(pending.orderNo)">
      继续核对发货
    </el-button>
  </header>
  <el-alert v-if="notice" :title="notice" :type="noticeType" :closable="false" show-icon class="order-notice" />

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
          <el-button v-if="isShippable(row.status)" link type="primary" :disabled="busy" @click="openShipment(row.orderNo)">发货</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="(list.data.value?.total ?? 0) > pageSize" v-model:current-page="page"
      layout="prev, pager, next" :page-size="pageSize" :total="list.data.value?.total ?? 0" />
  </section>
  <p class="order-roadmap">后续阶段将接入退款审核和支付对账。</p>

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
        <div><dt>收件人</dt><dd>{{ selected.receiverName }} · {{ selected.receiverPhone }}</dd></div>
        <div><dt>收货地址</dt><dd>{{ selected.receiverAddress }}</dd></div>
        <div v-if="selected.remark"><dt>买家备注</dt><dd>{{ selected.remark }}</dd></div>
        <div><dt>订单金额</dt><dd>{{ money.format(selected.totalAmount) }}</dd></div>
        <div v-if="selected.trackingNo"><dt>物流信息</dt><dd>{{ selected.logisticsCompany }} · {{ selected.trackingNo }}</dd></div>
        <div v-if="selected.shipTime"><dt>发货时间</dt><dd>{{ dateTime(selected.shipTime) }}</dd></div>
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
      <el-button v-if="selected && !detail.loading.value && !detail.error.value && isShippable(selected.status)"
        type="primary" @click="openShipment(selected.orderNo)">发货</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="shipOpen" title="确认发货" width="min(525px, calc(100vw - 28px))"
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
    <p class="order-ship-target">订单 <strong>{{ selectedOrderNo }}</strong></p>
    <el-form label-position="top" :disabled="busy || !!pending" @submit.prevent="submitShipment">
      <el-form-item label="物流公司" required>
        <el-input v-model="shipment.logisticsCompany" aria-label="物流公司" maxlength="50" placeholder="输入物流公司" />
      </el-form-item>
      <el-form-item label="运单号" required>
        <el-input v-model="shipment.trackingNo" aria-label="运单号" maxlength="64" placeholder="输入运单号" />
      </el-form-item>
    </el-form>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <template #footer>
      <el-button :disabled="busy" @click="shipOpen = false">关闭</el-button>
      <el-button v-if="pending" type="primary" :loading="busy" @click="reconcileShipment">核对发货状态</el-button>
      <el-button v-else type="primary" :loading="busy" @click="submitShipment">提交发货</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.order-notice { margin-block-end: 16px; }
.order-workspace { min-width: 0; }
.toolbar .el-input, .toolbar .el-select { width: min(100%, 225px); }
.order-roadmap { color: var(--el-text-color-secondary); font-size: .875rem; margin-top: 14px; }
.order-detail-head { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.order-summary { display: grid; gap: 10px; margin: 18px 0; }
.order-summary div { display: grid; grid-template-columns: 68px minmax(0, 1fr); gap: 10px; }
.order-summary dt { color: var(--el-text-color-secondary); }
.order-summary dd { margin: 0; overflow-wrap: anywhere; }
.order-ship-target { margin-block: 0 16px; }
.el-pagination { margin-top: 12px; }
</style>
