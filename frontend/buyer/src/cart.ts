import { request } from './api'
import type { CartItem } from './contracts'

export const availabilityLabel = {
  AVAILABLE: '可结算',
  OFF_SHELF: '已下架',
  OUT_OF_STOCK: '库存不足',
  INVALID_SKU: '规格已失效',
}

export function loadCart(signal?: AbortSignal) {
  return request<CartItem[]>('/cart', { auth: true, signal })
}

export function cartTotal(lines: CartItem[]) {
  return lines.reduce((cents, line) => cents + Math.round((line.price ?? 0) * 100) * line.quantity, 0) / 100
}
