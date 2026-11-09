import { useSession } from './session'

interface Result<T> {
  code: number
  message: string
  data: T
}

interface Options {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
}

export class ApiError extends Error {
  constructor(readonly code: number, message: string, readonly traceId: string | null = null,
              readonly businessResponse = false) {
    super(message)
  }
}

export async function request<T>(path: string, options: Options = {}): Promise<T> {
  const session = useSession()
  const login = path === '/admin/auth/login'
  const token = login ? null : session.token.value
  if (!login && !token) throw new ApiError(401, '请先登录管理端。')
  const headers = new Headers({ Accept: 'application/json' })
  if (token) headers.set('Authorization', token)
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  const timeout = AbortSignal.timeout(12_000)
  const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout
  let response: Response
  let envelope: Result<T> | undefined
  try {
    response = await fetch('/api' + path, {
      method: options.method, headers, signal, cache: 'no-store',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
    if (response.headers.get('content-type')?.includes('application/json')) {
      envelope = await response.json() as Result<T>
    }
  } catch (failure) {
    if (options.signal?.aborted) throw failure
    throw new ApiError(0, timeout.aborted ? '请求超时，请确认服务状态。' : '网络连接中断，请检查服务后重试。')
  }
  if (response.ok && envelope?.code === 200) return envelope.data
  const code = response.ok ? envelope?.code ?? 502 : response.status
  if (token && session.token.value === token) {
    if (code === 401) session.clear()
    if (code === 403 && path !== '/admin/access/me') void session.refreshAccess().catch(() => {})
  }
  const fallback = code >= 500 ? '商城服务暂不可用，请稍后重试。' : '请求失败，请检查输入或权限。'
  const message = code === 401 && token ? '会话已失效，请重新登录。' : code < 500 ? envelope?.message || fallback : fallback
  throw new ApiError(code, message, response.headers.get('X-Trace-Id'), envelope?.code === code)
}

export function errorText(error: unknown) {
  return error instanceof ApiError
    ? error.message + (error.traceId ? ' 请求编号 ' + error.traceId : '')
    : '操作未完成，请稍后重试。'
}
