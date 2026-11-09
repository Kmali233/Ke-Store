<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, request } from '../api'
import { useLoad } from '../useLoad'

interface Category {
  id: number
  name: string
  parentId: number
  status: number
}

interface Product {
  id: number
  name: string
  categoryId: number | null
  price: number
  stock: number
  image: string | null
  description: string | null
  status: number
}

interface Sku {
  id: number
  name: string
  price: number
  stock: number
  specs: Record<string, string>
  image: string | null
  status: number
  defaultSku: boolean
}

interface ProductDetail extends Product {
  skus: Sku[]
}

interface ProductPage {
  records: Product[]
  total: number
}

interface ProductForm {
  name: string
  categoryId: number | undefined
  price: number | undefined
  stock: number | undefined
  image: string
  description: string
  status: number
}

interface SkuForm {
  name: string
  price: number | undefined
  stock: number | undefined
  image: string
  status: number
  specs: { name: string; value: string }[]
}

const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })
const name = ref('')
const categoryId = ref<number>()
const filter = ref<{ name?: string; categoryId?: number }>({})
const page = ref(1)
const pageSize = 10
const selectedId = ref<number>()
const detailOpen = ref(false)
const productOpen = ref(false)
const skuOpen = ref(false)
const saving = ref(false)
const confirming = ref(false)
const actionError = ref('')
const formError = ref('')
const editingProduct = ref<ProductDetail>()
const editingSku = ref<Sku>()
const productForm = ref<ProductForm>(emptyProduct())
const skuForm = ref<SkuForm>(emptySku())

const categories = useLoad(signal => request<Category[]>('/admin/catalog/categories', { signal }))
const products = useLoad(signal => {
  const params = new URLSearchParams({ pageNum: String(page.value), pageSize: String(pageSize) })
  if (filter.value.name) params.set('name', filter.value.name)
  if (filter.value.categoryId !== undefined) params.set('categoryId', String(filter.value.categoryId))
  return request<ProductPage>('/admin/catalog/products/page?' + params, { signal })
})
const details = useLoad(signal => request<ProductDetail>('/admin/catalog/products/' + selectedId.value, { signal }))
const categoryNames = computed(() => new Map(categories.data.value?.map(category => [category.id, category.name])))
const productCategories = computed(() => categories.data.value?.filter(category =>
  category.status === 1 || category.id === editingProduct.value?.categoryId,
) ?? [])

void categories.refresh()
void products.refresh()

function emptyProduct(): ProductForm {
  return { name: '', categoryId: undefined, price: undefined, stock: 0, image: '', description: '', status: 1 }
}

function emptySku(): SkuForm {
  return { name: '', price: undefined, stock: 0, image: '', status: 1, specs: [{ name: '', value: '' }] }
}

function search() {
  filter.value = { name: name.value.trim(), categoryId: categoryId.value || undefined }
  goToPage(1)
}

function reset() {
  name.value = ''
  categoryId.value = undefined
  search()
}

function goToPage(value: number) {
  page.value = value
  void products.refresh()
}

function showDetails(id: number) {
  actionError.value = ''
  selectedId.value = id
  detailOpen.value = true
  void details.refresh()
}

function editProduct(product?: ProductDetail) {
  editingProduct.value = product
  productForm.value = product ? {
    name: product.name,
    categoryId: product.categoryId ?? undefined,
    price: product.skus.find(sku => sku.defaultSku)?.price,
    stock: undefined,
    image: product.image ?? '',
    description: product.description ?? '',
    status: product.status,
  } : emptyProduct()
  formError.value = ''
  productOpen.value = true
}

async function saveProduct() {
  if (saving.value) return
  const form = productForm.value
  const previous = editingProduct.value
  const name = form.name.trim()
  if (!name || form.price == null || (!previous && form.stock == null)) {
    formError.value = '请填写商品名称、默认规格价格和必填的初始库存。'
    return
  }
  const image = form.image.trim()
  const body = {
    id: previous?.id,
    name: name === previous?.name ? undefined : name,
    categoryId: form.categoryId === previous?.categoryId ? undefined : form.categoryId,
    price: form.price === previous?.skus.find(sku => sku.defaultSku)?.price ? undefined : form.price,
    stock: previous ? undefined : form.stock,
    image: image === (previous?.image ?? '') ? undefined : image,
    description: form.description === (previous?.description ?? '') ? undefined : form.description,
    status: form.status === previous?.status ? undefined : form.status,
  }
  await submit('/product', previous ? 'PUT' : 'POST', body, previous ? '商品已保存' : '商品已新增', () => {
    productOpen.value = false
  })
}

function editSku(sku?: Sku) {
  editingSku.value = sku
  skuForm.value = sku ? {
    name: sku.name,
    price: sku.price,
    stock: undefined,
    image: sku.image ?? '',
    status: sku.status,
    specs: Object.entries(sku.specs).map(([name, value]) => ({ name, value })),
  } : emptySku()
  formError.value = ''
  skuOpen.value = true
}

async function saveSku() {
  if (saving.value || selectedId.value === undefined) return
  const form = skuForm.value
  const previous = editingSku.value
  const name = form.name.trim()
  if (!name || form.price == null || (!previous && form.stock == null)) {
    formError.value = '请填写规格名称、价格和必填的初始库存。'
    return
  }
  const specNames = form.specs.map(spec => spec.name.trim())
  if (!previous?.defaultSku && (!specNames.length || specNames.some(name => !name)
      || form.specs.some(spec => !spec.value.trim()) || new Set(specNames).size !== specNames.length)) {
    formError.value = '请填写 1–5 组完整的规格名称和值，名称不可重复。'
    return
  }
  const specs = Object.fromEntries(form.specs.map(spec => [spec.name.trim(), spec.value.trim()]))
  const specsChanged = !previous || Object.keys(previous.specs).length !== specNames.length
    || specNames.some(key => previous.specs[key] !== specs[key])
  const image = form.image.trim()
  const body = {
    name: name === previous?.name ? undefined : name,
    price: form.price === previous?.price ? undefined : form.price,
    stock: previous ? undefined : form.stock,
    image: image === (previous?.image ?? '') ? undefined : image,
    status: form.status === previous?.status ? undefined : form.status,
    specs: !previous?.defaultSku && specsChanged ? specs : undefined,
  }
  const path = '/product/' + selectedId.value + '/skus' + (previous ? '/' + previous.id : '')
  await submit(path, previous ? 'PUT' : 'POST', body, previous ? 'SKU 已保存' : 'SKU 已新增', () => {
    skuOpen.value = false
  })
}

async function submit(
  path: string, method: 'POST' | 'PUT', body: unknown, success: string, close: () => void,
) {
  saving.value = true
  formError.value = ''
  try {
    await request(path, { method, body })
    close()
    ElMessage.success(success)
    await refreshCatalog()
  } catch (failure) {
    formError.value = errorText(failure)
  } finally {
    saving.value = false
  }
}

function refreshCatalog() {
  return Promise.all([
    products.refresh(),
    detailOpen.value && selectedId.value !== undefined ? details.refresh() : undefined,
  ])
}

async function remove(path: string, label: string, sku: boolean) {
  if (saving.value || confirming.value) return
  confirming.value = true
  try {
    await ElMessageBox.confirm('删除「' + label + '」？', sku ? '删除 SKU' : '删除商品', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  } finally {
    confirming.value = false
  }
  saving.value = true
  actionError.value = ''
  try {
    await request(path, { method: 'DELETE' })
    ElMessage.success(sku ? 'SKU 已删除' : '商品已删除')
    if (!sku && page.value > 1 && products.data.value?.records.length === 1) page.value--
    await refreshCatalog()
  } catch (failure) {
    actionError.value = errorText(failure)
  } finally {
    saving.value = false
  }
}

function showSpecs(specs: Record<string, string>) {
  return Object.entries(specs).map(([name, value]) => name + '：' + value).join(' · ') || '默认规格'
}
</script>

<template>
  <section aria-labelledby="catalog-heading">
    <header class="page-head">
      <div>
        <h1 id="catalog-heading">商品与规格</h1>
        <p>管理商品资料、上架状态与 SKU，库存变动集中在库存管理。</p>
      </div>
      <el-button type="primary" :disabled="saving" @click="editProduct()">新增商品</el-button>
    </header>

    <div class="panel">
      <form class="toolbar" aria-label="商品筛选" @submit.prevent="search">
        <el-input v-model="name" aria-label="商品名称" placeholder="按商品名称筛选" clearable maxlength="128" style="width: min(100%, 230px)" />
        <el-select v-model="categoryId" aria-label="商品分类" placeholder="全部分类" clearable filterable style="width: min(100%, 180px)">
          <el-option v-for="category in categories.data.value" :key="category.id" :label="category.name" :value="category.id" />
        </el-select>
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </form>
      <p v-if="categories.error.value" class="error-text" role="alert">
        分类读取失败。{{ categories.error.value }}
        <el-button link type="primary" @click="categories.refresh()">重试</el-button>
      </p>
      <p v-if="products.error.value" class="error-text" role="alert">
        {{ products.error.value }}
        <el-button link type="primary" @click="products.refresh()">重试</el-button>
      </p>
      <p v-if="actionError && !detailOpen" class="error-text" role="alert">{{ actionError }}</p>
      <el-table v-loading="products.loading.value" :data="products.error.value ? [] : (products.data.value?.records ?? [])" empty-text="暂无商品">
        <el-table-column label="商品" min-width="185">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <div>编号 #{{ row.id }}</div>
          </template>
        </el-table-column>
        <el-table-column label="分类" min-width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ categoryNames.get(row.categoryId) ?? '未分类' }}</template>
        </el-table-column>
        <el-table-column label="目录价格" width="115" align="right">
          <template #default="{ row }">{{ money.format(row.price) }}</template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="80" align="right" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light">{{ row.status === 1 ? '已上架' : '已下架' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button type="primary" link :disabled="saving" @click="showDetails(row.id)">管理商品</el-button>
              <el-button type="danger" link :disabled="saving" @click="remove('/product/' + row.id, row.name, false)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="products.data.value && !products.error.value"
        :current-page="page"
        :page-size="pageSize"
        :total="products.data.value.total"
        :pager-count="5"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: end; flex-wrap: wrap"
        @current-change="goToPage"
      />
    </div>
  </section>

  <el-drawer v-model="detailOpen" :title="details.loading.value ? '商品详情' : details.data.value?.name ?? '商品详情'" size="min(100vw, 760px)">
    <p v-if="details.error.value" class="error-text" role="alert">
      {{ details.error.value }}
      <el-button link type="primary" @click="details.refresh()">重试</el-button>
    </p>
    <div v-else-if="details.loading.value" role="status">正在读取商品详情…</div>
    <template v-else-if="details.data.value">
      <div class="toolbar">
        <el-tag :type="details.data.value.status === 1 ? 'success' : 'info'">{{ details.data.value.status === 1 ? '已上架' : '已下架' }}</el-tag>
        <span>编号 #{{ details.data.value.id }} · {{ categoryNames.get(details.data.value.categoryId ?? 0) ?? '未分类' }}</span>
        <el-button :disabled="saving" @click="editProduct(details.data.value)">编辑商品</el-button>
      </div>
      <p v-if="details.data.value.description">{{ details.data.value.description }}</p>
      <div class="page-head" style="margin-top: 24px">
        <div><h2>SKU 规格</h2><p>默认规格由商品自动创建，库存通过专用流程维护。</p></div>
        <el-button type="primary" :disabled="saving" @click="editSku()">新增 SKU</el-button>
      </div>
      <p v-if="actionError" class="error-text" role="alert">{{ actionError }}</p>
      <el-table :data="details.data.value.skus" empty-text="暂无规格">
        <el-table-column label="规格" min-width="174">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <el-tag v-if="row.defaultSku" size="small" type="info" style="margin-left: 6px">默认</el-tag>
            <div>{{ showSpecs(row.specs) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="价格" width="110" align="right">
          <template #default="{ row }">{{ money.format(row.price) }}</template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="74" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">{{ row.status === 1 ? '启用' : '停用' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="112">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" :disabled="saving" @click="editSku(row as Sku)">编辑</el-button>
              <el-button v-if="!row.defaultSku" link type="danger" :disabled="saving" @click="remove('/product/' + selectedId + '/skus/' + row.id, row.name, true)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-drawer>

  <el-dialog v-model="productOpen" :title="editingProduct ? '编辑商品' : '新增商品'" width="min(94vw, 620px)"
    append-to-body :show-close="!saving" :close-on-click-modal="!saving" :close-on-press-escape="!saving">
    <el-form label-position="top" :disabled="saving" @submit.prevent="saveProduct">
      <div class="form-grid">
        <el-form-item label="商品名称" required><el-input v-model="productForm.name" maxlength="128" /></el-form-item>
        <el-form-item label="所属分类">
          <el-select v-model="productForm.categoryId" :clearable="!editingProduct" filterable placeholder="选择分类" style="width: 100%">
            <el-option v-for="category in productCategories" :key="category.id" :label="category.name" :value="category.id" :disabled="category.status !== 1" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认规格价格（元）" required>
          <el-input-number v-model="productForm.price" :min="0" :max="99999999.99" :step="0.01" :precision="2" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="!editingProduct" label="默认规格初始库存" required>
          <el-input-number v-model="productForm.stock" :min="0" :max="2147483647" :precision="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="商品状态">
          <el-select v-model="productForm.status" style="width: 100%"><el-option label="上架" :value="1" /><el-option label="下架" :value="0" /></el-select>
        </el-form-item>
        <el-form-item label="图片地址"><el-input v-model="productForm.image" maxlength="255" placeholder="https://… 或站内路径" /></el-form-item>
      </div>
      <el-form-item label="商品描述"><el-input v-model="productForm.description" type="textarea" :rows="3" maxlength="65535" /></el-form-item>
      <p v-if="formError" class="error-text" role="alert">{{ formError }}</p>
      <div class="row-actions" style="justify-content: end">
        <el-button @click="productOpen = false">取消</el-button>
        <el-button type="primary" native-type="submit" :loading="saving">保存商品</el-button>
      </div>
    </el-form>
  </el-dialog>

  <el-dialog v-model="skuOpen" :title="editingSku ? '编辑 SKU' : '新增 SKU'" width="min(94vw, 620px)"
    append-to-body :show-close="!saving" :close-on-click-modal="!saving" :close-on-press-escape="!saving">
    <el-form label-position="top" :disabled="saving" @submit.prevent="saveSku">
      <div class="form-grid">
        <el-form-item label="SKU 名称" required><el-input v-model="skuForm.name" maxlength="128" /></el-form-item>
        <el-form-item label="销售价格（元）" required>
          <el-input-number v-model="skuForm.price" :min="0" :max="99999999.99" :step="0.01" :precision="2" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="!editingSku" label="初始库存" required>
          <el-input-number v-model="skuForm.stock" :min="0" :max="2147483647" :precision="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="SKU 状态">
          <el-select v-model="skuForm.status" style="width: 100%"><el-option label="启用" :value="1" /><el-option label="停用" :value="0" /></el-select>
        </el-form-item>
        <el-form-item label="图片地址"><el-input v-model="skuForm.image" maxlength="255" placeholder="https://… 或站内路径" /></el-form-item>
      </div>
      <fieldset v-if="!editingSku?.defaultSku" class="sku-specs">
        <legend>规格组合（必填）</legend>
        <div v-for="(spec, index) in skuForm.specs" :key="index" class="sku-spec-row">
          <el-input v-model="spec.name" :aria-label="'规格名称 ' + (index + 1)" placeholder="如 颜色" maxlength="30" />
          <el-input v-model="spec.value" :aria-label="'规格值 ' + (index + 1)" placeholder="如 墨黑" maxlength="50" />
          <el-button :disabled="skuForm.specs.length <= 1" @click="skuForm.specs.splice(index, 1)">移除</el-button>
        </div>
        <el-button :disabled="skuForm.specs.length >= 5" @click="skuForm.specs.push({ name: '', value: '' })">添加规格项</el-button>
      </fieldset>
      <p v-if="formError" class="error-text" role="alert">{{ formError }}</p>
      <div class="row-actions" style="justify-content: end">
        <el-button @click="skuOpen = false">取消</el-button>
        <el-button type="primary" native-type="submit" :loading="saving">保存 SKU</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.sku-specs { min-width: 0; border: 1px solid var(--el-border-color); border-radius: 6px; margin: 0 0 16px; padding: 12px; }
.sku-specs legend { color: var(--el-text-color-primary); padding: 0 5px; }
.sku-spec-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 8px; margin-bottom: 8px; }
</style>
