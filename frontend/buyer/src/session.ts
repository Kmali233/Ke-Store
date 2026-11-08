import { defineStore } from 'pinia'
import { shallowRef } from 'vue'
import type { Login } from './contracts'

const storageKey = 'xiaoke.buyer.session'

function restore(): Login | null {
  try {
    return JSON.parse(sessionStorage.getItem(storageKey) ?? 'null') as Login | null
  } catch {
    return null
  }
}

export const useSession = defineStore('buyer', () => {
  const identity = shallowRef(restore())

  function signIn(login: Login) {
    sessionStorage.setItem(storageKey, JSON.stringify(login))
    identity.value = login
  }

  function signOut() {
    identity.value = null
    sessionStorage.removeItem(storageKey)
  }

  return { identity, signIn, signOut }
})
