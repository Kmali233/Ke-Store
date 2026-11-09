<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError, errorText, request } from '../api'
import { useSession } from '../session'
import { useLoad } from '../useLoad'

interface Product { id: number; name: string }
interface Sku { id: number; name: string; status: number }
interface ProductDetail extends Product { skus: Sku[] }
interface Page<T> { records: T[]; total: number }
interface Balance { stock: number; sales: number; ledgerStock: number; consistent: boolean }
interface LedgerRow {
  id: number
  type: string
  businessNo: string
  quantity: number
  beforeStock: number
  afterStock: number
  createdAt: string
  reason: string | null
}
interface Adjustment {
  businessNo: string
  type: 'INBOUND' | 'ADJUSTMENT'
  quantity: number
  reason: string
}
interface PendingAdjustment { product: Product; sku: Sku; adjustment: Adjustment }

const eventNames: Record<string, string> = {
  OPENING: '期初库存',
  INBOUND: '入库',
  ADJUSTMENT: '手工调整',
  ORDER_OCCUPY: '订单扣减',
  CANCEL_RESTORE: '取消归还',
  REFUND_RESTORE: '退款归还',
}

const name = ref('')
const appliedName = ref('')
const productPage = ref(1)
const product = ref<Product>()
const sku = ref<Sku>()
const ledgerPage = ref(1)
const ledgerType = ref('')
const ledgerBusinessNo = ref('')
const filters = ref({ type: '', businessNo: '' })
const dialog = ref(false)
const busy = ref(false)
const formError = ref('')
const notice = ref('')
const draft = ref({ type: 'INBOUND' as Adjustment['type'], quantity: 1 as number | undefined, reason: '' })
const pending = ref<Adjustment>()
const pageSize = 10
const { access } = useSession()
const pendingKey = access.value ? 'xiaoke:admin:inventory:' + access.value.id : ''

function endpoint() {
  return '/product/' + product.value!.id + '/skus/' + sku.value!.id + '/inventory'
}

function listQuery(pageNum: number, query: Record<string, string> = {}) {
  const params = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(pageSize) })
  for (const [name, value] of Object.entries(query)) {
    if (value) params.set(name, value)
  }
  return params.toString()
}

const products = useLoad(signal => request<Page<Product>>(
  '/admin/catalog/products/page?' + listQuery(productPage.value, { name: appliedName.value }), { signal },
))
const detail = useLoad(signal => request<ProductDetail>('/admin/catalog/products/' + product.value!.id, { signal }))
const balance = useLoad(signal => request<Balance>(endpoint() + '/balance', { signal }))
const ledger = useLoad(signal => request<Page<LedgerRow>>(
  endpoint() + '/ledger?' + listQuery(ledgerPage.value, filters.value), { signal },
))

const availableSkus = computed(() => detail.data.value?.id === product.value?.id ? detail.data.value?.skus ?? [] : [])
const selectedBalance = computed(() => balance.loading.value || balance.error.value ? undefined : balance.data.value)

onMounted(() => {
  void products.refresh()
  if (!pendingKey) return
  try {
    const saved = sessionStorage.getItem(pendingKey)
    if (!saved) return
    const operation = JSON.parse(saved) as PendingAdjustment
    if (!operation.product?.id || !operation.sku?.id || !operation.adjustment?.businessNo) {
      notice.value = '暂存的库存操作信息不完整。请先查询库存流水。'
      return
    }
    product.value = operation.product
    sku.value = operation.sku
    pending.value = operation.adjustment
    draft.value = { type: operation.adjustment.type, quantity: operation.adjustment.quantity, reason: operation.adjustment.reason }
    formError.value = '有一笔待核对的库存调整。请查询流水或使用原业务号重试。'
    void detail.refresh()
    void balance.refresh()
    void ledger.refresh()
  } catch {
    notice.value = '无法读取暂存的库存操作。请先通过流水核对已有调整。'
  }
})
watch(productPage, products.refresh)
watch(ledgerPage, () => { if (sku.value) void ledger.refresh() })

function searchProducts() {
  appliedName.value = name.value.trim()
  if (productPage.value === 1) void products.refresh()
  else productPage.value = 1
}

function chooseProduct(row: Product) {
  if (pending.value || busy.value) return
  product.value = row
  sku.value = undefined
  notice.value = ''
  void detail.refresh()
}

function chooseSku(id: number) {
  if (pending.value || busy.value) return
  sku.value = availableSkus.value.find(row => row.id === id)
  if (!sku.value) return
  notice.value = ''
  ledgerType.value = ''
  ledgerBusinessNo.value = ''
  filters.value = { type: '', businessNo: '' }
  if (ledgerPage.value === 1) void ledger.refresh()
  else ledgerPage.value = 1
  void balance.refresh()
}

function searchLedger() {
  const businessNo = ledgerBusinessNo.value.trim()
  if (businessNo && !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/.test(businessNo)) {
    notice.value = '请输入有效的库存业务号。'
    return
  }
  notice.value = ''
  filters.value = { type: ledgerType.value, businessNo }
  if (ledgerPage.value === 1) void ledger.refresh()
  else ledgerPage.value = 1
}

function openAdjustment() {
  if (!sku.value || busy.value) return
  if (!pending.value) {
    draft.value = { type: 'INBOUND', quantity: 1, reason: '' }
    formError.value = ''
  }
  notice.value = ''
  dialog.value = true
}

function prepareAdjustment(): Adjustment | undefined {
  const { type, quantity } = draft.value
  const reason = draft.value.reason.trim()
  if (quantity === undefined || !Number.isInteger(quantity) || quantity === 0 ||
      quantity < -2147483648 || quantity > 2147483647 || (type === 'INBOUND' && quantity < 0)) {
    formError.value = type === 'INBOUND' ? '入库数量须为正整数。' : '调整数量须为非零整数。'
    return
  }
  if (!reason || /[\u0000-\u001f\u007f-\u009f]/.test(reason)) {
    formError.value = '请填写一行有效的调整原因。'
    return
  }
  return { businessNo: 'ADM-' + crypto.randomUUID(), type, quantity, reason }
}

function finishAdjustment(payload: Adjustment) {
  clearPending()
  dialog.value = false
  notice.value = '库存已更新，业务号 ' + payload.businessNo
  void balance.refresh()
  void ledger.refresh()
}

function clearPending() {
  pending.value = undefined
  try {
    if (pendingKey) sessionStorage.removeItem(pendingKey)
  } catch {
    // A retained record can be checked again by its original business number.
  }
}

async function submitAdjustment() {
  if (busy.value || !product.value || !sku.value) return
  const isRetry = Boolean(pending.value)
  formError.value = ''
  const payload = pending.value ?? prepareAdjustment()
  if (!payload) return
  if (pendingKey && !isRetry) {
    try {
      const { id, name } = product.value
      const selectedSku = { id: sku.value.id, name: sku.value.name, status: sku.value.status }
      sessionStorage.setItem(pendingKey, JSON.stringify({ product: { id, name }, sku: selectedSku, adjustment: payload }))
    } catch {
      formError.value = '无法暂存库存操作，请允许浏览器使用会话存储后重试。'
      return
    }
  }
  pending.value = payload
  busy.value = true
  try {
    await request(endpoint() + '/adjustments', { method: 'POST', body: payload })
    finishAdjustment(payload)
  } catch (failure) {
    if (!isRetry && failure instanceof ApiError && failure.businessResponse &&
        [400, 401, 403, 404, 409, 422].includes(failure.code)) {
      clearPending()
      formError.value = errorText(failure)
    } else {
      formError.value = errorText(failure) + '。这笔调整的结果待核对，可查询流水或用原业务号重试。'
    }
  } finally {
    busy.value = false
  }
}

async function checkAdjustment() {
  const payload = pending.value
  if (!payload || busy.value || !sku.value) return
  busy.value = true
  formError.value = ''
  try {
    const result = await request<Page<LedgerRow>>(
      endpoint() + '/ledger?' + listQuery(1, { businessNo: payload.businessNo }),
    )
    const matched = result.records.some(row =>
      row.businessNo === payload.businessNo && row.type === payload.type &&
      row.quantity === payload.quantity && row.reason === payload.reason,
    )
    if (matched) finishAdjustment(payload)
    else formError.value = '暂未查到匹配的流水。请保留该业务号以继续核对。'
  } catch (failure) {
    formError.value = errorText(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <header class="page-head">
    <div><h1>库存调整</h1><p>选择商品与规格，核对当前库存并查阅每一次变动。</p></div>
    <el-button v-if="pending" type="warning" plain :disabled="busy" @click="openAdjustment">
      继续核对调整
    </el-button>
  </header>
  <el-alert v-if="notice" :title="notice" :type="notice.startsWith('库存已更新') ? 'success' : 'warning'"
    :closable="false" show-icon class="inventory-notice" />

  <section class="panel inventory-selection" aria-label="选择库存商品">
    <h2>选择商品</h2>
    <form class="toolbar" @submit.prevent="searchProducts">
      <el-input v-model="name" aria-label="库存商品名称" placeholder="按商品名称筛选" maxlength="128" clearable />
      <el-button native-type="submit" :loading="products.loading.value">查询商品</el-button>
    </form>
    <p v-if="products.error.value" class="error-text" role="alert">{{ products.error.value }}</p>
    <el-table v-else v-loading="products.loading.value" :data="products.data.value?.records ?? []"
      row-key="id" max-height="320" empty-text="没有符合条件的商品">
      <el-table-column prop="name" label="商品" min-width="175" />
      <el-table-column prop="id" label="商品编号" width="110" />
      <el-table-column label="操作" width="125">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!!pending || busy" :aria-pressed="product?.id === row.id"
            @click="chooseProduct(row as Product)">{{ product?.id === row.id ? '已选商品' : '选择商品' }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="(products.data.value?.total ?? 0) > pageSize" v-model:current-page="productPage"
      layout="prev, pager, next" :page-size="pageSize" :total="products.data.value?.total ?? 0" />
    <div v-if="product" class="inventory-sku">
      <div><strong>{{ product.name }}</strong><span class="inventory-meta">商品 #{{ product.id }}</span></div>
      <el-select :model-value="sku?.id" placeholder="选择库存规格" aria-label="选择库存规格"
        :loading="detail.loading.value" :disabled="!!pending || busy || detail.loading.value" @change="chooseSku">
        <el-option v-for="option in availableSkus" :key="option.id" :value="option.id"
          :label="option.name + ' · #' + option.id + (option.status === 0 ? ' · 已停用' : '')" />
      </el-select>
      <p v-if="detail.error.value" class="error-text" role="alert">{{ detail.error.value }}</p>
    </div>
  </section>

  <section v-if="sku" class="panel inventory-ledger" aria-label="库存余额与流水">
    <div class="inventory-section-heading">
      <div><h2>库存流水</h2><p>{{ sku.name }} · SKU #{{ sku.id }}</p></div>
      <div class="row-actions">
        <el-button :loading="balance.loading.value || ledger.loading.value" @click="() => { void balance.refresh(); void ledger.refresh() }">刷新库存</el-button>
        <el-button v-if="!pending" type="primary" :disabled="busy" @click="openAdjustment">调整库存</el-button>
      </div>
    </div>
    <p v-if="balance.error.value" class="error-text" role="alert">{{ balance.error.value }}</p>
    <dl v-if="selectedBalance" class="inventory-metrics">
      <div><dt>当前库存</dt><dd>{{ selectedBalance.stock }}</dd></div>
      <div><dt>流水余额</dt><dd>{{ selectedBalance.ledgerStock }}</dd></div>
      <div><dt>已售数量</dt><dd>{{ selectedBalance.sales }}</dd></div>
      <div><dt>账实核对</dt><dd><el-tag :type="selectedBalance.consistent ? 'success' : 'warning'">
        {{ selectedBalance.consistent ? '一致' : '待核对' }}
      </el-tag></dd></div>
    </dl>
    <form class="toolbar" @submit.prevent="searchLedger">
      <el-select v-model="ledgerType" clearable aria-label="流水类型" placeholder="全部流水类型">
        <el-option v-for="(label, type) in eventNames" :key="type" :value="type" :label="label" />
      </el-select>
      <el-input v-model="ledgerBusinessNo" aria-label="库存业务号" placeholder="按业务号查询" maxlength="64" clearable />
      <el-button native-type="submit" :loading="ledger.loading.value">查询流水</el-button>
    </form>
    <p v-if="ledger.error.value" class="error-text" role="alert">{{ ledger.error.value }}</p>
    <el-table v-else v-loading="ledger.loading.value" :data="ledger.data.value?.records ?? []" row-key="id" empty-text="没有符合条件的库存流水">
      <el-table-column label="类型" width="110"><template #default="{ row }">{{ eventNames[row.type] || row.type }}</template></el-table-column>
      <el-table-column prop="businessNo" label="业务号" min-width="185" show-overflow-tooltip />
      <el-table-column label="变动" width="90" align="right"><template #default="{ row }">{{ row.quantity > 0 ? '+' : '' }}{{ row.quantity }}</template></el-table-column>
      <el-table-column prop="beforeStock" label="调整前" width="95" align="right" />
      <el-table-column prop="afterStock" label="调整后" width="95" align="right" />
      <el-table-column prop="reason" label="原因" min-width="165" show-overflow-tooltip />
      <el-table-column label="时间" min-width="160"><template #default="{ row }">{{ row.createdAt ? String(row.createdAt).replace('T', ' ').slice(0, 19) : '—' }}</template></el-table-column>
    </el-table>
    <el-pagination v-if="(ledger.data.value?.total ?? 0) > pageSize" v-model:current-page="ledgerPage"
      layout="prev, pager, next" :page-size="pageSize" :total="ledger.data.value?.total ?? 0" />
  </section>

  <el-dialog v-model="dialog" title="调整库存" width="min(540px, calc(100vw - 28px))"
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
    <p class="inventory-target">{{ product?.name }} / {{ sku?.name }} · SKU #{{ sku?.id }}
      <span v-if="selectedBalance"> · 当前库存 {{ selectedBalance.stock }}</span>
    </p>
    <el-form label-position="top" :disabled="busy || !!pending" @submit.prevent="submitAdjustment">
      <el-form-item label="调整类型" required>
        <el-select v-model="draft.type" aria-label="调整类型">
          <el-option label="入库" value="INBOUND" /><el-option label="手工调整" value="ADJUSTMENT" />
        </el-select>
      </el-form-item>
      <el-form-item label="变动数量" required>
        <el-input-number v-model="draft.quantity" aria-label="变动数量" :precision="0" :max="2147483647"
          :min="draft.type === 'INBOUND' ? 1 : -2147483648" />
        <span class="inventory-hint">正数增加库存，手工调整也支持负数。</span>
      </el-form-item>
      <el-form-item label="调整原因" required>
        <el-input v-model="draft.reason" aria-label="调整原因" maxlength="200" show-word-limit placeholder="例如：到货入库" />
      </el-form-item>
    </el-form>
    <p v-if="pending" class="inventory-key">业务号 <code>{{ pending.businessNo }}</code></p>
    <p v-if="formError" class="error-text" role="alert">{{ formError }}</p>
    <template #footer>
      <el-button :disabled="busy" @click="dialog = false">关闭</el-button>
      <el-button v-if="pending" :loading="busy" @click="checkAdjustment">核对流水</el-button>
      <el-button type="primary" :loading="busy" @click="submitAdjustment">{{ pending ? '用原业务号重试' : '确认调整' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.inventory-notice { margin-block-end: 16px; }
.inventory-selection, .inventory-ledger { margin-block-end: 16px; min-width: 0; }
.inventory-sku, .inventory-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.inventory-sku { border-top: 1px solid var(--el-border-color-light); margin-top: 16px; padding-top: 16px; }
.inventory-meta, .inventory-hint, .inventory-section-heading p { color: var(--el-text-color-secondary); }
.inventory-meta, .inventory-hint { margin-inline-start: 8px; }
.inventory-sku .el-select, .toolbar .el-input, .toolbar .el-select { width: min(100%, 240px); }
.inventory-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 12px; margin: 16px 0; }
.inventory-metrics div { padding: 14px 16px; border: 1px solid var(--el-border-color-light); border-radius: 9px; }
.inventory-metrics dt { color: var(--el-text-color-secondary); font-size: .88rem; }
.inventory-metrics dd { font-size: 1.25rem; font-weight: 650; margin: 6px 0 0; }
.inventory-target { margin-block: 0 16px; }
.inventory-key { overflow-wrap: anywhere; }
.el-pagination { margin-top: 12px; }
</style>
