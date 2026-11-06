import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient } from './client'

type Handler = (req: { path: string; headers: Headers; init: RequestInit }) => Response | Promise<Response>

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** fetch falso: registra cada llamada y delega en un manejador por ruta. */
function fakeFetch(routes: Record<string, Handler>) {
  const calls: { path: string; auth: string | null }[] = []
  const impl = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input).replace('/api/v1', '')
    const headers = new Headers(init.headers)
    calls.push({ path, auth: headers.get('Authorization') })
    const handler = routes[path]
    if (!handler) return json({ detail: 'sin ruta' }, 404)
    return handler({ path, headers, init })
  })
  return { impl: impl as unknown as typeof fetch, calls, count: (p: string) => calls.filter((c) => c.path === p).length }
}

describe('cliente HTTP: sesión y refresh', () => {
  it('login guarda el access token solo en memoria y lo envía como Bearer', async () => {
    const store = { setItem: vi.fn(), getItem: vi.fn(() => null), removeItem: vi.fn() }
    vi.stubGlobal('localStorage', store)
    vi.stubGlobal('sessionStorage', store)
    const f = fakeFetch({
      '/auth/login': () => json({ accessToken: 'A1', tokenType: 'Bearer', expiresIn: 600 }),
      '/users/me': ({ headers }) => json({ auth: headers.get('Authorization') }),
    })
    const api = createApiClient({ fetchImpl: f.impl })
    await api.login('EMP001', 'x')
    expect(api.isAuthenticated()).toBe(true)
    expect(await api.getJson('/users/me')).toEqual({ auth: 'Bearer A1' })
    expect(store.setItem).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('ante un 401 renueva con /auth/refresh y reintenta la petición una sola vez con el token nuevo', async () => {
    let first = true
    const f = fakeFetch({
      '/auth/login': () => json({ accessToken: 'OLD' }),
      '/auth/refresh': () => json({ accessToken: 'NEW' }),
      '/dashboard': ({ headers }) => {
        if (first) {
          first = false
          return json({ title: 'Unauthorized' }, 401)
        }
        return json({ auth: headers.get('Authorization') })
      },
    })
    const lost = vi.fn()
    const api = createApiClient({ fetchImpl: f.impl, onSessionLost: lost })
    await api.login('u', 'p')
    expect(await api.getJson('/dashboard')).toEqual({ auth: 'Bearer NEW' })
    expect(f.count('/auth/refresh')).toBe(1)
    expect(f.count('/dashboard')).toBe(2)
    expect(f.calls.filter((c) => c.path === '/dashboard').map((c) => c.auth)).toEqual(['Bearer OLD', 'Bearer NEW'])
    expect(lost).not.toHaveBeenCalled()
  })

  it('las peticiones concurrentes con 401 comparten UNA sola renovación (el refresh rota en cada uso)', async () => {
    let tokenValid = false
    const f = fakeFetch({
      '/auth/login': () => json({ accessToken: 'OLD' }),
      '/auth/refresh': async () => {
        await new Promise((r) => setTimeout(r, 20))
        tokenValid = true
        return json({ accessToken: 'NEW' })
      },
      '/a': ({ headers }) => (tokenValid && headers.get('Authorization') === 'Bearer NEW' ? json({ ok: 'a' }) : json({}, 401)),
      '/b': ({ headers }) => (tokenValid && headers.get('Authorization') === 'Bearer NEW' ? json({ ok: 'b' }) : json({}, 401)),
      '/c': ({ headers }) => (tokenValid && headers.get('Authorization') === 'Bearer NEW' ? json({ ok: 'c' }) : json({}, 401)),
    })
    const api = createApiClient({ fetchImpl: f.impl })
    await api.login('u', 'p')
    const results = await Promise.all([api.getJson('/a'), api.getJson('/b'), api.getJson('/c')])
    expect(results).toEqual([{ ok: 'a' }, { ok: 'b' }, { ok: 'c' }])
    expect(f.count('/auth/refresh')).toBe(1)
  })

  it('si el refresh es rechazado, avisa de sesión perdida, lanza 401 y no reintenta', async () => {
    const f = fakeFetch({
      '/auth/login': () => json({ accessToken: 'OLD' }),
      '/auth/refresh': () => json({ detail: 'x' }, 401),
      '/dashboard': () => json({ title: 'Unauthorized' }, 401),
    })
    const lost = vi.fn()
    const api = createApiClient({ fetchImpl: f.impl, onSessionLost: lost })
    await api.login('u', 'p')
    await expect(api.getJson('/dashboard')).rejects.toMatchObject({ status: 401 })
    expect(lost).toHaveBeenCalledTimes(1)
    expect(f.count('/dashboard')).toBe(1)
    expect(api.isAuthenticated()).toBe(false)
  })

  it('si el reintento sigue dando 401 no vuelve a renovar (sin bucles)', async () => {
    const f = fakeFetch({
      '/auth/login': () => json({ accessToken: 'OLD' }),
      '/auth/refresh': () => json({ accessToken: 'NEW' }),
      '/dashboard': () => json({ title: 'Unauthorized' }, 401),
    })
    const lost = vi.fn()
    const api = createApiClient({ fetchImpl: f.impl, onSessionLost: lost })
    await api.login('u', 'p')
    await expect(api.getJson('/dashboard')).rejects.toBeInstanceOf(ApiError)
    expect(f.count('/auth/refresh')).toBe(1)
    expect(f.count('/dashboard')).toBe(2)
    expect(lost).toHaveBeenCalledTimes(1)
  })

  it('un 401 del propio login (credenciales erróneas) no dispara refresh', async () => {
    const f = fakeFetch({ '/auth/login': () => json({ detail: 'bad' }, 401), '/auth/refresh': () => json({ accessToken: 'X' }) })
    const api = createApiClient({ fetchImpl: f.impl })
    await expect(api.login('u', 'mala')).rejects.toMatchObject({ status: 401 })
    expect(f.count('/auth/refresh')).toBe(0)
    expect(api.isAuthenticated()).toBe(false)
  })

  it('restore() recupera la sesión por cookie y es de un solo vuelo aunque se llame dos veces', async () => {
    const f = fakeFetch({ '/auth/refresh': async () => { await new Promise((r) => setTimeout(r, 10)); return json({ accessToken: 'R' }) } })
    const api = createApiClient({ fetchImpl: f.impl })
    const [a, b] = await Promise.all([api.restore(), api.restore()])
    expect([a, b]).toEqual([true, true])
    expect(f.count('/auth/refresh')).toBe(1)
    expect(api.isAuthenticated()).toBe(true)
  })

  it('restore() devuelve false si no hay cookie válida y también ante un fallo de red', async () => {
    const rejected = createApiClient({ fetchImpl: fakeFetch({ '/auth/refresh': () => json({}, 401) }).impl })
    expect(await rejected.restore()).toBe(false)
    const offline = createApiClient({ fetchImpl: (async () => { throw new TypeError('red') }) as unknown as typeof fetch })
    expect(await offline.restore()).toBe(false)
  })

  it('tras el logout se olvida el token y ya no se envía Authorization', async () => {
    const f = fakeFetch({
      '/auth/login': () => json({ accessToken: 'A' }),
      '/auth/logout': () => new Response(null, { status: 204 }),
      '/ping': () => json({}),
    })
    const api = createApiClient({ fetchImpl: f.impl })
    await api.login('u', 'p')
    await api.logout()
    expect(api.isAuthenticated()).toBe(false)
    await api.getJson('/ping')
    expect(f.calls.at(-1)?.auth).toBeNull()
  })

  it('los errores del API se convierten en ApiError con el detalle del problem+json', async () => {
    const f = fakeFetch({ '/documents/x': () => json({ title: 'Not Found', detail: 'The requested resource was not found.', status: 404 }, 404) })
    const api = createApiClient({ fetchImpl: f.impl })
    const err = (await api.getJson('/documents/x').catch((e: unknown) => e)) as ApiError
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(404)
    expect(err.message).toBe('The requested resource was not found.')
  })

  it('getBlob devuelve el nombre de archivo de Content-Disposition', async () => {
    const f = fakeFetch({ '/d': () => new Response('abc', { status: 200, headers: { 'Content-Disposition': 'attachment; filename="informe.pdf"' } }) })
    const api = createApiClient({ fetchImpl: f.impl })
    const { blob, filename } = await api.getBlob('/d')
    expect(filename).toBe('informe.pdf')
    expect(blob.size).toBe(3)
  })
})
