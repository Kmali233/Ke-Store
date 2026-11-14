<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText, request } from '../api'
import LoadState from '../components/LoadState.vue'
import ProductImage from '../components/ProductImage.vue'
import type { OrderDetail, OrderRefund, PaymentStatus } from '../contracts'
import { dateLabel, money, snapshotSpecs } from '../display'
import { openCheckout } from '../payment'
import { useLoad } from '../useLoad'

const route = useRoute()
const router = useRouter()
const path = '/order/' + route.params.number
const paymentPath = path + '/payment'
const purchaseNotice = route.query.buyNotice === 'price'
  ? '订单已创建，实际金额有变化，请核对后再付款。'
  : route.query.buyNotice === 'payment' ? '订单已创建，可以在此查看或继续付款。' : ''
const returned = route.query.paymentReturn === '1'
const paying = ref(false)
const checking = ref(false)
const canceling = ref(false)
const refunding = ref(false)
const busy = computed(() => paying.value || checking.value || canceling.value || refunding.value)
const refundNotice = ref('')
const actionError = ref('')
const actions = new AbortController()
onScopeDispose(() => actions.abort())

const { data, loading, error, refresh } = useLoad(signal => request<OrderDetail>(path, { auth: true, signal }))
const refund = useLoad(signal => request<OrderRefund>(path + '/refund', { auth: true, signal }))
const refundLabels = {
  REQUESTED: '已申请，等待审核',
  PROCESSING: '退款处理中，等待通道确认',
  REJECTED: '退款申请已拒绝',
  SUCCEEDED: '已退款',
  FAILED: '通道退款失败，请联系商家核对',
}
const paymentStatus = useLoad(signal => request<PaymentStatus>(paymentPath, { auth: true, signal }))
const { data: payment, error: paymentError } = paymentStatus
const demo = computed(() => payment.value?.channel === 'DEMO')
const paymentEnabled = computed(() => demo.value ? payment.value?.demoEnabled : payment.value?.sandboxEnabled)
const wechatQrUrl = import.meta.env.VITE_LOCAL_WECHAT_QR
const showWechatQr = computed(() => Boolean(wechatQrUrl && data.value?.status === 0
  && payment.value?.state === 'NONE' && !payment.value.sandboxEnabled && !payment.value.demoEnabled
  && !payment.value.paymentExpired && !payment.value.closeProcessing))
const paymentLabel = computed(() => demo.value ? '演示支付' : '支付宝沙箱支付')
void refresh()
void refund.refresh()
if (returned) {
  // The redirect is a hint to query. All other provider URL parameters are discarded.
  void router.replace({ path: route.path })
  void checkPayment()
} else {
  void paymentStatus.refresh()
}

async function pay() {
  if (busy.value || !payment.value?.canStart || data.value?.status !== 0) return
  paying.value = true
  actionError.value = ''
  try {
    await openCheckout(router, paymentPath, demo.value, actions.signal)
  } catch (failure) {
    if (!actions.signal.aborted) actionError.value = errorText(failure)
    await Promise.all([refresh(), paymentStatus.refresh()])
  } finally {
    paying.value = false
  }
}

async function simulatePayment() {
  if (busy.value || !payment.value?.canSimulate || data.value?.status !== 0) return
  if (!window.confirm('将这笔订单标记为模拟已付款？此操作不会扣款。')) return
  paying.value = true
  actionError.value = ''
  try {
    await request<void>(path + '/pay', { method: 'POST', auth: true, signal: actions.signal })
  } catch (failure) {
    if (!actions.signal.aborted) actionError.value = errorText(failure)
  } finally {
    await refreshOrder()
    paying.value = false
  }
}

async function cancelOrder() {
  if (busy.value || data.value?.status !== 0) return
  canceling.value = true
  actionError.value = ''
  try {
    await request<void>(path + '/cancel', { method: 'POST', auth: true, signal: actions.signal })
  } catch (failure) {
    if (!actions.signal.aborted) actionError.value = errorText(failure)
  } finally {
    await refreshOrder()
    canceling.value = false
  }
}

async function refreshOrder() {
  await Promise.all([refresh(), paymentStatus.refresh(), refund.refresh()])
}

async function requestRefund() {
  if (busy.value) return
  if (!window.confirm('申请退回这笔订单的全部实付金额？')) return
  refunding.value = true
  actionError.value = ''
  refundNotice.value = ''
  try {
    await request(path + '/refund', { method: 'POST', auth: true, signal: actions.signal })
    refundNotice.value = '已提交整单退款申请。'
  } catch (failure) {
    if (!actions.signal.aborted) actionError.value = errorText(failure)
  } finally {
    await refreshOrder()
    refunding.value = false
  }
}

async function checkPayment() {
  if (busy.value) return
  checking.value = true
  actionError.value = ''
  try {
    await request<PaymentStatus>(paymentPath + '/query', {
      method: 'POST', auth: true, signal: actions.signal,
    })
  } catch (failure) {
    if (!actions.signal.aborted) actionError.value = errorText(failure)
  } finally {
    await refreshOrder()
    checking.value = false
  }
}
</script>

<template>
  <RouterLink class="back" to="/orders">‹ 返回我的订单</RouterLink>
  <p v-if="purchaseNotice" class="notice" role="status">{{ purchaseNotice }}</p>
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
          <p v-if="data.remark" class="preserve muted">备注 · {{ data.remark }}</p>
        </div>
        <aside class="panel padded stack checkout-summary">
          <div class="split"><h2>订单状态</h2><span class="badge">{{ refund.data.value?.status === 'SUCCEEDED' ? '已退款' : data.statusDesc }}</span></div>
          <p v-if="refundNotice" role="status">{{ refundNotice }}</p>
          <p v-if="refund.error.value" class="error" role="alert">{{ refund.error.value }}</p>
          <p v-if="refund.data.value?.status" role="status">
            {{ refundLabels[refund.data.value.status] }} · {{ money(refund.data.value.amount) }}
          </p>
          <button v-if="refund.data.value?.canRequest && !refund.loading.value" class="secondary"
                  :disabled="busy" @click="requestRefund">申请整单退款</button>
          <button v-if="refund.error.value || (refund.data.value?.status && refund.data.value.status !== 'REJECTED')"
                  class="secondary" :disabled="busy" @click="refreshOrder">刷新退款进度</button>
          <strong class="price large">{{ money(data.totalAmount) }}</strong>
          <p v-if="payment?.channel" class="notice">{{ paymentLabel }} · 仅供测试，无真实扣款。</p>
          <p v-if="checking" class="muted" role="status">支付结果处理中，正在向商城查询…</p>
          <p v-else-if="payment?.state === 'SUCCEEDED'" role="status">商城已确认{{ demo ? '演示' : '沙箱' }}付款。</p>
          <p v-else-if="payment?.closeProcessing" class="muted" role="status">订单关单处理中，正在确认支付状态。</p>
          <p v-else-if="payment?.paymentExpired" class="muted" role="status">付款期限已过，等待系统处理。</p>
          <p v-else-if="payment?.state === 'PENDING'" class="muted" role="status">等待商城确认付款。已付款时可查询结果。</p>
          <div v-else-if="showWechatQr" class="stack">
            <h3>微信扫码收款</h3>
            <img class="wechat-collection-qr" :src="wechatQrUrl" width="208" height="208" alt="微信收款二维码">
            <p class="muted">扫码付款可能实际扣款。付款前请联系商家确认订单，到账后需要人工核对，商城不会自动更新付款状态。</p>
          </div>
          <p v-else-if="payment && !paymentEnabled && !payment.canSimulate && data.status === 0" class="muted">支付通道暂未启用。</p>
          <p v-if="paymentError" class="error" role="alert">{{ paymentError }}</p>
          <button v-if="data.status === 0 && payment?.canSimulate" :disabled="busy" @click="simulatePayment">
            {{ paying ? '正在更新订单…' : '模拟支付成功' }}
          </button>
          <button v-if="data.status === 0 && payment?.canStart" :disabled="busy" @click="pay">
            {{ paying ? '正在打开收银台…' : (payment.state === 'PENDING' ? '继续' : '去') + paymentLabel }}
          </button>
          <button v-if="paymentError || (data.status === 0 && payment?.state === 'PENDING' && paymentEnabled)"
                  class="secondary" :disabled="busy" @click="checkPayment">查询支付结果</button>
          <button v-if="data.status === 0 && (payment?.closeProcessing || payment?.paymentExpired)"
                  class="secondary" :disabled="busy" @click="refreshOrder">刷新订单状态</button>
          <button v-else-if="data.status === 0" class="secondary" :disabled="busy" @click="cancelOrder">
            {{ canceling ? '正在取消…' : '取消订单' }}
          </button>
        </aside>
      </div>
    </template>
  </LoadState>
</template>

<style scoped>
.wechat-collection-qr {
  display: block;
  max-width: 100%;
  height: auto;
}
</style>
