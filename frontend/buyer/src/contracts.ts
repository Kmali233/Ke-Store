export interface Page<T> {
  records: T[]
  current: number
  pages: number
  total: number
}

export interface Category {
  id: number
  name: string
  parentId: number
}

export interface Product {
  id: number
  name: string
  image: string | null
  description: string | null
  price: number
  stock: number
}

export interface Sku {
  id: number
  name: string
  image: string | null
  specs: Record<string, string>
  price: number
  stock: number
  status: number
  defaultSku: boolean
}

export interface ProductDetail extends Product {
  skus: Sku[]
  attrs: { attrName: string; attrValue: string; attrGroup: string | null }[]
}

export interface UserSummary {
  id: number
  username: string
  nickname: string | null
}

export interface Login {
  token: string
  userInfo: UserSummary
}

export type Availability = 'AVAILABLE' | 'OFF_SHELF' | 'OUT_OF_STOCK' | 'INVALID_SKU'

export interface CartItem {
  skuId: number
  quantity: number
  selected: boolean
  productId: number | null
  productName: string | null
  skuName: string | null
  skuSpec: string | null
  image: string | null
  price: number | null
  stock: number | null
  availability: Availability
}

export interface CheckoutInput {
  addressId?: number // Legacy saved checkout payloads
  remark: string
  items: { skuId: number; quantity: number }[]
}

export interface OrderCreated {
  orderNo: string
  status: number
  totalAmount: number
}

export interface OrderSummary extends OrderCreated {
  statusDesc: string
  createTime: string
}

export interface OrderRefund {
  canRequest: boolean
  refundNo: string | null
  amount: number | null
  status: 'REQUESTED' | 'PROCESSING' | 'REJECTED' | 'SUCCEEDED' | 'FAILED' | null
  updatedAt: string | null
}

export interface OrderDetail extends OrderSummary {
  remark: string | null
  items: {
    productId: number
    skuId: number | null
    productName: string
    skuName: string | null
    skuSpec: string | null
    productImage: string | null
    price: number
    quantity: number
    amount: number
  }[]
}

export interface PaymentStatus {
  sandboxEnabled: boolean
  demoEnabled: boolean
  canStart: boolean
  canSimulate: boolean
  state: 'NONE' | 'PENDING' | 'SUCCEEDED' | 'CLOSED'
  closeProcessing: boolean
  paymentExpired: boolean
  channel: 'ALIPAY_SANDBOX' | 'DEMO' | null
}
