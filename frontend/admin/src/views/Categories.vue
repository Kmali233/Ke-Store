<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errorText, request } from '../api'
import { useLoad } from '../useLoad'

interface Category {
  id: number
  name: string
  parentId: number
  icon: string | null
  sort: number
  status: number
}

const { data, error, loading, refresh } = useLoad(signal => request<Category[]>('/admin/catalog/categories', { signal }))
const editing = ref<Category>()
const editorOpen = ref(false)
const saving = ref(false)
const confirming = ref(false)
const formError = ref('')
const actionError = ref('')
const form = ref({ name: '', parentId: 0, icon: '', sort: 0 as number | undefined, status: 1 })
const categoryById = computed(() => new Map(data.value?.map(category => [category.id, category])))
const parents = computed(() => (data.value ?? []).filter(category => {
  if (category.status !== 1 && category.id !== editing.value?.parentId) return false
  let parent: Category | undefined = category
  const visited = new Set<number>()
  while (parent) {
    if (parent.id === editing.value?.id || visited.has(parent.id)) return false
    visited.add(parent.id)
    parent = categoryById.value.get(parent.parentId)
  }
  return true
}))

void refresh()

function openEditor(category?: Category) {
  editing.value = category
  form.value = category ? {
    name: category.name, parentId: category.parentId, icon: category.icon ?? '', sort: category.sort, status: category.status,
  } : { name: '', parentId: 0, icon: '', sort: 0, status: 1 }
  formError.value = ''
  editorOpen.value = true
}

async function saveCategory() {
  if (saving.value) return
  const name = form.value.name.trim()
  if (!name) {
    formError.value = '请输入分类名称。'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    const previous = editing.value
    const icon = form.value.icon.trim()
    await request('/category', {
      method: previous ? 'PUT' : 'POST',
      body: {
        id: previous?.id,
        name: name === previous?.name ? undefined : name,
        parentId: previous?.parentId === form.value.parentId ? undefined : form.value.parentId,
        icon: icon === (previous?.icon ?? '') ? undefined : icon,
        sort: form.value.sort === previous?.sort ? undefined : form.value.sort,
        status: form.value.status === previous?.status ? undefined : form.value.status,
      },
    })
    editorOpen.value = false
    ElMessage.success(previous ? '分类已保存' : '分类已新增')
    await refresh()
  } catch (failure) {
    formError.value = errorText(failure)
  } finally {
    saving.value = false
  }
}

async function removeCategory(category: Category) {
  if (saving.value || confirming.value) return
  confirming.value = true
  try {
    await ElMessageBox.confirm('删除「' + category.name + '」？有商品或子分类时需要先解除关联。', '删除分类', {
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
    await request('/category/' + category.id, { method: 'DELETE' })
    ElMessage.success('分类已删除')
    await refresh()
  } catch (failure) {
    actionError.value = errorText(failure)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section aria-labelledby="categories-heading">
    <header class="page-head">
      <div>
        <h1 id="categories-heading">商品分类</h1>
        <p>维护买家端的分类层级、显示顺序与启用状态。</p>
      </div>
      <el-button type="primary" :disabled="saving" @click="openEditor()">新增分类</el-button>
    </header>

    <div class="panel">
      <p v-if="error" class="error-text" role="alert">{{ error }} <el-button link type="primary" @click="refresh">重试</el-button></p>
      <p v-if="actionError" class="error-text" role="alert">{{ actionError }}</p>
      <el-table v-loading="loading" :data="error ? [] : data ?? []" empty-text="暂无分类">
        <el-table-column label="分类" min-width="160">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <div>编号 #{{ row.id }}</div>
          </template>
        </el-table-column>
        <el-table-column label="上级分类" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.parentId === 0 ? '顶级' : categoryById.get(row.parentId)?.name ?? ('#' + row.parentId) }}</template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="80" align="right" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="128">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button type="primary" link :disabled="saving" @click="openEditor(row as Category)">编辑</el-button>
              <el-button type="danger" link :disabled="saving" @click="removeCategory(row as Category)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>

  <el-dialog v-model="editorOpen" :title="editing ? '编辑分类' : '新增分类'" width="min(94vw, 560px)"
    :show-close="!saving" :close-on-click-modal="!saving" :close-on-press-escape="!saving">
    <el-form label-position="top" :disabled="saving" @submit.prevent="saveCategory">
      <div class="form-grid">
        <el-form-item label="分类名称" required><el-input v-model="form.name" maxlength="50" /></el-form-item>
        <el-form-item label="上级分类">
          <el-select v-model="form.parentId" filterable style="width: 100%">
            <el-option label="顶级分类" :value="0" />
            <el-option v-for="parent in parents" :key="parent.id" :label="parent.name" :value="parent.id" :disabled="parent.status !== 1" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="-2147483648" :max="2147483647" :precision="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%"><el-option label="启用" :value="1" /><el-option label="停用" :value="0" /></el-select>
        </el-form-item>
      </div>
      <el-form-item label="图标地址"><el-input v-model="form.icon" maxlength="255" placeholder="https://… 或站内路径" /></el-form-item>
      <p v-if="formError" class="error-text" role="alert">{{ formError }}</p>
      <div class="row-actions" style="justify-content: end">
        <el-button @click="editorOpen = false">取消</el-button>
        <el-button type="primary" native-type="submit" :loading="saving">保存分类</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
