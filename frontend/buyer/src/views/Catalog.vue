<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
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

const { data: categories, loading: categoriesLoading, error: categoriesError, refresh: loadCategories } = useLoad(signal =>
  request<Category[]>('/category/list', { signal }),
)
const categoriesById = computed(() => new Map((categories.value ?? []).map(item => [item.id, item])))
const selectedCategory = computed(() => categoriesById.value.get(categoryId.value ?? 0))
const categoryPath = computed(() => {
  const path: Category[] = []
  const seen = new Set<number>()
  let category = selectedCategory.value
  while (category && !seen.has(category.id)) {
    path.unshift(category)
    seen.add(category.id)
    category = categoriesById.value.get(category.parentId)
  }
  return path
})
const topCategories = computed(() => (categories.value ?? []).filter(item => !categoriesById.value.has(item.parentId)))
const subcategoryRows = computed(() => categoryPath.value
  .map(parent => ({
    parent,
    items: (categories.value ?? []).filter(item => item.parentId === parent.id && item.id !== parent.id),
  }))
  .filter(row => row.items.length > 0))
const title = computed(() => activeKeyword.value ? '搜索结果' : selectedCategory.value?.name
  || (categoryId.value ? '分类商品' : '数字商品'))

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
  <section id="catalog" class="catalog" aria-labelledby="catalog-title">
    <header class="catalog-heading">
      <div class="shell">
        <nav v-if="activeKeyword || categoryId" class="catalog-path" aria-label="目录路径">
          <RouterLink :to="catalogLink()">数字商品</RouterLink>
          <template v-for="parent in categoryPath.slice(0, -1)" :key="parent.id">
            <span aria-hidden="true">/</span><RouterLink :to="catalogLink(parent.id)">{{ parent.name }}</RouterLink>
          </template>
        </nav>
        <h1 id="catalog-title">{{ title }}</h1>
        <p v-if="activeKeyword" class="catalog-keyword">关键词「{{ activeKeyword }}」</p>
      </div>
    </header>

    <div class="shell catalog-content">
      <LoadState :loading="categoriesLoading" :error="categoriesError" @retry="loadCategories">
        <nav v-if="topCategories.length" class="panel catalog-filters" aria-label="商品分类筛选">
          <div class="filter-row">
            <span class="filter-label">分类</span>
            <div class="filter-links">
              <RouterLink :to="catalogLink()" :aria-current="!categoryId && !activeKeyword ? 'true' : undefined">全部商品</RouterLink>
              <RouterLink v-for="item in topCategories" :key="item.id" :to="catalogLink(item.id)"
                :aria-current="categoryPath[0]?.id === item.id ? 'true' : undefined">{{ item.name }}</RouterLink>
            </div>
          </div>
          <div v-for="(row, index) in subcategoryRows" :key="row.parent.id" class="filter-row">
            <span class="filter-label">{{ row.parent.name }}</span>
            <div class="filter-links">
              <RouterLink :to="catalogLink(row.parent.id)"
                :aria-current="categoryId === row.parent.id ? 'true' : undefined">全部</RouterLink>
              <RouterLink v-for="item in row.items" :key="item.id" :to="catalogLink(item.id)"
                :aria-current="categoryPath[index + 1]?.id === item.id ? 'true' : undefined">{{ item.name }}</RouterLink>
            </div>
          </div>
        </nav>
      </LoadState>

      <div class="catalog-results">
        <h2>商品列表</h2>
        <p v-if="!loading && !error && data" class="muted">共 {{ data.total }} 件</p>
      </div>
      <LoadState :loading="loading" :error="error" @retry="refresh">
        <div v-if="data?.records.length" class="product-grid">
          <RouterLink v-for="product in data.records" :key="product.id" class="product-card" :to="'/products/' + product.id">
            <ProductImage :src="product.image" :name="product.name" />
            <div class="product-card-body">
              <h3>{{ product.name }}</h3>
              <div class="card-meta">
                <strong class="price">{{ money(product.price) }}</strong>
                <span v-if="product.stock <= 0" class="stock-label">已售罄</span>
              </div>
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
    </div>
  </section>
</template>
