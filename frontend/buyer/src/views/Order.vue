<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText, request } from '../api'
import LoadState from '../components/LoadState.vue'
import ProductImage from '../components/ProductImage.vue'
import type { OrderDetail, PaymentStatus } from '../contracts'
import { dateLabel, money, snapshotSpecs } from '../display'
import { useLoad } from '../useLoad'

const route = useRoute()
const router = useRouter()
const path = '/order/' + route.params.number
const paymentPath = path + '/payment'
const returned = route.query.paymentReturn === '1'
const paying = ref(false)
const checking = ref(false)
const actionError = ref('')
const actions = new AbortController()
onScopeDispose(() => actions.abort())

const { data, loading, error, refresh } = useLoad(signal => request<OrderDetail>(path, { auth: true, signal }))
const paymentStatus = useLoad(signal => request<PaymentStatus>(paymentPath, { auth: true, signal }))
const { data: payment, error: paymentError } = paymentStatus
const demo = computed(() => payment.value?.channel === 'DEMO')
const paymentEnabled = computed(() => demo.value ? payment.value?.demoEnabled : payment.value?.sandboxEnabled)
const paymentLabel = computed(() => demo.value ? '演示支付' : '支付宝沙箱支付')
void refresh()
if (returned) {
  // The redirect is a hint to query. All other provider URL parameters are discarded.
  void router.replace({ path: route.path })
  void checkPayment()
} else {
  void paymentStatus.refresh()
}

async function pay() {
  if (paying.value || checking.value || !payment.value?.canStart || data.value?.status !== 0) return
  paying.value = true
  actionError.value = ''
  try {
    const checkout = await request<{ paymentUrl: string }>(paymentPath + (demo.value ? '/demo' : '/alipay'), {
      method: 'POST', auth: true, signal: actions.signal,
    })
    if (demo.value) {
      await router.push(checkout.paymentUrl)
    } else {
      window.location.assign(checkout.paymentUrl)
    }
  } catch (failure) {
    if (!actions.signal.aborted) actionError.value = errorText(failure)
    await Promise.all([refresh(), paymentStatus.refresh()])
  } finally {
    paying.value = false
  }
}

async function checkPayment() {
  if (checking.value || paying.value) return
  checking.value = true
  actionError.value = ''
  try {
    await request<PaymentStatus>(paymentPath + '/query', {
      method: 'POST', auth: true, signal: actions.signal,
    })
  } catch (failure) {
    if (!actions.signal.aborted) actionError.value = errorText(failure)
  } finally {
    await Promise.all([refresh(), paymentStatus.refresh()])
    checking.value = false
  }
}
</script>

<template>
  <RouterLink class="back" to="/orders">‹ 返回我的订单</RouterLink>
  <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
  <LoadState :loading="loading" :error="error" @retry="refresh">
    <template v-if="data">
      <header class="page-title">
        <p class="eyebrow">订单详情</p>
        <h1>订单 {{ data.orderNo }}</h1>
        <p class="muted">{{ dateLabel(data.createTime) }}</p>
      </header>
      <div class="checkout-layout">
        <div class="stack">
          <section class="panel padded stack">
            <h2>订单商品</h2>
            <div v-for="(item, index) in data.items" :key="index" class="order-line with-image">
              <ProductImage :src="item.productImage" :name="item.productName" />
              <div><strong>{{ item.productName }}</strong><p class="muted">{{ item.skuName }} {{ snapshotSpecs(item.skuSpec) }}</p><p>{{ money(item.price) }} × {{ item.quantity }}</p></div>
              <strong>{{ money(item.amount) }}</strong>
            </div>
          </section>
          <section class="panel padded stack">
            <h2>收货信息</h2>
            <p>{{ data.receiverName }} · {{ data.receiverPhone }}</p><p>{{ data.receiverAddress }}</p>
            <p v-if="data.remark" class="preserve muted">备注 · {{ data.remark }}</p>
            <p v-if="data.trackingNo" class="muted">{{ data.logisticsCompany }} · {{ data.trackingNo }}</p>
          </section>
        </div>
        <aside class="panel padded stack checkout-summary">
          <div class="split"><h2>订单状态</h2><span class="badge">{{ data.statusDesc }}</span></div>
          <strong class="price large">{{ money(data.totalAmount) }}</strong>
          <p v-if="payment?.channel" class="notice">{{ paymentLabel }} · 仅供测试，无真实扣款。</p>
          <p v-if="checking" class="muted" role="status">支付结果处理中，正在向商城查询…</p>
          <p v-else-if="payment?.state === 'SUCCEEDED'" role="status">商城已确认{{ demo ? '演示' : '沙箱' }}付款。</p>
          <p v-else-if="payment?.state === 'PENDING'" class="muted" role="status">等待商城确认付款。已付款时可查询结果。</p>
          <p v-else-if="payment && !paymentEnabled && data.status === 0" class="muted">支付通道暂未启用。</p>
          <p v-if="paymentError" class="error" role="alert">{{ paymentError }}</p>
          <button v-if="data.status === 0 && payment?.canStart" :disabled="paying || checking" @click="pay">
            {{ paying ? '正在打开收银台…' : (payment.state === 'PENDING' ? '继续' : '去') + paymentLabel }}
          </button>
          <button v-if="paymentError || (data.status === 0 && payment?.state === 'PENDING' && paymentEnabled)"
                  class="secondary" :disabled="paying || checking" @click="checkPayment">查询支付结果</button>
        </aside>
      </div>
    </template>
  </LoadState>
</template>
