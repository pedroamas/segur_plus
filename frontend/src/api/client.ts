/**
 * Cliente HTTP del API. El access JWT vive SOLO en una variable del cierre (memoria): nunca en localStorage ni
 * sessionStorage. El refresh es una cookie HttpOnly que el navegador envía sola a /api/v1/auth/*.
 *
 * Ante un 401 se renueva con POST /auth/refresh y se reintenta la petición UNA sola vez. Todas las peticiones que
 * fallan a la vez comparten la misma renovación (una sola llamada): el refresh rota en cada uso y reutilizar uno rotado
 * revocaría la familia (ADR 0007).
 */

export interface Problem {
  type?: string
  title?: string
  status?: number
  detail?: string
  correlationId?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly problem?: Problem
  constructor(status: number, message: string, problem?: Problem) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

interface TokenResponse {
  accessToken: string
}

export interface ClientOptions {
  baseUrl?: string
  fetchImpl?: typeof fetch
  /** Se llama cuando la sesión no se puede renovar (refresh rechazado). */
  onSessionLost?: () => void
}

const AUTH_PATHS = ['/auth/login', '/auth/refresh', '/auth/logout']

export function createApiClient(options: ClientOptions = {}) {
  const baseUrl = options.baseUrl ?? '/api/v1'
  const doFetch: typeof fetch = (...args) => (options.fetchImpl ?? fetch)(...args)
  let accessToken: string | null = null
  let refreshing: Promise<boolean> | null = null

  async function toError(res: Response): Promise<ApiError> {
    let problem: Problem | undefined
    try {
      problem = (await res.json()) as Problem
    } catch {
      problem = undefined
    }
    return new ApiError(res.status, problem?.detail ?? problem?.title ?? `Error ${res.status}`, problem)
  }

  /** Una única renovación en vuelo; los demás llamantes esperan su resultado. */
  function refresh(): Promise<boolean> {
    if (!refreshing) {
      refreshing = (async () => {
        try {
          const res = await doFetch(`${baseUrl}/auth/refresh`, { method: 'POST', credentials: 'same-origin' })
          if (!res.ok) {
            accessToken = null
            return false
          }
          accessToken = ((await res.json()) as TokenResponse).accessToken
          return true
        } catch {
          accessToken = null
          return false
        }
      })().finally(() => {
        refreshing = null
      })
    }
    return refreshing
  }

  async function send(path: string, init: RequestInit): Promise<Response> {
    const headers = new Headers(init.headers)
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    return doFetch(`${baseUrl}${path}`, { ...init, headers, credentials: 'same-origin' })
  }

  async function request(path: string, init: RequestInit = {}): Promise<Response> {
    let res = await send(path, init)
    if (res.status === 401 && !AUTH_PATHS.includes(path)) {
      if (await refresh()) {
        res = await send(path, init) // reintento único
      } else {
        options.onSessionLost?.()
        throw await toError(res)
      }
      if (res.status === 401) {
        accessToken = null
        options.onSessionLost?.()
      }
    }
    if (!res.ok) throw await toError(res)
    return res
  }

  return {
    async login(username: string, password: string): Promise<void> {
      const res = await request('/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      })
      accessToken = ((await res.json()) as TokenResponse).accessToken
    },
    /** Intenta restaurar la sesión con la cookie de refresh (p. ej. tras recargar la página). */
    restore: refresh,
    async logout(): Promise<void> {
      try {
        await request('/auth/logout', { method: 'POST' })
      } finally {
        accessToken = null
      }
    },
    isAuthenticated: () => accessToken !== null,
    async getJson<T>(path: string): Promise<T> {
      return (await request(path, { headers: { Accept: 'application/json' } })).json() as Promise<T>
    },
    /** Envía un multipart/form-data. No se fija Content-Type: el navegador añade el boundary. */
    async postForm<T>(path: string, form: FormData): Promise<T> {
      return (await request(path, { method: 'POST', headers: { Accept: 'application/json' }, body: form })).json() as Promise<T>
    },
    async getBlob(path: string): Promise<{ blob: Blob; filename?: string }> {
      const res = await request(path)
      const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(res.headers.get('Content-Disposition') ?? '')
      return { blob: await res.blob(), filename: m ? decodeURIComponent(m[1]) : undefined }
    },
  }
}

export type ApiClient = ReturnType<typeof createApiClient>

let lostHandler: (() => void) | undefined
/** La UI registra aquí qué hacer cuando la sesión no se puede renovar (volver al login). */
export function setSessionLostHandler(handler: (() => void) | undefined): void {
  lostHandler = handler
}
export const api = createApiClient({ onSessionLost: () => lostHandler?.() })
