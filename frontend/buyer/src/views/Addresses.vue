<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { errorText, request } from '../api'
import LoadState from '../components/LoadState.vue'
import type { Address, AddressInput } from '../contracts'
import { useLoad } from '../useLoad'

const route = useRoute()
const editing = ref<number | null>()
const busy = ref(false)
const actionError = ref('')
const notice = ref('')
const blank = () => ({ receiverName: '', receiverPhone: '', province: '', city: '', district: '', detailAddress: '', isDefault: false })
const form = ref(blank())
const { data, error, loading, refresh } = useLoad(signal => request<Address[]>('/address/list', { auth: true, signal }))
const combined = computed(() =>
  [form.value.province, form.value.city, form.value.district, form.value.detailAddress]
    .map(part => part.trim()).filter(Boolean).join(' '),
)
void refresh()

function edit(address?: Address) {
  actionError.value = ''
  notice.value = ''
  editing.value = address?.id ?? null
  form.value = address ? {
    receiverName: address.receiverName, receiverPhone: address.receiverPhone,
    province: address.province ?? '', city: address.city ?? '', district: address.district ?? '',
    detailAddress: address.detailAddress, isDefault: address.isDefault,
  } : blank()
}

async function change(action: () => Promise<unknown>, message: string) {
  if (busy.value) return
  busy.value = true
  actionError.value = ''
  notice.value = ''
  try {
    await action()
    editing.value = undefined
    notice.value = message
    await refresh()
  } catch (failure) {
    actionError.value = errorText(failure)
  } finally {
    busy.value = false
  }
}

function save() {
  if (editing.value === undefined) return
  const input = form.value
  const body: AddressInput = {
    receiverName: input.receiverName.trim(), receiverPhone: input.receiverPhone.trim(),
    province: input.province.trim() || null, city: input.city.trim() || null, district: input.district.trim() || null,
    detailAddress: input.detailAddress.trim(), isDefault: input.isDefault,
  }
  if (!body.receiverName || !body.receiverPhone || !body.detailAddress || combined.value.length > 255) {
    actionError.value = '请填写收件人、电话和详细地址，合并后的地址最多 255 个字符。'
    return
  }
  const id = editing.value
  const { isDefault, ...fields } = body
  const payload = id === null ? { ...fields, isDefault: isDefault || undefined } : { id, ...body }
  void change(() => request('/address', {
    method: id === null ? 'POST' : 'PUT', auth: true, body: payload,
  }), '地址已保存。')
}

function remove(address: Address) {
  if (window.confirm('删除这条收货地址？')) {
    void change(() => request('/address/' + address.id, { method: 'DELETE', auth: true }), '地址已删除。')
  }
}
</script>

<template>
  <RouterLink v-if="route.query.from === 'checkout'" class="back" to="/checkout">‹ 返回结算</RouterLink>
  <header class="page-title split">
    <div><p class="eyebrow">地址簿</p><h1>收货地址</h1></div>
    <button type="button" :disabled="busy" @click="edit()">新增地址</button>
  </header>
  <p v-if="notice" class="success" role="status">{{ notice }}</p>
  <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
  <form v-if="editing !== undefined" class="panel padded stack spaced-bottom" @submit.prevent="save">
    <h2>{{ editing === null ? '新增地址' : '编辑地址' }}</h2>
    <fieldset class="stack" :disabled="busy">
      <div class="field-row">
        <label class="field">收件人<input v-model="form.receiverName" required maxlength="50" autocomplete="shipping name"></label>
        <label class="field">联系电话<input v-model="form.receiverPhone" required maxlength="20" type="tel" autocomplete="shipping tel"></label>
      </div>
      <div class="field-row">
        <label class="field">省（可选）<input v-model="form.province" maxlength="50" autocomplete="shipping address-level1"></label>
        <label class="field">市（可选）<input v-model="form.city" maxlength="50" autocomplete="shipping address-level2"></label>
        <label class="field">区（可选）<input v-model="form.district" maxlength="50" autocomplete="shipping address-level3"></label>
      </div>
      <label class="field">详细地址<textarea v-model="form.detailAddress" required maxlength="255" autocomplete="shipping street-address" rows="2"></textarea></label>
      <label class="choice"><input v-model="form.isDefault" type="checkbox">设为默认地址</label>
      <div class="actions">
        <button type="submit">{{ busy ? '正在保存…' : '保存地址' }}</button>
        <button class="outline" type="button" @click="editing = undefined; actionError = ''">取消编辑</button>
      </div>
    </fieldset>
  </form>
  <LoadState :loading="loading" :error="error" @retry="refresh">
    <div v-if="data?.length" class="address-grid">
      <article v-for="address in data" :key="address.id" class="panel padded stack">
        <h2>{{ address.receiverName }} <span v-if="address.isDefault" class="badge">默认</span></h2>
        <p>{{ address.receiverPhone }}</p>
        <p class="muted">{{ [address.province, address.city, address.district, address.detailAddress].filter(Boolean).join(' ') }}</p>
        <div class="actions">
          <button class="outline" type="button" :disabled="busy" @click="edit(address)">编辑</button>
          <button v-if="!address.isDefault" class="outline" type="button" :disabled="busy"
            @click="change(() => request('/address/' + address.id + '/default', { method: 'PUT', auth: true }), '已设为默认地址。')">设为默认</button>
          <button class="quiet" type="button" :disabled="busy" @click="remove(address)">删除</button>
        </div>
      </article>
    </div>
    <div v-else class="state"><p>还没有收货地址。</p><button class="outline" type="button" :disabled="busy" @click="edit()">添加第一条地址</button></div>
  </LoadState>
</template>
