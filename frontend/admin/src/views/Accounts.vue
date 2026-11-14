<script setup lang="ts">
import { computed, ref } from 'vue'
import { errorText, request } from '../api'
import { useLoad } from '../useLoad'

interface Role {
  id: number
  code: string
  name: string
  permissions: string[]
}

interface Account {
  id: number
  username: string
  status: number
  roles: Role[]
}

type Editor = { type: 'create' } | { type: 'roles' | 'status'; account: Account }

const directory = '/admin/access'
const { data, loading, error, refresh } = useLoad(async (signal) => {
  const [accounts, roles, actor] = await Promise.all([
    request<Account[]>(directory + '/accounts', { signal }),
    request<Role[]>(directory + '/roles', { signal }),
    request<{ id: number }>(directory + '/me', { signal }),
  ])
  return { accounts, roles, actorId: actor.id }
})
const editor = ref<Editor>()
const username = ref('')
const password = ref('')
const roleIds = ref<number[]>([])
const saving = ref(false)
const failure = ref('')
const confirmation = ref('')
const title = computed(() => {
  if (editor.value?.type === 'roles') return '分配角色'
  if (editor.value?.type === 'status') return editor.value.account.status === 1 ? '停用账号' : '启用账号'
  return '新建账号'
})

const permissions: Record<string, string> = {
  'admin:manage': '账号管理',
  'product:write': '商品管理',
  'inventory:adjust': '库存调整',
  'order:read': '订单查看',
  'trade:reconcile': '支付退款对账',
  'audit:read': '审计查询',
  'refund:review': '退款审核（预留）',
}

function open(next: Editor) {
  if (saving.value) return
  username.value = ''
  password.value = ''
  roleIds.value = next.type === 'roles' ? next.account.roles.map(role => role.id) : []
  failure.value = ''
  confirmation.value = ''
  editor.value = next
}

function close() {
  if (saving.value) return
  editor.value = undefined
  password.value = ''
  failure.value = ''
}

async function submit() {
  const next = editor.value
  if (!next || saving.value) return
  failure.value = ''
  if (next.type === 'create' && !/^[A-Za-z0-9_]{4,50}$/.test(username.value.trim())) {
    failure.value = '用户名须为 4 至 50 位字母、数字或下划线。'
    return
  }
  if (next.type === 'create' && (!/^[ -~]{12,64}$/.test(password.value)
    || !/[A-Za-z]/.test(password.value) || !/[0-9]/.test(password.value))) {
    failure.value = '口令须为 12 至 64 位可打印字符，并包含字母和数字。'
    return
  }

  saving.value = true
  try {
    if (next.type === 'create') {
      await request<Account>(directory + '/accounts', {
        method: 'POST', body: { username: username.value.trim(), password: password.value },
      })
      confirmation.value = '账号已创建，可在账号列表为其分配角色。'
    } else if (next.type === 'roles') {
      await request<void>(directory + '/accounts/' + next.account.id + '/roles', {
        method: 'PUT', body: { roleIds: [...roleIds.value] },
      })
      confirmation.value = '角色已更新。'
    } else {
      const status = next.account.status === 1 ? 0 : 1
      await request<void>(directory + '/accounts/' + next.account.id + '/status', {
        method: 'PUT', body: { status },
      })
      confirmation.value = status === 0 ? '账号已停用。' : '账号已启用。'
    }
    editor.value = undefined
    password.value = ''
    await refresh()
  } catch (cause) {
    failure.value = errorText(cause)
  } finally {
    saving.value = false
  }
}

void refresh()
</script>

<template>
  <section class="page-head">
    <div>
      <h1>账号与角色</h1>
      <p>维护后台账号、查看角色权限与管理账号状态。</p>
    </div>
    <el-button type="primary" :disabled="loading || !!error || !data" @click="open({ type: 'create' })">
      新建账号
    </el-button>
  </section>

  <p v-if="confirmation" class="account-confirmation" role="status">{{ confirmation }}</p>
  <section class="panel" aria-label="账号列表">
    <div class="toolbar account-table-heading">
      <div>
        <h2>账号列表</h2>
        <span v-if="data">{{ data.accounts.length }} 个账号</span>
      </div>
      <el-button :loading="loading" :disabled="saving" @click="refresh">刷新</el-button>
    </div>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <el-table v-else v-loading="loading" :data="data?.accounts ?? []" empty-text="暂无后台账号" row-key="id">
      <el-table-column label="账号" min-width="175">
        <template #default="{ row }">
          <strong>{{ row.username }}</strong>
          <div class="secondary">#{{ row.id }} <span v-if="row.id === data?.actorId">· 当前账号</span></div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="105">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="245">
        <template #default="{ row }">
          <div class="account-tags">
            <el-tag v-for="role in row.roles" :key="role.id" effect="plain" type="info">{{ role.name }}</el-tag>
            <span v-if="row.roles.length === 0" class="secondary">未分配角色</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="165">
        <template #default="{ row }">
          <div v-if="row.id !== data?.actorId" class="row-actions">
            <el-button link type="primary" :disabled="loading || saving" @click="open({ type: 'roles', account: row as Account })">
              分配角色
            </el-button>
            <el-button link :type="row.status === 1 ? 'danger' : 'primary'" :disabled="loading || saving"
              @click="open({ type: 'status', account: row as Account })">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
          </div>
          <span v-else class="secondary">由其他管理员维护</span>
        </template>
      </el-table-column>
    </el-table>
  </section>

  <section v-if="data && !error" class="panel role-panel" aria-labelledby="role-directory-title">
    <div class="toolbar account-table-heading"><h2 id="role-directory-title">角色权限</h2></div>
    <div class="role-directory">
      <article v-for="role in data.roles" :key="role.id" class="role-card">
        <h3>{{ role.name }}</h3>
        <p class="secondary">{{ role.code }}</p>
        <div class="account-tags">
          <el-tag v-for="permission in role.permissions" :key="permission" effect="plain" type="info">
            {{ permissions[permission] ?? permission }}
          </el-tag>
        </div>
      </article>
    </div>
  </section>

  <el-dialog :model-value="!!editor" :title="title" width="min(490px, calc(100vw - 32px))"
    :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving"
    @update:model-value="value => { if (!value) close() }">
    <form v-if="editor" class="account-form" @submit.prevent="submit">
      <template v-if="editor.type === 'create'">
        <label for="account-username">用户名</label>
        <el-input id="account-username" v-model="username" autocomplete="off" maxlength="50" :disabled="saving" />
        <label for="account-password">初始口令</label>
        <el-input id="account-password" v-model="password" type="password" autocomplete="new-password"
          maxlength="64" :disabled="saving" />
        <p class="secondary">12 至 64 位可打印字符，包含字母和数字。新账号创建后可分配角色。</p>
      </template>
      <template v-else-if="editor.type === 'roles'">
        <p>为 <strong>{{ editor.account.username }}</strong> 设置角色。清空选择会撤销该账号的全部角色。</p>
        <el-checkbox-group v-model="roleIds" class="account-roles" aria-label="可分配角色" :disabled="saving">
          <el-checkbox v-for="role in data?.roles" :key="role.id" :value="role.id">{{ role.name }}</el-checkbox>
        </el-checkbox-group>
      </template>
      <p v-else>确认{{ editor.account.status === 1 ? '停用' : '启用' }} <strong>{{ editor.account.username }}</strong>？
        状态变更后该账号的旧会话将失效。</p>
      <p v-if="failure" class="error-text" role="alert">{{ failure }}</p>
      <div class="account-dialog-actions">
        <el-button native-type="button" :disabled="saving" @click="close">取消</el-button>
        <el-button :type="editor.type === 'status' && editor.account.status === 1 ? 'danger' : 'primary'"
          native-type="submit" :loading="saving">
          {{ editor.type === 'roles' ? '保存角色' : title }}
        </el-button>
      </div>
    </form>
  </el-dialog>
</template>

<style scoped>
.account-table-heading h2, .role-card h3 { margin: 0; }
.account-table-heading > div > span, .secondary { color: #526176; font-size: .8rem; }
.account-confirmation { padding: .65rem .85rem; color: #17623b; background: #ecf8ef; border-radius: .45rem; }
.account-tags { display: flex; flex-wrap: wrap; gap: .35rem; }
.role-panel { margin-top: 1rem; }
.role-directory { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 205px), 1fr)); gap: .75rem; padding: 0 1rem 1rem; }
.role-card { min-width: 0; border: 1px solid #dfe7f1; border-radius: .5rem; padding: .75rem; }
.role-card p { margin: .2rem 0 .65rem; overflow-wrap: anywhere; }
.account-form { display: grid; gap: .6rem; }
.account-form p { margin: 0; }
.account-roles { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: .3rem; }
.account-dialog-actions { display: flex; justify-content: flex-end; padding-top: .75rem; }
</style>
