// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'

const h = vi.hoisted(() => ({ getJson: vi.fn(), delete: vi.fn(), postStream: vi.fn() }))
vi.mock('../api/client', async (orig) => ({
  ...(await orig<typeof import('../api/client')>()),
  api: { getJson: h.getJson, delete: h.delete, postStream: h.postStream },
}))

import ChatPage from './ChatPage'

const enc = new TextEncoder()
const ev = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`
const DONE = ev('done', { messageId: 'm-1', tokensIn: 1200, tokensOut: 340, latencyMs: 2500 })
const OK_VERIF = ev('verification', { ok: true, citationsValid: true, citedCodes: ['D06'], unknownCitations: [], numbersChecked: 2, unsupportedNumbers: [] })
const SRC = ev('sources', { sources: [{ documentId: 'doc-6', code: 'D06', title: 'Informe de ventas', cited: true }] })
const CONV = ev('conversation', { conversationId: 'c-1', messageId: 'm-1' })

function streamOf(chunks: string[]): Response {
  return new Response(new ReadableStream({ start(c) { chunks.forEach((x) => c.enqueue(enc.encode(x))); c.close() } }))
}
/** Stream controlable a mano para probar el estado «generando». */
function manualStream() {
  let ctl!: ReadableStreamDefaultController<Uint8Array>
  const body = new ReadableStream<Uint8Array>({ start(c) { ctl = c } })
  return { res: new Response(body), push: (s: string) => ctl.enqueue(enc.encode(s)), close: () => ctl.close() }
}

const LIST = [
  { id: 'c-1', title: 'Ventas de octubre', createdAt: '2027-01-10T10:00:00Z', updatedAt: '2027-01-10T10:05:00Z' },
  { id: 'c-2', title: 'Mis comisiones', createdAt: '2027-01-11T10:00:00Z', updatedAt: '2027-01-11T10:05:00Z' },
]

function serve(list = LIST, messages: unknown[] = []) {
  h.getJson.mockImplementation(async (path: string) => {
    if (path === '/chat/conversations') return list
    if (/\/messages$/.test(path)) return messages
    throw new ApiError(404, 'no')
  })
}
function renderAt(path = '/chat') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/chat/:conversationId?" element={<ChatPage />} />
        <Route path="/documentos/:id" element={<p>visor del documento</p>} />
      </Routes>
    </MemoryRouter>,
  )
}
const box = () => screen.getByLabelText('Pregunta al asistente')

beforeEach(() => {
  h.getJson.mockReset(); h.delete.mockReset(); h.postStream.mockReset()
  serve()
})
afterEach(cleanup)

describe('chat: envío y streaming', () => {
  it('muestra la respuesta, fuentes clicables, verificación y tokens/latencia solo al terminar', async () => {
    const m = manualStream()
    h.postStream.mockResolvedValue(m.res)
    const user = userEvent.setup()
    renderAt()
    await user.type(box(), '¿Ventas de octubre?')
    await user.click(screen.getByRole('button', { name: 'Enviar' }))
    m.push(CONV + ev('status', { phase: 'generating' }) + ev('delta', { text: 'Se vendieron 12 pólizas ' }))
    await screen.findByText(/Se vendieron 12 pólizas/)
    // mientras genera: ni tokens ni verificación; aria-live y botón cancelar
    expect(screen.queryByTestId('usage')).toBeNull()
    expect(screen.queryByTestId('verification')).toBeNull()
    expect(screen.getByRole('log')).toHaveAttribute('aria-live', 'polite')
    expect(screen.getByRole('log')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument()
    m.push(ev('delta', { text: 'según [D06].' }) + SRC + OK_VERIF + DONE)
    m.close()
    await screen.findByTestId('usage')
    expect(screen.getByTestId('usage')).toHaveTextContent(/1\.?200 tokens de entrada · 340 de salida · 2,5 s/)
    expect(screen.getByTestId('verification')).toHaveTextContent('Verificado')
    expect(screen.getByRole('log')).toHaveAttribute('aria-busy', 'false')
    // la cita [D06] del texto y la lista de fuentes enlazan al documento
    const links = within(screen.getByTestId('chat-log')).getAllByRole('link')
    expect(links.map((l) => l.getAttribute('href'))).toEqual(['/documentos/doc-6', '/documentos/doc-6'])
    expect(h.postStream).toHaveBeenCalledWith('/chat', { conversationId: undefined, message: '¿Ventas de octubre?' }, expect.any(AbortSignal))
  })

  it('verificación con cifras o citas no respaldadas se muestra como aviso', async () => {
    const bad = ev('verification', { ok: false, citationsValid: false, citedCodes: ['D99'], unknownCitations: ['D99'], numbersChecked: 3, unsupportedNumbers: ['7.777'] })
    h.postStream.mockResolvedValue(streamOf([CONV, ev('delta', { text: 'x' }), bad, DONE]))
    const user = userEvent.setup()
    renderAt()
    await user.type(box(), 'hola{Enter}')
    const v = await screen.findByTestId('verification')
    expect(v).toHaveTextContent(/Revisa esta respuesta/)
    expect(v).toHaveTextContent('7.777')
    expect(v).toHaveTextContent('D99')
  })

  it('Enter envía y Shift+Enter inserta salto de línea sin enviar', async () => {
    h.postStream.mockResolvedValue(streamOf([CONV, ev('delta', { text: 'ok' }), DONE]))
    const user = userEvent.setup()
    renderAt()
    await user.type(box(), 'línea 1{Shift>}{Enter}{/Shift}línea 2')
    expect(h.postStream).not.toHaveBeenCalled()
    expect(box()).toHaveValue('línea 1\nlínea 2')
    await user.keyboard('{Enter}')
    await waitFor(() => expect(h.postStream).toHaveBeenCalledTimes(1))
    expect(h.postStream.mock.calls[0][1].message).toBe('línea 1\nlínea 2')
  })

  it('no hay doble envío: Enter repetido y clics mientras genera hacen una sola petición', async () => {
    const m = manualStream()
    h.postStream.mockResolvedValue(m.res)
    renderAt()
    fireEvent.change(box(), { target: { value: 'una pregunta' } })
    fireEvent.keyDown(box(), { key: 'Enter' })
    fireEvent.keyDown(box(), { key: 'Enter' })
    fireEvent.submit(screen.getByRole('form', { name: 'Mensaje al asistente' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Enviar' })).toBeDisabled()
    expect(h.postStream).toHaveBeenCalledTimes(1)
    m.push(CONV + DONE); m.close()
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Cancelar' })).toBeNull())
  })

  it('no envía mensajes vacíos', async () => {
    renderAt()
    expect(screen.getByRole('button', { name: 'Enviar' })).toBeDisabled()
    fireEvent.change(box(), { target: { value: '   ' } })
    fireEvent.keyDown(box(), { key: 'Enter' })
    expect(h.postStream).not.toHaveBeenCalled()
  })

  it('Cancelar aborta la petición, conserva lo recibido y deja el chat usable', async () => {
    let signal!: AbortSignal
    h.postStream.mockImplementation(async (_p: string, _b: unknown, s: AbortSignal) => {
      signal = s
      const body = new ReadableStream<Uint8Array>({
        start(c) { c.enqueue(enc.encode(CONV + ev('delta', { text: 'parcial' }))); s.addEventListener('abort', () => c.error(new DOMException('x', 'AbortError'))) },
      })
      return new Response(body)
    })
    const user = userEvent.setup()
    renderAt()
    await user.type(box(), 'larga{Enter}')
    await screen.findByText('parcial')
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    await screen.findByText('Respuesta cancelada.')
    expect(signal.aborted).toBe(true)
    expect(screen.getByText('parcial')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(box()).not.toHaveAttribute('readonly')
    expect(screen.getByRole('button', { name: 'Enviar' })).toBeDisabled() // vacío, pero ya no hay «Cancelar»
    expect(screen.queryByRole('button', { name: 'Cancelar' })).toBeNull()
  })
})

describe('chat: errores recuperables', () => {
  it.each([429, 503])('HTTP %i del POST ⇒ mensaje de límite de uso, pregunta recuperada y chat usable', async (status) => {
    h.postStream.mockRejectedValue(new ApiError(status, 'x'))
    const user = userEvent.setup()
    renderAt()
    await user.type(box(), 'mi pregunta{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent(/límite de uso del modelo, ?|límite de uso del modelo/)
    expect(screen.getByRole('alert')).toHaveTextContent('Reintenta en unos minutos')
    expect(box()).toHaveValue('mi pregunta')
    expect(box()).not.toHaveAttribute('readonly')
    expect(screen.queryByTestId('msg-user')).toBeNull()
  })

  it('evento error dentro del stream ⇒ mismo mensaje, y el siguiente envío funciona', async () => {
    h.postStream.mockResolvedValueOnce(streamOf([CONV, ev('error', { status: 503, code: 'ai-unavailable', detail: 'x' })]))
    const user = userEvent.setup()
    renderAt()
    await user.type(box(), 'primera{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent('límite de uso del modelo')
    h.postStream.mockResolvedValueOnce(streamOf([CONV, ev('delta', { text: 'ya funciona' }), DONE]))
    await user.click(screen.getByRole('button', { name: 'Enviar' })) // la pregunta volvió al cuadro
    await screen.findByText('ya funciona')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('stream interrumpido: conserva el texto parcial, lo marca incompleto y no bloquea', async () => {
    h.postStream.mockResolvedValue(streamOf([CONV, ev('delta', { text: 'a medias' })]))
    const user = userEvent.setup()
    renderAt()
    await user.type(box(), 'pregunta{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent('interrumpió')
    expect(screen.getByText('a medias')).toBeInTheDocument()
    expect(screen.getByText('Respuesta incompleta.')).toBeInTheDocument()
    expect(box()).not.toHaveAttribute('readonly')
  })

  it('conversación ajena por URL ⇒ 404 ⇒ «Conversación no encontrada.»', async () => {
    h.getJson.mockImplementation(async (path: string) => {
      if (path === '/chat/conversations') return LIST
      throw new ApiError(404, 'Not Found')
    })
    renderAt('/chat/de-otro-usuario')
    expect(h.getJson).toHaveBeenCalledWith('/chat/conversations/de-otro-usuario/messages')
    expect(await screen.findByRole('alert')).toHaveTextContent('Conversación no encontrada.')
    expect(screen.queryByTestId('msg-text')).toBeNull()
  })
})

describe('chat: render seguro del contenido del modelo', () => {
  const EVIL = [
    '<img src=x onerror="window.__pwned=1">',
    '<script>window.__pwned=1</script>',
    '<a href="javascript:window.__pwned=1">pincha</a>',
    '[clic](javascript:window.__pwned=1) **negrita** `code` # titulo',
    '<style>body{display:none}</style><iframe src="https://evil.example"></iframe>',
  ].join('\n')

  it('HTML y markdown maliciosos se pintan literalmente como texto: ningún nodo ejecutable', async () => {
    h.postStream.mockResolvedValue(streamOf([CONV, ev('delta', { text: EVIL }), SRC, OK_VERIF, DONE]))
    const user = userEvent.setup()
    renderAt()
    await user.type(box(), 'x{Enter}')
    await screen.findByTestId('usage')
    const text = screen.getAllByTestId('msg-text')[1]
    const log = screen.getByTestId('chat-log')
    // el HTML y el enlace markdown peligrosos aparecen LITERALES como texto
    for (const lit of ['<img src=x onerror="window.__pwned=1">', '<script>window.__pwned=1</script>', '<a href="javascript:window.__pwned=1">pincha</a>', '[clic](javascript:window.__pwned=1)', '<style>body{display:none}</style>', '<iframe src="https://evil.example"></iframe>']) {
      expect(text.textContent, lit).toContain(lit)
    }
    for (const sel of ['script', 'img', 'iframe', 'style', 'a', '[onerror]', '[style]', '[href^="javascript"]']) {
      expect(log.querySelector(`[data-testid="msg-text"] ${sel}, .msg-text ${sel}`), sel).toBeNull() // ningún nodo ejecutable ni enlace dentro del texto del modelo
    }
    expect(text.querySelector('strong')?.textContent).toBe('negrita') // el markdown permitido sí se formatea…
    expect(text.querySelector('code')?.textContent).toBe('code') // …pero solo como elementos de React
    expect((window as unknown as { __pwned?: number }).__pwned).toBeUndefined()
  })

  it('formatea listas y negrita del modelo sin HTML crudo', async () => {
    const md = 'Resumen:\n\n* **Pedro:** 105,0 %\n* Laura: 76,0 %\n\n1. uno\n2. dos\n\nFin [D06]'
    h.postStream.mockResolvedValue(streamOf([CONV, ev('delta', { text: md }), SRC, OK_VERIF, DONE]))
    const user = userEvent.setup()
    renderAt()
    await user.type(box(), 'x{Enter}')
    await screen.findByTestId('usage')
    const text = screen.getAllByTestId('msg-text')[1]
    expect([...text.querySelectorAll('ul > li')].map((l) => l.textContent)).toEqual(['Pedro: 105,0 %', 'Laura: 76,0 %'])
    expect([...text.querySelectorAll('ol > li')].map((l) => l.textContent)).toEqual(['uno', 'dos'])
    expect(text.querySelector('strong')?.textContent).toBe('Pedro:')
    expect(within(text).getByRole('link', { name: '[D06]' })).toHaveAttribute('href', '/documentos/doc-6')
    expect(text.innerHTML).not.toContain('**')
  })

  it('una cita [D99] que el servidor no devolvió como fuente no se convierte en enlace', async () => {
    h.postStream.mockResolvedValue(streamOf([CONV, ev('delta', { text: 'ver [D99] y [D06]' }), SRC, OK_VERIF, DONE]))
    const user = userEvent.setup()
    renderAt()
    await user.type(box(), 'x{Enter}')
    await screen.findByTestId('usage')
    const text = screen.getAllByTestId('msg-text')[1]
    expect(within(text).getAllByRole('link').map((l) => l.textContent)).toEqual(['[D06]'])
    expect(text.textContent).toBe('ver [D99] y [D06]')
  })

  it('el historial también se pinta como texto', async () => {
    serve(LIST, [
      { id: 'm1', role: 'USER', content: 'hola', status: 'COMPLETED', sources: [], tools: [], verification: null, tokensIn: null, tokensOut: null, latencyMs: null, createdAt: '' },
      { id: 'm2', role: 'ASSISTANT', content: '<b onmouseover=alert(1)>x</b>', status: 'COMPLETED', sources: [], tools: [], verification: { ok: true, numbersChecked: 0, citedCodes: [], unknownCitations: [], unsupportedNumbers: [], citationsValid: true }, tokensIn: 10, tokensOut: 5, latencyMs: 900, createdAt: '' },
    ])
    renderAt('/chat/c-1')
    await screen.findByText('<b onmouseover=alert(1)>x</b>')
    expect(screen.getByTestId('chat-log').querySelector('b')).toBeNull()
    expect(screen.getByTestId('usage')).toHaveTextContent('10 tokens')
  })
})

describe('chat: conversaciones', () => {
  it('lista solo las que devuelve el API y abre una pidiendo sus mensajes', async () => {
    serve(LIST, [{ id: 'm1', role: 'USER', content: 'pregunta vieja', status: 'COMPLETED', sources: [], tools: [], verification: null, tokensIn: null, tokensOut: null, latencyMs: null, createdAt: '' }])
    const user = userEvent.setup()
    renderAt()
    const items = await screen.findAllByRole('listitem')
    expect(items).toHaveLength(2)
    await user.click(within(items[1]).getByRole('button', { name: /^Mis comisiones/ }))
    await screen.findByText('pregunta vieja')
    expect(h.getJson).toHaveBeenCalledWith('/chat/conversations/c-2/messages')
  })

  it('sin conversaciones muestra el estado vacío', async () => {
    serve([])
    renderAt()
    expect(await screen.findByTestId('no-conversations')).toBeInTheDocument()
  })

  it('borrar pide confirmación; cancelar no borra; confirmar llama a DELETE y refresca la lista', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('Ventas de octubre')
    await user.click(screen.getByRole('button', { name: 'Borrar conversación «Ventas de octubre»' }))
    expect(screen.getByRole('group', { name: /Confirmar borrado/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(h.delete).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Borrar conversación «Ventas de octubre»' }))
    serve([LIST[1]])
    await user.click(screen.getByRole('button', { name: 'Sí, borrar' }))
    await waitFor(() => expect(h.delete).toHaveBeenCalledWith('/chat/conversations/c-1'))
    await waitFor(() => expect(screen.queryByText('Ventas de octubre')).toBeNull())
    expect(screen.getByText('Mis comisiones')).toBeInTheDocument()
  })

  it('«Nueva conversación» vacía la pantalla y el siguiente mensaje no lleva conversationId', async () => {
    serve(LIST, [{ id: 'm1', role: 'USER', content: 'vieja', status: 'COMPLETED', sources: [], tools: [], verification: null, tokensIn: null, tokensOut: null, latencyMs: null, createdAt: '' }])
    h.postStream.mockResolvedValue(streamOf([CONV, ev('delta', { text: 'hola' }), DONE]))
    const user = userEvent.setup()
    renderAt('/chat/c-1')
    await screen.findByText('vieja')
    await user.click(screen.getByRole('button', { name: 'Nueva conversación' }))
    await waitFor(() => expect(screen.queryByText('vieja')).toBeNull())
    await user.type(box(), 'nueva{Enter}')
    await waitFor(() => expect(h.postStream).toHaveBeenCalled())
    expect(h.postStream.mock.calls[0][1].conversationId).toBeUndefined()
  })

  it('continuar una conversación abierta envía su conversationId', async () => {
    h.postStream.mockResolvedValue(streamOf([CONV, ev('delta', { text: 'hola' }), DONE]))
    const user = userEvent.setup()
    renderAt('/chat/c-1')
    await waitFor(() => expect(h.getJson).toHaveBeenCalledWith('/chat/conversations/c-1/messages'))
    await user.type(box(), 'otra{Enter}')
    await waitFor(() => expect(h.postStream).toHaveBeenCalled())
    expect(h.postStream.mock.calls[0][1].conversationId).toBe('c-1')
  })
})
