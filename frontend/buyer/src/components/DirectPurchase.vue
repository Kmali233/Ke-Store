<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, errorText, request } from '../api'
import { clearPending, readPending, reserveCheckout } from '../checkout'
import type { PendingCheckout } from '../checkout'
import type { OrderCreated, PaymentStatus, ProductDetail, Sku } from '../contracts'
import { money, specsLabel } from '../display'
import { openCheckout } from '../payment'
import { useSession } from '../session'

const props = defineProps<{ product: ProductDetail; sku: Sku; initialQuantity: number; userId: number }>()
const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const session = useSession()
const dialog = ref<HTMLDialogElement>()
const quantity = ref(props.initialQuantity)
const pending = ref<PendingCheckout | null>(null)
const busy = ref(false)
const canRetry = ref(false)
const message = ref('')
const storageError = ref(false)
const actions = new AbortController()
const options = { auth: true, signal: actions.signal }
const active = () => !actions.signal.aborted && session.identity?.userInfo.id === props.userId
const limit = computed(() => Math.min(props.sku.stock, 999))
const valid = computed(() => Number.isInteger(quantity.value) && quantity.value >= 1 && quantity.value <= limit.value)
const estimated = computed(() => Math.round(props.sku.price * 100) * quantity.value / 100)
const pendingItem = computed(() => pending.value?.body.items[0])
const pendingSku = computed(() => props.product.skus.find(item => item.id === pendingItem.value?.skuId))
const pendingForProduct = computed(() => !!pendingSku.value)

onScopeDispose(() => actions.abort())
onMounted(() => {
  dialog.value?.showModal()
  try {
    pending.value = readPending(props.userId, 'direct')
  } catch {
    storageError.value = true
    message.value = '无法读取本地购买记录，请检查浏览器的站点存储。'
  }
  if (pending.value) void recover()
})

async function finish(attempt: PendingCheckout, order: OrderCreated, estimate?: number) {
  if (!active()) return
  try {
    clearPending(props.userId, attempt.key, 'direct')
  } catch {
    // The stored key can still recover this order if clearing storage fails.
  }

  const orderPage = '/orders/' + order.orderNo
  const paymentPath = '/order/' + order.orderNo + '/payment'
  if (estimate === undefined || order.totalAmount !== estimate) {
    await router.push({ path: orderPage, query: estimate === undefined ? {} : { buyNotice: 'price' } })
    return
  }
  try {
    const payment = await request<PaymentStatus>(paymentPath, options)
    if (!active()) return
    if (payment.canStart && (payment.channel === 'DEMO' || payment.channel === 'ALIPAY_SANDBOX')) {
      await openCheckout(router, paymentPath, payment.channel === 'DEMO', actions.signal)
      return
    }
  } catch {
    // The order already exists. Let the buyer continue on its detail page.
  }
  if (active()) await router.push({ path: orderPage, query: { buyNotice: 'payment' } })
}

async function lookup(attempt: PendingCheckout) {
  canRetry.value = false
  try {
    const order = await request<OrderCreated>('/order/idempotency/' + attempt.key, options)
    await finish(attempt, order)
  } catch (failure) {
    if (!active()) return
    canRetry.value = failure instanceof ApiError && failure.code === 404 && failure.businessResponse
    message.value = canRetry.value
      ? '暂未查到订单。可以继续查询，或按原请求重试。'
      : '暂时无法确认订单。' + errorText(failure)
  }
}

async function send(attempt: PendingCheckout, estimate?: number) {
  try {
    const order = await request<OrderCreated>('/order', {
      ...options, method: 'POST', key: attempt.key, body: attempt.body,
    })
    await finish(attempt, order, estimate)
  } catch (failure) {
    if (!active()) return
    if (failure instanceof ApiError && failure.businessResponse && [400, 404, 409].includes(failure.code)) {
      clearPending(props.userId, attempt.key, 'direct')
      pending.value = null
      canRetry.value = false
      message.value = errorText(failure)
      return
    }
    await lookup(attempt)
  }
}

async function run(action: () => Promise<void>) {
  if (busy.value || !active()) return
  busy.value = true
  message.value = ''
  try {
    await action()
  } catch {
    if (active()) message.value = '无法完成本次操作，请检查浏览器的站点存储后重试。'
  } finally {
    busy.value = false
  }
}

function recover() {
  const attempt = pending.value
  if (attempt) return run(() => lookup(attempt))
}

function retry() {
  const attempt = pending.value
  if (attempt && canRetry.value) return run(() => send(attempt))
}

function submit() {
  if (!valid.value || pending.value || storageError.value) return
  return run(async () => {
    const estimate = estimated.value
    const { attempt, created } = await reserveCheckout(props.userId, {
      remark: '',
      items: [{ skuId: props.sku.id, quantity: quantity.value }],
    }, 'direct')
    if (!active()) return
    pending.value = attempt
    if (created) await send(attempt, estimate)
    else await lookup(attempt)
  })
}

function changeQuantity(delta: number) {
  const current = Number(quantity.value)
  quantity.value = Math.max(1, Math.min(limit.value, current + delta))
}

function close() {
  if (!busy.value) dialog.value?.close()
}
</script>

<template>
  <dialog ref="dialog" class="purchase-dialog" aria-labelledby="purchase-title"
    @cancel.prevent="close" @close="emit('close')">
    <form class="purchase-frame" @submit.prevent="submit">
      <header class="purchase-head">
        <h2 id="purchase-title">{{ pending ? '确认上一次购买' : '确认订单' }}</h2>
        <button class="quiet close-button" type="button" aria-label="关闭订单确认" :disabled="busy" @click="close">×</button>
      </header>
      <div class="purchase-content">
        <template v-if="pending">
          <p v-if="pendingForProduct"><strong>{{ product.name }}</strong> · {{ pendingSku?.name }} × {{ pendingItem?.quantity }}</p>
          <p v-else>你有一笔其他商品的待确认购买（规格编号 {{ pendingItem?.skuId }} × {{ pendingItem?.quantity }}）。</p>
          <p class="muted">查询结果后，可以在订单详情继续付款。</p>
        </template>
        <template v-else>
          <div class="product-summary">
            <strong>{{ product.name }}</strong>
            <p class="muted">{{ sku.name }}<template v-if="specsLabel(sku.specs)"> · {{ specsLabel(sku.specs) }}</template></p>
          </div>
          <p v-if="product.description" class="product-description preserve">{{ product.description }}</p>
        </template>
        <p v-if="message" class="error" role="alert">{{ message }}</p>
        <p v-if="busy" class="muted" role="status">正在确认订单，请稍候…</p>
      </div>
      <footer class="purchase-footer">
        <div v-if="!pending" class="purchase-total">
          <div><p class="muted">预计合计</p><strong class="price large">{{ valid ? money(estimated) : '—' }}</strong></div>
          <div class="purchase-quantity">
            <label for="purchase-quantity">数量 · 库存 {{ sku.stock }}</label>
            <div class="stepper">
              <button class="outline" type="button" aria-label="减少数量" :disabled="busy || quantity <= 1" @click="changeQuantity(-1)">−</button>
              <input id="purchase-quantity" v-model.number="quantity" type="number" min="1" :max="limit" required :disabled="busy">
              <button class="outline" type="button" aria-label="增加数量" :disabled="busy || quantity >= limit" @click="changeQuantity(1)">+</button>
            </div>
          </div>
        </div>
        <div class="purchase-actions">
          <button class="quiet" type="button" :disabled="busy" @click="close">{{ pending ? '关闭' : '取消' }}</button>
          <template v-if="pending">
            <button class="outline" type="button" :disabled="busy" @click="recover">查询订单</button>
            <button v-if="canRetry" type="button" :disabled="busy" @click="retry">按原请求重试</button>
          </template>
          <button v-else type="submit" :disabled="busy || !valid || storageError">去付款</button>
        </div>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.purchase-dialog {
  width: min(36rem, calc(100% - 1.5rem));
  max-width: none;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: 0 20px 70px #15182133;
  color: inherit;
}
.purchase-dialog::backdrop { background: #1c233075; }
.purchase-frame { display: flex; flex-direction: column; max-height: calc(100dvh - 2rem); }
.purchase-head, .purchase-content, .purchase-footer { padding: .95rem clamp(.9rem, 4vw, 1.65rem); }
.purchase-head { display: grid; grid-template-columns: 44px 1fr 44px; align-items: center; border-bottom: 1px solid var(--line); }
.purchase-head h2 { grid-column: 2; text-align: center; }
.close-button { padding: 0; font-size: 1.4rem; }
.purchase-content { overflow: auto; display: grid; gap: .8rem; min-height: 0; }
.product-summary strong { font-size: 1.05rem; }
.product-description { padding-top: .8rem; border-top: 1px solid var(--line); }
.purchase-footer { border-top: 1px solid var(--line); display: grid; gap: .9rem; }
.purchase-total, .purchase-actions { display: flex; align-items: end; gap: .5rem; flex-wrap: wrap; }
.purchase-total { justify-content: space-between; }
.purchase-actions { justify-content: flex-end; }
.purchase-quantity label { color: var(--muted); font-size: .85rem; }
.stepper { display: flex; gap: .3rem; }
.stepper button { width: 44px; padding: 0; }
.stepper input { width: 3.5rem; text-align: center; padding-inline: .2rem; appearance: textfield; }
.stepper input::-webkit-inner-spin-button, .stepper input::-webkit-outer-spin-button { appearance: none; margin: 0; }
</style>
