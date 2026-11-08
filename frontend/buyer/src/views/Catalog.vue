<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import phone from '../../../assets/xiaomi-18-pro.jpg'
import notebook from '../../../assets/xiaomi-book-pro14.jpg'
import television from '../../../assets/tv-s-mini.jpg'
import wearable from '../../../assets/watch-s5.jpg'
import { request } from '../api'
import LoadState from '../components/LoadState.vue'
import ProductImage from '../components/ProductImage.vue'
import type { Category, Page, Product } from '../contracts'
import { money } from '../display'
import { useLoad } from '../useLoad'

const route = useRoute()
const activeKeyword = computed(() => typeof route.query.q === 'string' ? route.query.q.slice(0, 128).trim() : '')
const categoryId = computed(() => activeKeyword.value ? undefined : positiveInteger(route.query.category))
const page = computed(() => positiveInteger(route.query.page) ?? 1)
const isHome = computed(() => !activeKeyword.value && !categoryId.value && page.value === 1)

const { data: categories, refresh: loadCategories } = useLoad(signal =>
  request<Category[]>('/category/list', { signal }),
)
const categoryName = computed(() => categories.value?.find(category => category.id === categoryId.value)?.name)
const departments = [
  { pattern: /手机/, image: phone, caption: '旗舰与日常通讯' },
  { pattern: /电视|影音/, image: television, caption: '把好画质带回家' },
  { pattern: /电脑|笔记本|平板/, image: notebook, caption: '工作与学习好物' },
  { pattern: /穿戴|手表/, image: wearable, caption: '出门随身好物' },
]
const featured = computed(() => departments.flatMap(department => {
  const category = categories.value?.find(item => department.pattern.test(item.name))
  return category ? [{ ...department, category }] : []
}).slice(0, 3))

const { data, loading, error, refresh } = useLoad(signal => {
  const params = new URLSearchParams({ pageNum: String(page.value), pageSize: '12' })
  if (activeKeyword.value) params.set('keyword', activeKeyword.value)
  if (categoryId.value) params.set('categoryId', String(categoryId.value))
  const endpoint = activeKeyword.value ? 'search' : 'page'
  return request<Page<Product>>('/product/' + endpoint + '?' + params, { signal })
})

watch([activeKeyword, categoryId, page], refresh, { immediate: true })
void loadCategories()

function positiveInteger(value: unknown) {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return undefined
  const number = Number(value)
  return Number.isSafeInteger(number) && number > 0 ? number : undefined
}

function catalogLink(category?: number, nextPage = 1, q?: string) {
  return { path: '/', query: { category, page: nextPage > 1 ? nextPage : undefined, q }, hash: '#catalog' }
}
</script>

<template>
  <section v-if="isHome" class="storefront" aria-label="商城首页">
    <nav class="category-menu" aria-label="商品分类">
      <span class="category-title">商品分类</span>
      <RouterLink :to="catalogLink()">全部商品 <span aria-hidden="true">›</span></RouterLink>
      <RouterLink v-for="category in categories" :key="category.id" :to="catalogLink(category.id)"
        :class="{ 'category-child': category.parentId > 0 }">
        {{ category.name }} <span aria-hidden="true">›</span>
      </RouterLink>
    </nav>
    <div class="store-hero">
      <div class="hero-copy">
        <p class="eyebrow">XIAOKE STORE / 好物选购</p>
        <h1>把科技好物<br>带进日常。</h1>
        <p class="hero-description">从随身设备到居家好物，找到适合你的那一件。</p>
        <RouterLink class="button" :to="catalogLink()">开始选购 <span aria-hidden="true">→</span></RouterLink>
      </div>
      <div class="hero-visual" aria-hidden="true"><img :src="phone" alt="" fetchpriority="high"></div>
      <span class="hero-caption">数码商品示意</span>
    </div>
  </section>

  <div v-if="isHome && featured.length" class="department-grid" aria-label="常逛分类">
    <RouterLink v-for="{ category, image, caption } in featured" :key="category.id"
      class="department-card" :to="catalogLink(category.id)">
      <div><strong>{{ category.name }}</strong><p>{{ caption }}</p><span>进入分类 <span aria-hidden="true">›</span></span></div>
      <img :src="image" alt="" width="120" height="100">
    </RouterLink>
  </div>

  <section id="catalog" class="catalog" aria-labelledby="catalog-title">
    <div class="section-head">
      <div>
        <p class="eyebrow">商品目录</p>
        <h2 id="catalog-title">{{ activeKeyword ? '搜索结果' : categoryName || (categoryId ? '分类商品' : '在售商品') }}</h2>
      </div>
      <p v-if="!loading && !error && data" class="muted">{{ activeKeyword ? '“' + activeKeyword + '” · ' : '' }}共 {{ data.total }} 件</p>
    </div>
    <nav v-if="categories?.length" class="catalog-filters" aria-label="筛选商品">
      <RouterLink :to="catalogLink()" :aria-current="!categoryId && !activeKeyword ? 'true' : 'false'">全部</RouterLink>
      <RouterLink v-for="category in categories" :key="category.id" :to="catalogLink(category.id)"
        :aria-current="categoryId === category.id ? 'true' : 'false'">{{ category.name }}</RouterLink>
    </nav>
    <LoadState :loading="loading" :error="error" @retry="refresh">
      <div v-if="data?.records.length" class="product-grid">
        <RouterLink v-for="product in data.records" :key="product.id" class="product-card" :to="'/products/' + product.id">
          <ProductImage :src="product.image" :name="product.name" />
          <div class="product-card-body">
            <h3>{{ product.name }}</h3>
            <p class="muted clamp">{{ product.description || '查看规格与商品详情' }}</p>
            <strong class="price">{{ money(product.price) }}</strong>
            <span v-if="product.stock <= 0" class="muted stock-label">暂时售罄</span>
          </div>
        </RouterLink>
      </div>
      <div v-else class="state">
        <p>{{ activeKeyword ? '没有找到相关商品，试试其他关键词。' : '这一页暂无在售商品。' }}</p>
        <RouterLink v-if="activeKeyword || categoryId" class="button outline" :to="catalogLink()">查看全部商品</RouterLink>
      </div>
      <div v-if="page > 1 || (data?.pages ?? 0) > 1" class="pager">
        <RouterLink v-if="page > 1" class="button outline" :to="catalogLink(categoryId, page - 1, activeKeyword || undefined)">上一页</RouterLink>
        <button v-else class="outline" disabled>上一页</button>
        <span>第 {{ page }} 页</span>
        <RouterLink v-if="page < (data?.pages ?? 0)" class="button outline" :to="catalogLink(categoryId, page + 1, activeKeyword || undefined)">下一页</RouterLink>
        <button v-else class="outline" disabled>下一页</button>
      </div>
    </LoadState>
  </section>
</template>
