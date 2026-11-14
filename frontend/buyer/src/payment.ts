import type { Router } from 'vue-router'
import { request } from './api'

export async function openCheckout(router: Router, paymentPath: string, demo: boolean, signal: AbortSignal) {
  const { paymentUrl } = await request<{ paymentUrl: string }>(paymentPath + (demo ? '/demo' : '/alipay'), {
    method: 'POST', auth: true, signal,
  })
  if (signal.aborted) return
  if (demo) {
    await router.push(paymentUrl)
  } else {
    window.location.assign(paymentUrl)
  }
}
