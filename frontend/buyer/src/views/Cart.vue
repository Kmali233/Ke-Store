<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { errorText, request } from '../api'
import { availabilityLabel, cartTotal, loadCart } from '../cart'
import { readPending } from '../checkout'
import LoadState from '../components/LoadState.vue'
import ProductImage from '../components/ProductImage.vue'
import type { CartItem } from '../contracts'
import { money, snapshotSpecs } from '../display'
import { useSession } from '../session'
import { useLoad } from '../useLoad'

const session = useSession()
const { data, loading, error, refresh } = useLoad(loadCart)
const saving = ref(false)
const mutationError = ref('')
const quantities = reactive<Record<number, number>>({})
const selected = computed(() => data.value?.filter(line => line.selected) ?? [])
const eligible = computed(() => selected.value.length > 0 && selected.value.length <= 20
  && selected.value.every(line => line.availability === 'AVAILABLE'))
const pending = computed(() => {
  try {
    return session.identity ? Boolean(readPending(session.identity.userInfo.id)) : false
  } catch {
    return false
  }
})

watch(data, lines => {
  for (const line of lines ?? []) quantities[line.skuId] = line.quantity
})
void refresh()

async function mutate(skuId: number, method: 'PATCH' | 'DELETE', body?: { quantity?: number; selected?: boolean }) {
  if (saving.value) return
  saving.value = true
  mutationError.value = ''
  try {
    await request('/cart/items/' + skuId, { method, body, auth: true })
  } catch (failure) {
    mutationError.value = errorText(failure)
  } finally {
    await refresh()
    saving.value = false
  }
}

function changeQuantity(line: CartItem) {
  const quantity = quantities[line.skuId]
  if (Number.isInteger(quantity) && quantity >= 1 && quantity <= 999 && quantity !== line.quantity) {
    void mutate(line.skuId, 'PATCH', { quantity })
  }
}
</script>

<template>
  <header class="page-title">
    <p class="eyebrow">购物袋</p>
    <h1>我的购物车</h1>
    <p class="muted">勾选要购买的规格，再去确认结算信息。</p>
  </header>
  <p v-if="mutationError" class="error" role="alert">{{ mutationError }}</p>
  <p v-if="pending" class="notice"><RouterLink to="/checkout">查看上一次结算结果</RouterLink></p>
  <LoadState :loading="loading" :error="error" @retry="refresh">
    <template v-if="data?.length">
      <div class="panel line-list">
        <article v-for="line in data" :key="line.skuId" class="cart-line">
          <label class="select-item">
            <input type="checkbox" :checked="line.selected" :disabled="saving"
              @change="mutate(line.skuId, 'PATCH', { selected: ($event.target as HTMLInputElement).checked })">
            <span class="sr-only">勾选 {{ line.skuName || line.productName || '失效规格' }}</span>
          </label>
          <ProductImage :src="line.image" :name="line.productName || ''" />
          <div class="line-name">
            <h2 v-if="line.productId && line.availability !== 'INVALID_SKU' && line.availability !== 'OFF_SHELF'">
              <RouterLink :to="'/products/' + line.productId">{{ line.productName }}</RouterLink>
            </h2>
            <h2 v-else>{{ line.productName || '商品已失效' }}</h2>
            <p class="muted">{{ line.skuName }} {{ snapshotSpecs(line.skuSpec) }}</p>
            <span class="badge" :class="{ warning: line.availability !== 'AVAILABLE' }">{{ availabilityLabel[line.availability] }}</span>
          </div>
          <div class="line-controls">
            <strong class="price">{{ money(line.price) }}</strong>
            <form class="quantity-form" @submit.prevent="changeQuantity(line)">
              <label class="sr-only" :for="'quantity-' + line.skuId">数量 {{ line.skuName || line.productName }}</label>
              <input :id="'quantity-' + line.skuId" v-model.number="quantities[line.skuId]" type="number" min="1" max="999" required :disabled="saving">
              <button class="outline" type="submit" :disabled="saving || quantities[line.skuId] === line.quantity">更新</button>
            </form>
            <button class="quiet" type="button" :disabled="saving" @click="mutate(line.skuId, 'DELETE')">移除</button>
          </div>
        </article>
      </div>
      <div class="panel padded split cart-total">
        <div>
          <span>已选 {{ selected.length }} 种 · 参考合计 </span><strong class="price">{{ money(cartTotal(selected)) }}</strong>
          <p v-if="selected.length > 20" class="muted">每单最多结算 20 种规格，请分批结算。</p>
          <p v-else-if="selected.some(line => line.availability !== 'AVAILABLE')" class="muted">请调整库存不足的数量，或取消勾选不可结算的商品。</p>
          <p v-else-if="!selected.length" class="muted">请先勾选商品。</p>
        </div>
        <RouterLink v-if="eligible && !saving" class="button" to="/checkout">去结算</RouterLink>
        <button v-else disabled>去结算</button>
      </div>
    </template>
    <div v-else class="state">
      <p>购物车还是空的。</p>
      <RouterLink class="button" to="/">去选购</RouterLink>
    </div>
  </LoadState>
</template>
