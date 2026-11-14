<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorText, request } from '../api'
import DirectPurchase from '../components/DirectPurchase.vue'
import LoadState from '../components/LoadState.vue'
import ProductImage from '../components/ProductImage.vue'
import type { ProductDetail } from '../contracts'
import { money, specsLabel } from '../display'
import { useSession } from '../session'
import { useLoad } from '../useLoad'

const route = useRoute()
const router = useRouter()
const session = useSession()
const skuId = ref<number>()
const quantity = ref(1)
const submitting = ref(false)
const notice = ref('')
const addError = ref('')
const purchaseOpen = ref(false)
const { data, loading, error, refresh } = useLoad(signal =>
  request<ProductDetail>('/product/' + route.params.id, { signal }),
)
const sku = computed(() => data.value?.skus.find(item => item.id === skuId.value))
const maxQuantity = computed(() => Math.min(sku.value?.stock ?? 0, 999))
watch(data, product => {
  const available = product?.skus.filter(item => item.status === 1 && item.stock > 0) ?? []
  skuId.value = (available.find(item => item.defaultSku) ?? available[0])?.id
})
watch(skuId, () => {
  quantity.value = 1
  notice.value = ''
  addError.value = ''
}, { flush: 'sync' })
const validQuantity = computed(() => Number.isInteger(quantity.value)
  && quantity.value >= 1 && quantity.value <= maxQuantity.value)
watch([data, () => route.query.buy, () => session.identity?.userInfo.id], ([product, buy, userId]) => {
  if (!product || buy !== '1' || !userId) return
  const previousSku = product.skus.find(item => String(item.id) === route.query.sku && item.status === 1 && item.stock > 0)
  if (previousSku) skuId.value = previousSku.id
  const previousQuantity = Number(route.query.qty)
  if (sku.value && Number.isInteger(previousQuantity) && previousQuantity >= 1 && previousQuantity <= maxQuantity.value) {
    quantity.value = previousQuantity
  }
  purchaseOpen.value = !!sku.value
  const { buy: _, sku: _sku, qty: _qty, ...query } = route.query
  void router.replace({ query })
}, { flush: 'post' })
watch(() => session.identity, identity => {
  if (identity || !purchaseOpen.value) return
  purchaseOpen.value = false
  const next = router.resolve({ path: route.path, query: { ...route.query, buy: '1' } }).fullPath
  void router.replace({ path: '/login', query: { next, expired: '1' } })
})
void refresh()

async function buyNow() {
  if (!sku.value || !validQuantity.value || submitting.value) return
  if (!session.identity) {
    const next = router.resolve({ path: route.path, query: { ...route.query, buy: '1', sku: sku.value.id, qty: quantity.value } }).fullPath
    await router.push({ path: '/login', query: { next } })
    return
  }
  purchaseOpen.value = true
}

async function addToCart() {
  if (submitting.value || skuId.value === undefined || !validQuantity.value) return
  if (!session.identity) {
    await router.push({ path: '/login', query: { next: route.fullPath } })
    return
  }
  submitting.value = true
  notice.value = ''
  addError.value = ''
  try {
    await request('/cart/items', { method: 'POST', auth: true, body: { skuId: skuId.value, quantity: quantity.value } })
    notice.value = '已加入购物车。'
  } catch (failure) {
    addError.value = errorText(failure)
    if (!session.identity) await router.replace({ path: '/login', query: { next: route.fullPath, expired: '1' } })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <RouterLink class="back" to="/">‹ 返回选购</RouterLink>
  <LoadState :loading="loading" :error="error" @retry="refresh">
    <template v-if="data">
      <div class="product-layout panel">
        <ProductImage :src="sku?.image || data.image" :name="data.name" />
        <section class="product-info">
          <p class="eyebrow">商品详情</p>
          <h1>{{ data.name }}</h1>
          <p class="muted preserve">{{ data.description }}</p>
          <p class="price large">{{ money(sku?.price ?? data.price) }}</p>
          <fieldset class="options" :disabled="submitting">
            <legend>选择规格</legend>
            <label v-for="option in data.skus" :key="option.id" class="choice">
              <input v-model="skuId" type="radio" name="sku" :value="option.id" :disabled="option.stock < 1 || option.status !== 1">
              <span>{{ option.name }} <small>{{ specsLabel(option.specs) }}</small><small v-if="option.status !== 1"> · 不可购买</small><small v-else-if="option.stock < 1"> · 售罄</small></span>
            </label>
          </fieldset>
          <p v-if="!sku" class="muted">暂时没有可购买的规格。</p>
          <form v-else class="stack" @submit.prevent="addToCart">
            <label class="field quantity">数量（库存 {{ sku.stock }}）
              <input v-model.number="quantity" required type="number" min="1" :max="maxQuantity" :disabled="submitting">
            </label>
            <div class="actions">
              <button type="button" :disabled="submitting || !validQuantity" @click="buyNow">立即购买</button>
              <button class="outline" type="submit" :disabled="submitting || !validQuantity">{{ submitting ? '正在加入…' : '加入购物车' }}</button>
              <RouterLink class="button outline" to="/cart">查看购物车</RouterLink>
            </div>
          </form>
          <p v-if="notice" class="success" role="status">{{ notice }}</p>
          <p v-if="addError" class="error" role="alert">{{ addError }}</p>
        </section>
      </div>
      <DirectPurchase v-if="purchaseOpen && sku && session.identity" :product="data" :sku="sku"
        :initial-quantity="quantity" :user-id="session.identity.userInfo.id" @close="purchaseOpen = false" />
      <section v-if="data.attrs.length" class="panel padded spaced">
        <h2>商品参数</h2>
        <dl class="facts">
          <template v-for="(attr, index) in data.attrs" :key="index">
            <dt>{{ attr.attrGroup ? attr.attrGroup + ' / ' : '' }}{{ attr.attrName }}</dt><dd>{{ attr.attrValue }}</dd>
          </template>
        </dl>
      </section>
    </template>
  </LoadState>
</template>
