import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { ChatSource, ChatVerification } from '../api/chat'

export interface UiMessage {
  key: string
  role: 'USER' | 'ASSISTANT'
  content: string
  /** Estado de la UI/servidor: streaming mientras llegan tokens; el resto vienen del servidor al releer. */
  status: 'streaming' | 'COMPLETED' | 'CANCELLED' | 'FAILED' | 'PENDING' | 'STREAMING' | string
  sources: ChatSource[]
  verification: ChatVerification | null
  usage: { tokensIn: number; tokensOut: number; latencyMs: number } | null
  phase?: string
  interrupted?: boolean
}

const CITATION = /\[([A-Z][A-Z0-9]{1,7})]/g
const INLINE = /(\*\*[^*\n]+\*\*|`[^`\n]+`|\[[A-Z][A-Z0-9]{1,7}])/g
const BULLET = /^\s*(?:[*+-]|\d+[.)])\s+(.*)$/
const ORDERED = /^\s*\d+[.)]\s+/

/** Texto con citas `[D04]` convertidas en enlaces SOLO si el servidor las devolvió como fuentes autorizadas. */
function withCitations(text: string, byCode: Map<string, ChatSource>, keyPrefix: string): ReactNode[] {
  const parts: ReactNode[] = []
  let last = 0
  let n = 0
  for (const m of text.matchAll(CITATION)) {
    const src = byCode.get(m[1])
    if (!src) continue
    if (m.index > last) parts.push(text.slice(last, m.index))
    parts.push(
      <Link key={`${keyPrefix}c${n++}`} to={`/documentos/${encodeURIComponent(src.documentId)}`} className="citation" title={src.title}>
        {m[0]}
      </Link>,
    )
    last = m.index + m[0].length
  }
  if (last < text.length) parts.push(text.slice(last))
  return parts
}

/** Inline mínimo: **negrita**, `código` y citas. Todo lo demás (HTML, enlaces markdown, imágenes) queda como texto literal. */
function inline(text: string, byCode: Map<string, ChatSource>, k: string): ReactNode[] {
  return text.split(INLINE).flatMap((tok, i): ReactNode[] => {
    if (tok.length > 4 && tok.startsWith('**') && tok.endsWith('**')) return [<strong key={`${k}b${i}`}>{withCitations(tok.slice(2, -2), byCode, `${k}b${i}`)}</strong>]
    if (tok.length > 2 && tok.startsWith('`') && tok.endsWith('`')) return [<code key={`${k}k${i}`}>{tok.slice(1, -1)}</code>]
    return withCitations(tok, byCode, `${k}t${i}`)
  })
}

/**
 * Contenido del MODELO como elementos de React construidos a mano (párrafos, listas, negrita, código): React escapa todo el
 * texto y nunca se usa dangerouslySetInnerHTML ni se convierte markdown a HTML, así que `<script>`, `<img onerror>`,
 * `[x](javascript:…)` o `<iframe>` se ven como texto, sin ejecutarse ni enlazar. Sin estilos ni atributos en línea (CSP).
 */
export function MessageText({ text, sources, markdown = true }: { text: string; sources: ChatSource[]; markdown?: boolean }): ReactNode {
  const byCode = new Map(sources.map((s) => [s.code, s]))
  if (!markdown) return <>{withCitations(text, byCode, 'p')}</>
  const blocks: ReactNode[] = []
  let para: string[] = []
  let items: string[] = []
  let ordered = false
  const flushPara = () => {
    if (para.length) blocks.push(<p key={`p${blocks.length}`}>{inline(para.join('\n'), byCode, `p${blocks.length}`)}</p>)
    para = []
  }
  const flushList = () => {
    if (!items.length) return
    const k = `l${blocks.length}`
    const lis = items.map((it, i) => <li key={`${k}i${i}`}>{inline(it, byCode, `${k}i${i}`)}</li>)
    blocks.push(ordered ? <ol key={k}>{lis}</ol> : <ul key={k}>{lis}</ul>)
    items = []
  }
  for (const line of text.split('\n')) {
    const b = BULLET.exec(line)
    if (b) {
      flushPara()
      const isOrdered = ORDERED.test(line)
      if (items.length && isOrdered !== ordered) flushList()
      ordered = isOrdered
      items.push(b[1])
    } else if (line.trim() === '') {
      flushPara()
      flushList()
    } else {
      flushList()
      para.push(line.replace(/^#{1,6}\s+/, ''))
    }
  }
  flushPara()
  flushList()
  return <>{blocks}</>
}

export function parseVerification(raw: unknown): ChatVerification | null {
  if (!raw || typeof raw !== 'object') return null
  const v = raw as Partial<ChatVerification>
  if (typeof v.ok !== 'boolean') return null
  return {
    ok: v.ok,
    citationsValid: v.citationsValid ?? true,
    citedCodes: v.citedCodes ?? [],
    unknownCitations: v.unknownCitations ?? [],
    numbersChecked: v.numbersChecked ?? 0,
    unsupportedNumbers: v.unsupportedNumbers ?? [],
  }
}

export function Verification({ v }: { v: ChatVerification }) {
  if (v.ok) {
    return (
      <p className="verification verification-ok" data-testid="verification">
        <span aria-hidden="true">✓ </span>Verificado: {v.numbersChecked === 0 ? 'sin cifras que comprobar' : `${v.numbersChecked} cifras respaldadas por los datos`}
        {v.citedCodes.length > 0 && ' y citas válidas'}.
      </p>
    )
  }
  return (
    <p className="verification verification-warn" data-testid="verification">
      <span aria-hidden="true">⚠ </span>Revisa esta respuesta:
      {v.unsupportedNumbers.length > 0 && ` cifras no respaldadas por los datos (${v.unsupportedNumbers.join(', ')});`}
      {v.unknownCitations.length > 0 && ` citas no reconocidas (${v.unknownCitations.join(', ')});`} no la tomes como definitiva.
    </p>
  )
}

const nf = new Intl.NumberFormat('es-ES')

export function UsageLine({ u }: { u: NonNullable<UiMessage['usage']> }) {
  const secs = (u.latencyMs / 1000).toLocaleString('es-ES', { maximumFractionDigits: 1 })
  return (
    <p className="muted usage" data-testid="usage">
      {nf.format(u.tokensIn)} tokens de entrada · {nf.format(u.tokensOut)} de salida · {secs} s
    </p>
  )
}

export function ChatMessage({ m }: { m: UiMessage }) {
  const mine = m.role === 'USER'
  const cited = m.sources.filter((s) => s.cited)
  const others = m.sources.filter((s) => !s.cited)
  return (
    <article className={`msg ${mine ? 'msg-user' : 'msg-assistant'}`} aria-label={mine ? 'Tu mensaje' : 'Respuesta del asistente'} data-testid={mine ? 'msg-user' : 'msg-assistant'}>
      <div className="msg-text" data-testid="msg-text">
        <MessageText text={m.content} sources={m.sources} markdown={!mine} />
        {m.status === 'streaming' && m.content === '' && <span className="muted">…</span>}
      </div>
      {m.interrupted && <p className="muted note">Respuesta incompleta.</p>}
      {m.status === 'CANCELLED' && <p className="muted note">Respuesta cancelada.</p>}
      {(m.status === 'FAILED' || m.status === 'PENDING' || m.status === 'STREAMING') && !mine && <p className="muted note">Esta respuesta no se completó.</p>}
      {!mine && m.sources.length > 0 && (
        <div className="sources" data-testid="sources">
          <span className="muted">Fuentes: </span>
          <ul>
            {[...cited, ...others].map((s) => (
              <li key={s.documentId}>
                <Link to={`/documentos/${encodeURIComponent(s.documentId)}`}>
                  <strong>{s.code}</strong> · {s.title}
                </Link>
                {s.cited && <span className="badge">citada</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {!mine && m.verification && m.status === 'COMPLETED' && <Verification v={m.verification} />}
      {!mine && m.usage && m.status === 'COMPLETED' && <UsageLine u={m.usage} />}
    </article>
  )
}
