import { shallowRef } from 'vue'
import { request } from './api'

const storageKey = 'xiaoke.admin.session'
interface Access {
  id: number
  username: string
  roles: { id: number; name: string }[]
  permissions: string[]
}

const token = shallowRef(sessionStorage.getItem(storageKey))
const access = shallowRef<Access | null>(null)
let inFlight: { token: string; result: Promise<Access> } | undefined

function start(value: string) {
  access.value = null
  sessionStorage.setItem(storageKey, value)
  token.value = value
}

function clear() {
  access.value = null
  token.value = null
  sessionStorage.removeItem(storageKey)
}

async function refreshAccess(): Promise<Access> {
  const current = token.value
  if (inFlight?.token === current) return inFlight.result
  const result = request<Access>('/admin/access/me').then(identity => {
    if (token.value === current) access.value = identity
    return identity
  })
  if (current) inFlight = { token: current, result }
  try {
    return await result
  } finally {
    if (inFlight?.result === result) inFlight = undefined
  }
}

export function useSession() {
  return { token, access, start, clear, refreshAccess }
}
