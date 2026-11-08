import { useSession } from './session'

interface Result<T> {
  code: number
  message: string
  data: T
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  auth?: boolean
  key?: string
  signal?: AbortSignal
}

export class ApiError extends Error {
  constructor(readonly code: number, message: string, readonly businessResponse = false) {
    super(message)
  }
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const session = useSession()
  const token = options.auth ? session.identity?.token : undefined
  if (options.auth && !token) throw new ApiError(401, '请先登录。')

  const headers = new Headers({ Accept: 'application/json' })
  if (token) headers.set('Authorization', token)
  if (options.key) headers.set('Idempotency-Key', options.key)
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  const timeout = AbortSignal.timeout(12_000)
  const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout
  let response: Response
  let result: Result<T> | null = null
  try {
    response = await fetch(`/api${path}`, {
      method: options.method, headers, signal, cache: 'no-store',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
    if (response.headers.get('content-type')?.includes('application/json')) {
      result = await response.json() as Result<T>
    }
  } catch (error) {
    if (options.signal?.aborted) throw error
    throw new ApiError(0, timeout.aborted ? '请求超时，请检查服务后重试。' : '连接中断，请检查网络后重试。')
  }

  if (response.ok && result?.code === 200) return result.data
  const code = response.ok ? result?.code ?? 502 : response.status
  if (code === 401 && token && session.identity?.token === token) session.signOut()
  const message = code >= 500
    ? '商城服务暂不可用，请稍后重试。'
    : result?.message || '请求失败，请检查输入后重试。'
  throw new ApiError(code, code === 401 && token ? '登录已失效，请重新登录。' : message, result?.code === code)
}

export function errorText(error: unknown) {
  return error instanceof ApiError ? error.message : '操作未能完成，请重试。'
}
