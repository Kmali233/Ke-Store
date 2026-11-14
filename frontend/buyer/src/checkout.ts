import type { CheckoutInput } from './contracts'

type CheckoutSource = 'cart' | 'direct'

export interface PendingCheckout {
  key: string
  body: CheckoutInput
}

export function checkoutStorageKey(userId: number, source: CheckoutSource = 'cart') {
  return (source === 'cart' ? 'xiaoke.buyer.checkout.' : 'xiaoke.buyer.direct-order.') + userId
}

export function readPending(userId: number, source?: CheckoutSource): PendingCheckout | null {
  const record = localStorage.getItem(checkoutStorageKey(userId, source))
  return record ? JSON.parse(record) as PendingCheckout : null
}

export async function reserveCheckout(userId: number, body: CheckoutInput, source?: CheckoutSource) {
  const storageKey = checkoutStorageKey(userId, source)
  const reserve = () => {
    const existing = readPending(userId, source)
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

export function clearPending(userId: number, key: string, source?: CheckoutSource) {
  if (readPending(userId, source)?.key === key) localStorage.removeItem(checkoutStorageKey(userId, source))
}
