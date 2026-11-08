import type { CheckoutInput } from './contracts'

export interface PendingCheckout {
  key: string
  body: CheckoutInput
}

export function checkoutStorageKey(userId: number) {
  return 'xiaoke.buyer.checkout.' + userId
}

export function readPending(userId: number): PendingCheckout | null {
  const record = localStorage.getItem(checkoutStorageKey(userId))
  return record ? JSON.parse(record) as PendingCheckout : null
}

export async function reserveCheckout(userId: number, body: CheckoutInput) {
  const storageKey = checkoutStorageKey(userId)
  const reserve = () => {
    const existing = readPending(userId)
    if (existing) return { attempt: existing, created: false }

    const key = Array.from(crypto.getRandomValues(new Uint8Array(16)), byte =>
      byte.toString(16).padStart(2, '0'),
    ).join('')
    const attempt = { key, body }
    localStorage.setItem(storageKey, JSON.stringify(attempt))
    return { attempt, created: true }
  }
  return navigator.locks ? navigator.locks.request(storageKey, reserve) : reserve()
}

export function clearPending(userId: number, key: string) {
  if (readPending(userId)?.key === key) localStorage.removeItem(checkoutStorageKey(userId))
}
