import { onScopeDispose, ref, shallowRef } from 'vue'
import { errorText } from './api'

export function useLoad<T>(load: (signal: AbortSignal) => Promise<T>) {
  const data = shallowRef<T>()
  const loading = ref(false)
  const error = ref('')
  let active: AbortController | undefined
  let disposed = false

  async function refresh() {
    if (disposed) return
    active?.abort()
    const controller = new AbortController()
    active = controller
    loading.value = true
    error.value = ''
    try {
      const result = await load(controller.signal)
      if (!controller.signal.aborted) data.value = result
    } catch (failure) {
      if (!controller.signal.aborted) error.value = errorText(failure)
    } finally {
      if (!controller.signal.aborted) loading.value = false
    }
  }

  onScopeDispose(() => {
    disposed = true
    active?.abort()
  })
  return { data, loading, error, refresh }
}
