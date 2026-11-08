import { useCallback, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  ChatError,
  chatErrorMessage,
  deleteConversation,
  getMessages,
  listConversations,
  streamChat,
  type ChatEvent,
} from '../api/chat'
import type { ConversationSummary, MessageView } from '../api/types'
import { ChatMessage, parseVerification, type UiMessage } from '../components/ChatMessage'
import { ErrorBox, Loading, errorMessage } from '../components/Async'

const MAX_CHARS = 2000 // igual que segurplus.ai.chat.max-message-chars del backend (que es quien lo aplica)

const PHASE_TEXT: Record<string, string> = {
  planning: 'Entendiendo la pregunta…',
  retrieving: 'Buscando en los documentos…',
  generating: 'Escribiendo la respuesta…',
  tool: 'Consultando datos…',
}

function fromServer(m: MessageView): UiMessage {
  return {
    key: m.id,
    role: m.role === 'USER' ? 'USER' : 'ASSISTANT',
    content: m.content,
    status: m.status,
    sources: m.sources.map((s) => ({ documentId: s.documentId, code: s.code, title: s.title, cited: s.cited })),
    verification: parseVerification(m.verification),
    usage: m.tokensIn != null && m.tokensOut != null && m.latencyMs != null ? { tokensIn: m.tokensIn, tokensOut: m.tokensOut, latencyMs: m.latencyMs } : null,
  }
}

const dateFmt = new Intl.DateTimeFormat('es-ES', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })

export default function ChatPage() {
  const { conversationId } = useParams()
  const navigate = useNavigate()
  const [conversations, setConversations] = useState<ConversationSummary[] | null>(null)
  const [listError, setListError] = useState<string | null>(null)
  const [messages, setMessages] = useState<UiMessage[]>([])
  const [loadingMessages, setLoadingMessages] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [phase, setPhase] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const sendingRef = useRef(false) // guarda síncrona contra el doble envío (el estado de React llega tarde)
  const skipLoadFor = useRef<string | null>(null) // conversación recién creada por el stream: no recargarla
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const endRef = useRef<HTMLDivElement>(null)

  const refreshList = useCallback(async () => {
    try {
      setConversations(await listConversations())
      setListError(null)
    } catch (e) {
      setListError(errorMessage(e))
    }
  }, [])

  useEffect(() => {
    void refreshList()
  }, [refreshList])

  // Abrir una conversación: se pide al API (ajena o inexistente ⇒ 404 ⇒ mensaje; la UI no decide).
  useEffect(() => {
    if (!conversationId) {
      setMessages([])
      setError(null)
      return
    }
    if (skipLoadFor.current === conversationId) {
      skipLoadFor.current = null
      return
    }
    let cancelled = false
    setLoadingMessages(true)
    setError(null)
    setMessages([])
    getMessages(conversationId)
      .then((list) => !cancelled && setMessages(list.map(fromServer)))
      .catch((e) => !cancelled && setError(errorMessage(e, 'Conversación no encontrada.')))
      .finally(() => !cancelled && setLoadingMessages(false))
    return () => {
      cancelled = true
    }
  }, [conversationId])

  // Al salir de la pantalla se corta el stream en curso (el backend lo marca como cancelado).
  useEffect(() => () => abortRef.current?.abort(), [])

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ block: 'end' })
  }, [messages])

  function patchAssistant(fn: (m: UiMessage) => UiMessage) {
    setMessages((all) => all.map((m) => (m.key === 'pending-assistant' ? fn(m) : m)))
  }

  function onEvent(e: ChatEvent) {
    switch (e.type) {
      case 'conversation':
        if (e.conversationId !== conversationId) {
          skipLoadFor.current = e.conversationId
          navigate(`/chat/${e.conversationId}`, { replace: true })
        }
        break
      case 'status':
        setPhase(PHASE_TEXT[e.phase] ?? null)
        break
      case 'tool':
        setPhase(PHASE_TEXT.tool)
        break
      case 'delta':
        patchAssistant((m) => ({ ...m, content: m.content + e.text }))
        break
      case 'sources':
        patchAssistant((m) => ({ ...m, sources: e.sources }))
        break
      case 'verification':
        patchAssistant((m) => ({ ...m, verification: e.verification }))
        break
      case 'done':
        patchAssistant((m) => ({
          ...m,
          key: e.done.messageId,
          status: 'COMPLETED',
          usage: { tokensIn: e.done.tokensIn, tokensOut: e.done.tokensOut, latencyMs: e.done.latencyMs },
        }))
        break
    }
  }

  async function send() {
    const text = input.trim()
    if (!text || sendingRef.current) return
    sendingRef.current = true
    setSending(true)
    setError(null)
    setPhase(PHASE_TEXT.planning)
    const ctrl = new AbortController()
    abortRef.current = ctrl
    setInput('')
    setMessages((all) => [
      ...all,
      { key: `u-${Date.now()}`, role: 'USER', content: text, status: 'COMPLETED', sources: [], verification: null, usage: null },
      { key: 'pending-assistant', role: 'ASSISTANT', content: '', status: 'streaming', sources: [], verification: null, usage: null },
    ])
    try {
      await streamChat({ message: text, conversationId, signal: ctrl.signal }, onEvent)
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        patchAssistant((m) => ({ ...m, key: 'cancelled-assistant', status: 'CANCELLED' }))
      } else {
        setError(chatErrorMessage(e))
        // el chat sigue usable: la pregunta vuelve al cuadro para reenviarla y se conserva lo que llegó
        setInput((cur) => (cur === '' ? text : cur))
        setMessages((all) =>
          all
            .filter((m) => !(m.key === 'pending-assistant' && m.content === ''))
            .map((m) => (m.key === 'pending-assistant' ? { ...m, key: 'failed-assistant', status: 'FAILED', interrupted: true } : m)),
        )
        // un error HTTP del POST inicial (404, 400, 503…) no llegó a guardar nada: la pregunta del usuario se retira de la lista
        if (!(e instanceof ChatError)) setMessages((all) => all.filter((m) => !(m.role === 'USER' && m.content === text && m.status === 'COMPLETED' && m.key.startsWith('u-'))))
      }
    } finally {
      abortRef.current = null
      sendingRef.current = false
      setSending(false)
      setPhase(null)
      void refreshList()
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    void send()
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      void send()
    }
  }

  function newConversation() {
    if (sendingRef.current) return
    navigate('/chat')
    setMessages([])
    setError(null)
    inputRef.current?.focus()
  }

  async function doDelete(id: string) {
    setDeleting(true)
    try {
      await deleteConversation(id)
      setConfirmDelete(null)
      if (id === conversationId) navigate('/chat')
    } catch (e) {
      setConfirmDelete(null)
      setListError(errorMessage(e, 'La conversación ya no existe.'))
    } finally {
      setDeleting(false)
      void refreshList()
    }
  }

  return (
    <section aria-labelledby="chat-title">
      <h1 id="chat-title">Chat</h1>
      <div className="chat-layout">
        <aside className="chat-side" aria-label="Mis conversaciones">
          <button type="button" onClick={newConversation} disabled={sending} className="secondary">Nueva conversación</button>
          {listError && <ErrorBox message={listError} />}
          {!conversations && !listError && <Loading />}
          {conversations && conversations.length === 0 && <p className="muted" data-testid="no-conversations">Aún no tienes conversaciones.</p>}
          <ul className="conv-list" data-testid="conversations">
            {conversations?.map((c) => (
              <li key={c.id} className={c.id === conversationId ? 'conv active' : 'conv'}>
                {confirmDelete === c.id ? (
                  <div role="group" aria-label={`Confirmar borrado de «${c.title}»`} className="conv-confirm">
                    <p>¿Borrar «{c.title}»? No se puede deshacer.</p>
                    <button type="button" onClick={() => doDelete(c.id)} disabled={deleting}>Sí, borrar</button>{' '}
                    <button type="button" className="secondary" onClick={() => setConfirmDelete(null)} disabled={deleting}>Cancelar</button>
                  </div>
                ) : (
                  <>
                    <button
                      type="button"
                      className="conv-open"
                      aria-current={c.id === conversationId ? 'true' : undefined}
                      onClick={() => !sendingRef.current && navigate(`/chat/${c.id}`)}
                      disabled={sending}
                    >
                      <span className="conv-title">{c.title}</span>
                      <span className="muted conv-date">{dateFmt.format(new Date(c.updatedAt))}</span>
                    </button>
                    <button type="button" className="secondary conv-del" aria-label={`Borrar conversación «${c.title}»`} onClick={() => setConfirmDelete(c.id)} disabled={sending}>
                      Borrar
                    </button>
                  </>
                )}
              </li>
            ))}
          </ul>
        </aside>

        <div className="chat-main">
          <div className="chat-log" role="log" aria-live="polite" aria-busy={sending} aria-label="Conversación" data-testid="chat-log">
            {loadingMessages && <Loading what="Cargando conversación…" />}
            {!loadingMessages && messages.length === 0 && !error && (
              <p className="muted">Pregunta por los documentos a los que tienes acceso o por tus cifras (ventas, comisiones, pólizas, objetivos).</p>
            )}
            {messages.map((m) => <ChatMessage key={m.key} m={m} />)}
            <div ref={endRef} />
          </div>
          <p role="status" className="muted phase" data-testid="chat-phase">{sending ? (phase ?? 'Procesando…') : ''}</p>
          {error && <ErrorBox message={error} />}
          <form onSubmit={onSubmit} aria-label="Mensaje al asistente" className="chat-form">
            <label htmlFor="chat-input">Pregunta al asistente</label>
            <textarea
              id="chat-input"
              ref={inputRef}
              rows={3}
              maxLength={MAX_CHARS}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              aria-describedby="chat-hint"
              readOnly={sending}
            />
            <p id="chat-hint" className="muted hint">Enter envía · Shift+Enter nueva línea · {input.length}/{MAX_CHARS}</p>
            <div className="chat-actions">
              <button type="submit" disabled={sending || input.trim() === ''}>Enviar</button>
              {sending && <button type="button" className="secondary" onClick={() => abortRef.current?.abort()}>Cancelar</button>}
            </div>
          </form>
        </div>
      </div>
    </section>
  )
}
