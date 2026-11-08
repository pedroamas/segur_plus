/** Cliente del chat (F5): solo habla con /api/v1/chat. No existe ninguna clave de Dify/Gemini en el frontend. */
import { ApiError, api as defaultApi, type ApiClient } from './client'
import { SseParser } from './sse'
import type { ConversationSummary, MessageView } from './types'

export interface ChatSource {
  documentId: string
  code: string
  title: string
  cited: boolean
}

export interface ChatVerification {
  ok: boolean
  citationsValid: boolean
  citedCodes: string[]
  unknownCitations: string[]
  numbersChecked: number
  unsupportedNumbers: string[]
}

export interface ChatDone {
  messageId: string
  tokensIn: number
  tokensOut: number
  latencyMs: number
}

export type ChatEvent =
  | { type: 'conversation'; conversationId: string; messageId: string }
  | { type: 'status'; phase: string }
  | { type: 'tool'; tool: string }
  | { type: 'delta'; text: string }
  | { type: 'sources'; sources: ChatSource[] }
  | { type: 'verification'; verification: ChatVerification }
  | { type: 'done'; done: ChatDone }

export type ChatErrorKind = 'unavailable' | 'timeout' | 'interrupted' | 'not-found' | 'forbidden' | 'bad-request' | 'unknown'

/** Error de un turno de chat con la clasificación que necesita la UI para elegir el mensaje. */
export class ChatError extends Error {
  readonly kind: ChatErrorKind
  readonly status?: number
  constructor(kind: ChatErrorKind, message: string, status?: number) {
    super(message)
    this.name = 'ChatError'
    this.kind = kind
    this.status = status
  }
}

export const IDLE_TIMEOUT_MS = 90_000

export const CHAT_MESSAGES: Record<ChatErrorKind, string> = {
  unavailable: 'El asistente no está disponible ahora mismo o se ha alcanzado el límite de uso del modelo. Reintenta en unos minutos.',
  timeout: 'El asistente ha tardado demasiado en responder. Puedes reintentarlo.',
  interrupted: 'La respuesta se interrumpió antes de terminar. Puedes reenviar la pregunta.',
  'not-found': 'Conversación no encontrada.',
  forbidden: 'No tienes permiso para usar el asistente.',
  'bad-request': 'No se pudo enviar el mensaje.',
  unknown: 'No se pudo completar la consulta. Inténtalo de nuevo.',
}

/** Traduce el estado/código del servidor (HTTP o evento `error`) a un tipo de error de la UI. */
function classify(status: number | undefined, code?: string): ChatErrorKind {
  if (code === 'ai-timeout' || status === 504 || status === 408) return 'timeout'
  if (code === 'ai-unavailable' || status === 429 || status === 503 || status === 502) return 'unavailable'
  if (status === 404) return 'not-found'
  if (status === 403) return 'forbidden'
  if (status === 400) return 'bad-request'
  return 'unknown'
}

export function chatErrorMessage(e: unknown): string {
  if (e instanceof ChatError) return e.kind === 'bad-request' && e.message ? e.message : CHAT_MESSAGES[e.kind]
  if (e instanceof ApiError) return CHAT_MESSAGES[classify(e.status)]
  return 'No se pudo conectar con el servidor.'
}

function toEvent(name: string, raw: string): ChatEvent | { type: 'error'; status?: number; code?: string } | null {
  let d: Record<string, unknown>
  try {
    d = JSON.parse(raw) as Record<string, unknown>
  } catch {
    return null
  }
  switch (name) {
    case 'conversation':
      return { type: 'conversation', conversationId: String(d.conversationId), messageId: String(d.messageId) }
    case 'status':
      return { type: 'status', phase: String(d.phase) }
    case 'tool':
      return { type: 'tool', tool: String(d.tool) }
    case 'delta':
      return { type: 'delta', text: typeof d.text === 'string' ? d.text : '' }
    case 'sources':
      return { type: 'sources', sources: Array.isArray(d.sources) ? (d.sources as ChatSource[]) : [] }
    case 'verification':
      return d && typeof d === 'object' ? { type: 'verification', verification: d as unknown as ChatVerification } : null
    case 'done':
      return { type: 'done', done: d as unknown as ChatDone }
    case 'error':
      return { type: 'error', status: typeof d.status === 'number' ? d.status : undefined, code: typeof d.code === 'string' ? d.code : undefined }
    default:
      return null // evento desconocido: se ignora
  }
}

export interface StreamOptions {
  message: string
  conversationId?: string
  signal?: AbortSignal
  /** Sin ningún dato durante este tiempo se corta con error de timeout. */
  idleTimeoutMs?: number
  client?: Pick<ApiClient, 'postStream'>
}

/**
 * Envía una pregunta y entrega cada evento SSE a `onEvent` según llega. Resuelve al recibir `done`. Lanza ChatError si el
 * servidor manda `error`, si el stream se corta sin `done` o si no llega nada en `idleTimeoutMs`; ApiError si el POST
 * inicial falla (404 conversación ajena, 503 sin motor, 400…); y un AbortError (DOMException) si la UI cancela.
 */
export async function streamChat(opts: StreamOptions, onEvent: (e: ChatEvent) => void): Promise<void> {
  const client = opts.client ?? defaultApi
  const idleMs = opts.idleTimeoutMs ?? IDLE_TIMEOUT_MS
  const ctrl = new AbortController()
  let timedOut = false
  let timer: ReturnType<typeof setTimeout> | undefined
  const arm = () => {
    clearTimeout(timer)
    timer = setTimeout(() => {
      timedOut = true
      ctrl.abort()
    }, idleMs)
  }
  const onAbort = () => ctrl.abort()
  if (opts.signal?.aborted) throw new DOMException('Cancelado', 'AbortError')
  opts.signal?.addEventListener('abort', onAbort, { once: true })
  arm()
  try {
    let res: Response
    try {
      res = await client.postStream('/chat', { conversationId: opts.conversationId, message: opts.message }, ctrl.signal)
    } catch (e) {
      if (timedOut) throw new ChatError('timeout', CHAT_MESSAGES.timeout)
      throw e
    }
    if (!res.body) throw new ChatError('interrupted', CHAT_MESSAGES.interrupted)
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    const parser = new SseParser()
    let finished = false
    try {
      while (!finished) {
        if (ctrl.signal.aborted) throw new DOMException('Cancelado', 'AbortError')
        const { done, value } = await reader.read()
        if (done) break
        arm()
        for (const raw of parser.push(decoder.decode(value, { stream: true }))) {
          const ev = toEvent(raw.event, raw.data)
          if (!ev) continue
          if (ev.type === 'error') throw new ChatError(classify(ev.status, ev.code), CHAT_MESSAGES[classify(ev.status, ev.code)], ev.status)
          onEvent(ev)
          if (ev.type === 'done') {
            finished = true
            break
          }
        }
      }
    } catch (e) {
      if (timedOut) throw new ChatError('timeout', CHAT_MESSAGES.timeout)
      if (e instanceof ChatError || (e instanceof DOMException && e.name === 'AbortError')) throw e
      throw new ChatError('interrupted', CHAT_MESSAGES.interrupted) // red cortada a mitad del stream
    } finally {
      void reader.cancel().catch(() => undefined)
    }
    if (!finished) throw new ChatError('interrupted', CHAT_MESSAGES.interrupted)
  } finally {
    clearTimeout(timer)
    opts.signal?.removeEventListener('abort', onAbort)
  }
}

export const listConversations = (client: Pick<ApiClient, 'getJson'> = defaultApi) =>
  client.getJson<ConversationSummary[]>('/chat/conversations')
export const getMessages = (id: string, client: Pick<ApiClient, 'getJson'> = defaultApi) =>
  client.getJson<MessageView[]>(`/chat/conversations/${encodeURIComponent(id)}/messages`)
export const deleteConversation = (id: string, client: Pick<ApiClient, 'delete'> = defaultApi) =>
  client.delete(`/chat/conversations/${encodeURIComponent(id)}`)
