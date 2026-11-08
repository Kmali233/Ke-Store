<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { errorText, request } from '../api'
import LoadState from '../components/LoadState.vue'
import ProductImage from '../components/ProductImage.vue'
import type { OrderDetail } from '../contracts'
import { dateLabel, money, snapshotSpecs } from '../display'
import { useLoad } from '../useLoad'

const route = useRoute()
const path = '/order/' + route.params.number
const paying = ref(false)
const actionError = ref('')
const { data, loading, error, refresh } = useLoad(signal => request<OrderDetail>(path, { auth: true, signal }))
void refresh()

async function pay() {
  if (paying.value || data.value?.status !== 0) return
  paying.value = true
  actionError.value = ''
  try {
    await request(path + '/pay', { method: 'POST', auth: true })
  } catch (failure) {
    actionError.value = errorText(failure) + ' 请以刷新后的订单状态为准。'
  } finally {
    await refresh()
    paying.value = false
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
          <p class="notice">模拟支付，仅更新订单状态，无实际扣款。</p>
          <button v-if="data.status === 0" :disabled="paying" @click="pay">{{ paying ? '正在更新…' : '模拟支付' }}</button>
        </aside>
      </div>
    </template>
  </LoadState>
</template>
