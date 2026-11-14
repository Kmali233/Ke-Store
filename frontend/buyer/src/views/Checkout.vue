<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, errorText, request } from '../api'
import { availabilityLabel, cartTotal, loadCart } from '../cart'
import { clearPending, readPending, reserveCheckout } from '../checkout'
import type { PendingCheckout } from '../checkout'
import LoadState from '../components/LoadState.vue'
import type { OrderCreated } from '../contracts'
import { money, snapshotSpecs } from '../display'
import { useSession } from '../session'
import { useLoad } from '../useLoad'

const router = useRouter()
const session = useSession()
const userId = session.identity!.userInfo.id
const remark = ref('')
const attempt = ref<PendingCheckout | null>(null)
const busy = ref(false)
const canRetry = ref(false)
const actionError = ref('')
const storageError = ref('')
let active = true
onScopeDispose(() => { active = false })

const { data, error, loading, refresh } = useLoad(async signal => ({
  selected: (await loadCart(signal)).filter(item => item.selected),
}))
const eligible = computed(() => {
  const items = data.value?.selected ?? []
  return items.length > 0 && items.length <= 20 && items.every(item => item.availability === 'AVAILABLE')
})

try {
  attempt.value = readPending(userId)
} catch {
  storageError.value = '无法读取本地结算记录，请检查浏览器的站点存储设置后刷新。'
}
if (attempt.value) void checkResult()
else void refresh()

async function showOrder(pending: PendingCheckout, order: OrderCreated) {
  clearPending(userId, pending.key)
  if (active && session.identity?.userInfo.id === userId) {
    await router.replace('/orders/' + order.orderNo)
  }
}

async function lookup(pending: PendingCheckout, context = '') {
  canRetry.value = false
  try {
    const order = await request<OrderCreated>('/order/idempotency/' + pending.key, { auth: true })
    await showOrder(pending, order)
  } catch (failure) {
    if (failure instanceof ApiError && failure.code === 404 && failure.businessResponse) {
      canRetry.value = true
      actionError.value = context + '暂未查到成功订单。可以继续查询，或使用原结算信息和原凭证重试。'
    } else {
      actionError.value = context + '暂时无法确认订单结果。' + errorText(failure)
    }
  }
}

async function checkResult() {
  if (busy.value || !attempt.value) return
  busy.value = true
  actionError.value = ''
  try {
    await lookup(attempt.value)
  } finally {
    busy.value = false
  }
}

async function send(pending: PendingCheckout) {
  canRetry.value = false
  try {
    const order = await request<OrderCreated>('/cart/checkout', {
      method: 'POST', auth: true, key: pending.key, body: pending.body,
    })
    await showOrder(pending, order)
  } catch (failure) {
    const rejected = failure instanceof ApiError && failure.businessResponse
      && [400, 404, 409].includes(failure.code)
    if (rejected) {
      clearPending(userId, pending.key)
      attempt.value = null
      actionError.value = errorText(failure) + '。请核对结算信息或订单列表后再提交。'
      if (active) await refresh()
    } else if (session.identity?.userInfo.id === userId && active) {
      await lookup(pending, errorText(failure) + ' ')
    }
  }
}

async function submit() {
  if (busy.value || attempt.value || storageError.value || !eligible.value || !data.value) return
  busy.value = true
  actionError.value = ''
  try {
    const { attempt: pending, created } = await reserveCheckout(userId, {
      remark: remark.value.trim(),
      items: data.value.selected.map(item => ({ skuId: item.skuId, quantity: item.quantity })),
    })
    attempt.value = pending
    if (!active || session.identity?.userInfo.id !== userId) return
    if (created) await send(pending)
    else await lookup(pending)
  } catch {
    storageError.value = '无法保存本地结算记录，请检查浏览器的站点存储设置后刷新。'
  } finally {
    busy.value = false
  }
}

async function retry() {
  if (busy.value || !canRetry.value || !attempt.value) return
  busy.value = true
  actionError.value = ''
  try {
    await send(attempt.value)
  } catch {
    storageError.value = '无法更新本地结算记录，请检查浏览器的站点存储设置后刷新。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <RouterLink class="back" to="/cart">‹ 返回购物车</RouterLink>
  <header class="page-title"><p class="eyebrow">最后一步</p><h1>确认订单</h1></header>
  <p v-if="storageError" class="error" role="alert">{{ storageError }}</p>
  <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
  <section v-if="attempt" class="panel padded stack" aria-live="polite">
    <h2>确认上一次结算结果</h2>
    <p class="muted">已保存原结算信息。查询结果后，可以继续查看订单或按原信息重试。</p>
    <div class="actions">
      <button :disabled="busy || !!storageError" @click="checkResult">{{ busy ? '正在确认…' : '查询下单结果' }}</button>
      <button v-if="canRetry" class="outline" :disabled="busy || !!storageError" @click="retry">使用原请求重试</button>
      <RouterLink to="/orders">查看我的订单</RouterLink>
    </div>
  </section>
  <LoadState v-else :loading="loading && !storageError" :error="error" @retry="refresh">
    <form v-if="data?.selected.length" class="checkout-layout" @submit.prevent="submit">
      <div class="stack">
        <section class="panel padded stack">
          <h2>商品清单</h2>
          <div v-for="item in data.selected" :key="item.skuId" class="split order-line">
            <div><strong>{{ item.productName || '失效商品' }}</strong><p class="muted">{{ item.skuName }} {{ snapshotSpecs(item.skuSpec) }} × {{ item.quantity }}</p>
              <p v-if="item.availability !== 'AVAILABLE'" class="error">{{ availabilityLabel[item.availability] }}</p>
            </div>
            <span>{{ money(item.price) }} / 件</span>
          </div>
          <label class="field">订单备注（可选）<textarea v-model="remark" maxlength="255" rows="2"></textarea></label>
        </section>
      </div>
      <aside class="panel padded stack checkout-summary">
        <h2>结算金额</h2>
        <strong class="price large">{{ money(cartTotal(data.selected)) }}</strong>
        <p class="muted">此处为参考合计，实际金额以订单为准。下单后可在订单详情查看付款方式。</p>
        <p v-if="!eligible" class="notice">请返回购物车调整库存不足、失效商品或超过 20 种的勾选项。</p>
        <button type="submit" :disabled="busy || !eligible || !!storageError">提交订单</button>
      </aside>
    </form>
    <div v-else-if="!storageError" class="state">
      <p>没有勾选要结算的商品。</p><RouterLink class="button" to="/cart">返回购物车</RouterLink>
    </div>
  </LoadState>
</template>
