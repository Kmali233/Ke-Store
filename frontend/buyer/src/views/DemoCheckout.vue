<script setup lang="ts">
import { onScopeDispose, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText, request } from '../api'
import LoadState from '../components/LoadState.vue'
import type { OrderDetail, PaymentStatus } from '../contracts'
import { money } from '../display'
import { useLoad } from '../useLoad'

const number = String(useRoute().params.number)
const router = useRouter()
const orderPage = '/orders/' + number
const orderApi = '/order/' + number
const actions = new AbortController()
const paying = ref(false)
const actionError = ref('')
onScopeDispose(() => actions.abort())

const { data, loading, error, refresh } = useLoad(async signal => {
  const [order, payment] = await Promise.all([
    request<OrderDetail>(orderApi, { auth: true, signal }),
    request<PaymentStatus>(orderApi + '/payment', { auth: true, signal }),
  ])
  return { order, payment }
})
void refresh()

async function simulate(delivery: 'notify' | 'query') {
  if (paying.value || !data.value?.payment.demoEnabled || data.value.payment.channel !== 'DEMO'
      || data.value.payment.state !== 'PENDING' || data.value.order.status !== 0) return
  paying.value = true
  actionError.value = ''
  try {
    await request(orderApi + '/payment/demo/confirm/' + delivery, {
      method: 'POST', auth: true, signal: actions.signal,
    })
    await router.replace(orderPage)
  } catch (failure) {
    if (!actions.signal.aborted) actionError.value = errorText(failure)
    await refresh()
  } finally {
    paying.value = false
  }
}
</script>

<template>
  <RouterLink class="back" :to="orderPage">‹ 返回订单详情</RouterLink>
  <header class="page-title">
    <p class="eyebrow">模拟支付</p>
    <h1>演示收银台</h1>
    <p class="muted">仅供演示，无真实扣款。</p>
  </header>
  <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
  <LoadState :loading="loading" :error="error" @retry="refresh">
    <section v-if="data" class="panel padded stack">
      <template v-if="data.payment.demoEnabled && data.payment.channel === 'DEMO'">
        <div class="split"><h2>订单 {{ data.order.orderNo }}</h2><strong class="price large">{{ money(data.order.totalAmount) }}</strong></div>
        <template v-if="data.payment.state === 'PENDING' && data.order.status === 0">
          <p class="muted">选「仅记录付款」可模拟通知丢失，返回订单详情后手动查询。</p>
          <div class="actions">
            <button :disabled="paying" @click="simulate('notify')">模拟付款并通知</button>
            <button class="secondary" :disabled="paying" @click="simulate('query')">仅记录付款</button>
          </div>
        </template>
        <p v-else-if="data.payment.state === 'SUCCEEDED'" role="status">商城已确认演示付款。</p>
        <p v-else class="muted">请从待付款订单详情发起演示支付。</p>
      </template>
      <p v-else class="muted">此订单的演示支付入口不可用。</p>
    </section>
  </LoadState>
</template>
