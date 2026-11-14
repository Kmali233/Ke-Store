<script setup lang="ts">
import { ref } from 'vue'
import { request } from '../api'
import LoadState from '../components/LoadState.vue'
import type { OrderSummary, Page } from '../contracts'
import { dateLabel, money } from '../display'
import { useLoad } from '../useLoad'

const page = ref(1)
const { data, loading, error, refresh } = useLoad(signal =>
  request<Page<OrderSummary>>('/order/page?pageNum=' + page.value + '&pageSize=10', { auth: true, signal }),
)
void refresh()

function move(next: number) {
  page.value = next
  void refresh()
}
</script>

<template>
  <header class="page-title"><p class="eyebrow">订单记录</p><h1>我的订单</h1><p class="muted">查看订单状态和已购商品。</p></header>
  <LoadState :loading="loading" :error="error" @retry="refresh">
    <div v-if="data?.records.length" class="panel line-list">
      <RouterLink v-for="order in data.records" :key="order.orderNo" class="order-row split" :to="'/orders/' + order.orderNo">
        <div><h2>订单 {{ order.orderNo }}</h2><p class="muted">{{ dateLabel(order.createTime) }}</p></div>
        <span class="badge">{{ order.statusDesc }}</span>
        <strong class="price">{{ money(order.totalAmount) }}</strong>
        <span class="muted">查看详情 ›</span>
      </RouterLink>
    </div>
    <div v-else class="state"><p>这里还没有订单。</p><RouterLink class="button" to="/">去选购</RouterLink></div>
    <div v-if="page > 1 || (data?.pages ?? 0) > 1" class="pager">
      <button class="outline" :disabled="page <= 1" @click="move(page - 1)">上一页</button>
      <span>第 {{ page }} 页</span>
      <button class="outline" :disabled="page >= (data?.pages ?? 0)" @click="move(page + 1)">下一页</button>
    </div>
  </LoadState>
</template>
