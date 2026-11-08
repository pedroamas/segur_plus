import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient } from './client'
import { ChatError, chatErrorMessage, streamChat, type ChatEvent } from './chat'

const enc = new TextEncoder()
const ev = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`

/** Respuesta SSE a partir de trozos; `then` decide cómo termina (cierre limpio, error de red o colgado). */
function sse(chunks: string[], end: 'close' | 'error' | 'hang' = 'close', signal?: AbortSignal): Response {
  let i = 0
  const body = new ReadableStream<Uint8Array>({
    async pull(c) {
      if (i < chunks.length) return void c.enqueue(enc.encode(chunks[i++]))
      if (end === 'close') return void c.close()
      if (end === 'error') return void c.error(new TypeError('network error'))
      await new Promise<void>((_, rej) => signal?.addEventListener('abort', () => rej(new DOMException('abortado', 'AbortError'))))
    },
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

const clientOf = (fn: (path: string, body: unknown, signal?: AbortSignal) => Promise<Response>) => ({ postStream: fn })
const FULL = [
  ev('conversation', { conversationId: 'c1', messageId: 'm1' }),
  ev('status', { phase: 'generating' }),
  ev('delta', { text: 'Ventas de ' }),
  ev('delta', { text: 'octubre [D06]' }),
  ev('sources', { sources: [{ documentId: 'd6', code: 'D06', title: 'Ventas', cited: true }] }),
  ev('verification', { ok: true, citationsValid: true, citedCodes: ['D06'], unknownCitations: [], numbersChecked: 1, unsupportedNumbers: [] }),
  ev('done', { messageId: 'm1', tokensIn: 10, tokensOut: 5, latencyMs: 1200 }),
]

async function collect(opts: Parameters<typeof streamChat>[0]) {
  const events: ChatEvent[] = []
  await streamChat(opts, (e) => events.push(e))
  return events
}

afterEach(() => vi.useRealTimers())

describe('streamChat', () => {
  it('entrega los eventos en orden y termina en done', async () => {
    const events = await collect({ message: 'hola', client: clientOf(async () => sse(FULL)) })
    expect(events.map((e) => e.type)).toEqual(['conversation', 'status', 'delta', 'delta', 'sources', 'verification', 'done'])
    expect(events.filter((e) => e.type === 'delta').map((e) => (e as { text: string }).text).join('')).toBe('Ventas de octubre [D06]')
  })

  it('reconstruye eventos partidos entre chunks (a mitad de palabra y de JSON)', async () => {
    const all = FULL.join('')
    const cuts = [7, 31, 60, 61, 130, 200, all.length - 3]
    const chunks: string[] = []
    let prev = 0
    for (const c of cuts) {
      chunks.push(all.slice(prev, c))
      prev = c
    }
    chunks.push(all.slice(prev))
    const events = await collect({ message: 'x', client: clientOf(async () => sse(chunks)) })
    expect(events.map((e) => e.type)).toEqual(['conversation', 'status', 'delta', 'delta', 'sources', 'verification', 'done'])
  })

  it('decodifica bien UTF-8 multibyte partido entre chunks', async () => {
    const bytes = enc.encode(ev('delta', { text: 'niño €' }) + ev('done', { messageId: 'm', tokensIn: 0, tokensOut: 0, latencyMs: 0 }))
    const cut = bytes.indexOf(0xc3) + 1 // entre los dos bytes de «ñ»
    const body = new ReadableStream<Uint8Array>({
      start(c) {
        c.enqueue(bytes.slice(0, cut))
        c.enqueue(bytes.slice(cut))
        c.close()
      },
    })
    const events = await collect({ message: 'x', client: clientOf(async () => new Response(body)) })
    expect((events[0] as { text: string }).text).toBe('niño €')
  })

  it('envía solo conversationId y message (nunca identidad)', async () => {
    const post = vi.fn(async () => sse(FULL))
    await collect({ message: 'hola', conversationId: 'c9', client: clientOf(post) })
    expect(post).toHaveBeenCalledWith('/chat', { conversationId: 'c9', message: 'hola' }, expect.any(AbortSignal))
  })

  it('evento error 503 ⇒ ChatError «unavailable» con el mensaje del límite de uso', async () => {
    const chunks = [ev('conversation', { conversationId: 'c', messageId: 'm' }), ev('error', { status: 503, code: 'ai-unavailable', detail: 'x' })]
    const err = await collect({ message: 'x', client: clientOf(async () => sse(chunks)) }).catch((e) => e)
    expect(err).toBeInstanceOf(ChatError)
    expect(err.kind).toBe('unavailable')
    expect(chatErrorMessage(err)).toMatch(/límite de uso del modelo.*Reintenta en unos minutos/)
  })

  it('evento error ai-timeout ⇒ «timeout»', async () => {
    const err = await collect({ message: 'x', client: clientOf(async () => sse([ev('error', { status: 504, code: 'ai-timeout' })])) }).catch((e) => e)
    expect(err.kind).toBe('timeout')
  })

  it('HTTP 429/503 del POST ⇒ ApiError con el mismo mensaje de límite de uso', async () => {
    for (const status of [429, 503]) {
      const err = await collect({ message: 'x', client: clientOf(async () => Promise.reject(new ApiError(status, 'x'))) }).catch((e) => e)
      expect(err).toBeInstanceOf(ApiError)
      expect(chatErrorMessage(err)).toMatch(/límite de uso del modelo/)
    }
  })

  it('stream cerrado sin done ⇒ interrupted', async () => {
    const err = await collect({ message: 'x', client: clientOf(async () => sse([FULL[0], FULL[2]])) }).catch((e) => e)
    expect(err).toBeInstanceOf(ChatError)
    expect(err.kind).toBe('interrupted')
  })

  it('error de red a mitad del stream ⇒ interrupted, conservando los eventos ya entregados', async () => {
    const events: ChatEvent[] = []
    const err = await streamChat({ message: 'x', client: clientOf(async () => sse([FULL[0], FULL[2]], 'error')) }, (e) => events.push(e)).catch((e) => e)
    expect(err.kind).toBe('interrupted')
    expect(events.map((e) => e.type)).toEqual(['conversation', 'delta'])
  })

  it('cancelar con AbortController corta el stream y lanza AbortError', async () => {
    const ctrl = new AbortController()
    const events: ChatEvent[] = []
    const p = streamChat(
      { message: 'x', signal: ctrl.signal, client: clientOf(async (_p, _b, s) => sse([FULL[0], FULL[2]], 'hang', s)) },
      (e) => {
        events.push(e)
        if (e.type === 'delta') ctrl.abort()
      },
    )
    const err = await p.catch((e) => e)
    expect(err).toBeInstanceOf(DOMException)
    expect(err.name).toBe('AbortError')
  })

  it('ya abortado antes de empezar: no hace ninguna petición', async () => {
    const post = vi.fn()
    const ctrl = new AbortController()
    ctrl.abort()
    await expect(streamChat({ message: 'x', signal: ctrl.signal, client: clientOf(post) }, () => undefined)).rejects.toMatchObject({ name: 'AbortError' })
    expect(post).not.toHaveBeenCalled()
  })

  it('sin datos durante el plazo ⇒ timeout (y reinicia el plazo con cada chunk)', async () => {
    vi.useFakeTimers()
    const p = streamChat({ message: 'x', idleTimeoutMs: 1000, client: clientOf(async (_p, _b, s) => sse([FULL[0]], 'hang', s)) }, () => undefined).catch((e) => e)
    await vi.advanceTimersByTimeAsync(1100)
    const err = await p
    expect(err).toBeInstanceOf(ChatError)
    expect(err.kind).toBe('timeout')
    expect(chatErrorMessage(err)).toMatch(/tardado demasiado/)
  })

  it('timeout también si el POST inicial no responde', async () => {
    vi.useFakeTimers()
    const hang = (_p: string, _b: unknown, s?: AbortSignal) => new Promise<Response>((_, rej) => s?.addEventListener('abort', () => rej(new DOMException('x', 'AbortError'))))
    const p = streamChat({ message: 'x', idleTimeoutMs: 500, client: clientOf(hang) }, () => undefined).catch((e) => e)
    await vi.advanceTimersByTimeAsync(600)
    expect((await p).kind).toBe('timeout')
  })

  it('ignora eventos desconocidos y datos que no son JSON', async () => {
    const chunks = ['event: raro\ndata: {}\n\n', 'event: delta\ndata: no-json\n\n', ev('done', { messageId: 'm', tokensIn: 1, tokensOut: 1, latencyMs: 1 })]
    const events = await collect({ message: 'x', client: clientOf(async () => sse(chunks)) })
    expect(events.map((e) => e.type)).toEqual(['done'])
  })
})

describe('streamChat con el cliente HTTP real: 401 + refresh a mitad de la petición', () => {
  it('un 401 en el POST renueva (una vez) y reintenta con el token nuevo antes de abrir el stream', async () => {
    const calls: { path: string; auth: string | null }[] = []
    let first = true
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const path = String(input).replace('/api/v1', '')
      calls.push({ path, auth: new Headers(init.headers).get('Authorization') })
      if (path === '/auth/login') return new Response(JSON.stringify({ accessToken: 'OLD' }), { status: 200 })
      if (path === '/auth/refresh') return new Response(JSON.stringify({ accessToken: 'NEW' }), { status: 200 })
      if (first) {
        first = false
        return new Response(JSON.stringify({ title: 'Unauthorized' }), { status: 401 })
      }
      return sse(FULL)
    }) as unknown as typeof fetch
    const api = createApiClient({ fetchImpl })
    await api.login('u', 'p')
    const events = await collect({ message: 'hola', client: api })
    expect(events.at(-1)?.type).toBe('done')
    expect(calls.filter((c) => c.path === '/auth/refresh')).toHaveLength(1)
    expect(calls.filter((c) => c.path === '/chat').map((c) => c.auth)).toEqual(['Bearer OLD', 'Bearer NEW'])
  })

  it('si el refresh falla, avisa de sesión perdida y lanza 401', async () => {
    const lost = vi.fn()
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input).replace('/api/v1', '')
      return new Response('{}', { status: path === '/auth/refresh' ? 401 : 401 })
    }) as unknown as typeof fetch
    const api = createApiClient({ fetchImpl, onSessionLost: lost })
    const err = await collect({ message: 'x', client: api }).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.status).toBe(401)
    expect(lost).toHaveBeenCalled()
  })
})
