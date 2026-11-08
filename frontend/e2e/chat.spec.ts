import { expect, test, type Page, type Route } from '@playwright/test'
import { cleanupE2eConversations, conversationIds, liveConversations, login, readEnv, sql } from './support'

// F5. Presupuesto de llamadas REALES al modelo (capa gratuita de Gemini, ~20 peticiones/día): cada turno de chat real gasta 2
// peticiones (planificador + generador). Esta suite hace 2 turnos reales (DIR001 y EMP001); el resto va con POST /chat interceptado
// (page.route) y no toca Dify ni Gemini.
const DIR_QUESTION = '¿Cuántas pólizas y qué total de primas se vendieron en octubre de 2026?'
const EMP_QUESTION = '¿Qué cubre el seguro de hogar?'
const REAL_TIMEOUT = 120_000
let realTurns = 0

const sse = (events: [string, unknown][]) => events.map(([e, d]) => `event: ${e}\ndata: ${JSON.stringify(d)}\n\n`).join('')
const CONV = ['conversation', { conversationId: '00000000-0000-4000-8000-000000000001', messageId: '00000000-0000-4000-8000-000000000002' }] as [string, unknown]
const DONE = ['done', { messageId: '00000000-0000-4000-8000-000000000002', tokensIn: 100, tokensOut: 20, latencyMs: 900 }] as [string, unknown]

/** Intercepta SOLO POST /api/v1/chat (no /chat/conversations…). */
async function mockChat(page: Page, handler: (route: Route, n: number) => Promise<void> | void) {
  let n = 0
  await page.route(/\/api\/v1\/chat$/, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    await handler(route, ++n)
  })
  return () => n
}
const ask = async (page: Page, text: string) => {
  await page.getByLabel('Pregunta al asistente').fill(text)
  await page.keyboard.press('Enter')
}
const gotoChat = async (page: Page) => {
  await page.getByRole('link', { name: 'Chat' }).click()
  await expect(page.getByRole('heading', { name: 'Chat', level: 1 })).toBeVisible()
}

/** Errores de la CSP estricta de Caddy (sin 'unsafe-inline') que el navegador reporta en consola. */
function watchCsp(page: Page): string[] {
  const violations: string[] = []
  page.on('console', (m) => /content security policy|refused to (apply|execute|load)/i.test(m.text()) && violations.push(m.text()))
  page.on('pageerror', (e) => violations.push(`pageerror: ${e.message}`))
  return violations
}

test.afterAll(() => console.log(`chat E2E: ${realTurns} turnos reales al modelo (≈${realTurns * 2} peticiones a Gemini)`))

test.describe.serial('F5: chat real (2 turnos reales: DIR001 y EMP001)', () => {
  const before = conversationIds()
  let dirConversation = ''
  let dirTitle = ''
  test.afterAll(() => {
    const n = cleanupE2eConversations(before)
    console.log(`conversaciones creadas por los E2E y borradas de la BD: ${n}`)
    expect(conversationIds().size).toBe(before.size)
  })

  test('DIR001 pregunta por las ventas de octubre: streaming SSE, cifras reales, verificación, tokens/latencia y sin claves en la red', async ({ page }) => {
    test.setTimeout(REAL_TIMEOUT + 30_000)
    const csp = watchCsp(page)
    const env = readEnv()
    const secrets = ['DIFY_APP_API_KEY', 'DIFY_APP_API_KEY_FALLBACK', 'DIFY_PLANNER_API_KEY', 'DIFY_PLANNER_API_KEY_FALLBACK', 'DIFY_KNOWLEDGE_API_KEY', 'GEMINI_API_KEY']
      .map((k) => env[k]).filter((v) => v && v.length >= 8)
    const leaks: string[] = []
    let chatRequest: { auth: string | null; accept: string | null; body: string | null; contentType: string | null } | null = null
    page.on('request', (r) => {
      if (/\/api\/v1\/chat$/.test(r.url()) && r.method() === 'POST') {
        const h = r.headers()
        chatRequest = { auth: h.authorization ?? null, accept: h.accept ?? null, body: r.postData(), contentType: null }
      }
    })
    page.on('response', async (res) => {
      if (!res.url().includes('/api/v1/chat')) return
      if (/\/chat$/.test(res.url())) chatRequest && (chatRequest.contentType = res.headers()['content-type'] ?? null)
      const body = (await res.text().catch(() => '')) + JSON.stringify(res.headers())
      for (const s of secrets) if (body.includes(s)) leaks.push(res.url())
    })
    await login(page, 'DIR001')
    await gotoChat(page)
    await expect(page.getByLabel('Pregunta al asistente')).toBeEnabled() // ya no hay «no disponible»
    await ask(page, DIR_QUESTION)
    realTurns++
    await expect(page.getByTestId('msg-user')).toContainText('octubre de 2026')
    await expect(page.getByTestId('usage')).toBeVisible({ timeout: REAL_TIMEOUT }) // tokens/latencia solo al terminar
    const answer = page.getByTestId('msg-assistant').getByTestId('msg-text')
    await expect(answer).toContainText('12')
    await expect(answer).toContainText('9.680')
    await expect(page.getByTestId('verification')).toBeVisible()
    await expect(page.getByTestId('verification')).toContainText('Verificado')
    await expect(page.getByTestId('usage')).toContainText(/tokens de entrada .* de salida .* s/)
    expect(chatRequest!.auth).toMatch(/^Bearer eyJ/) // token en cabecera, no en la URL
    expect(chatRequest!.accept).toBe('text/event-stream')
    expect(JSON.parse(chatRequest!.body!)).toEqual({ message: DIR_QUESTION }) // solo message (sin identidad; conversationId aún no existe)
    expect(chatRequest!.contentType).toContain('text/event-stream')
    // tras terminar: foco usable, la conversación nueva aparece y la URL apunta a ella
    await expect(page).toHaveURL(/\/chat\/[0-9a-f-]{36}$/)
    dirConversation = page.url().split('/').pop()!
    await expect(page.getByTestId('conversations').getByRole('listitem').first()).toContainText('pólizas')
    dirTitle = (await page.getByTestId('conversations').getByRole('listitem').first().locator('.conv-title').innerText()).trim()
    expect(liveConversations('DIR001').map((c) => c.id)).toContain(dirConversation)
    expect(sql(`SELECT status FROM messages WHERE conversation_id='${dirConversation}' AND role='ASSISTANT'`)).toBe('COMPLETED')
    expect(leaks, 'ninguna clave de Dify/Gemini en las respuestas del chat').toEqual([])
    expect(csp, 'sin violaciones de la CSP').toEqual([])
  })

  test('recargar y reabrir la conversación de DIR001 recupera mensajes, verificación y tokens del historial', async ({ page }) => {
    await login(page, 'DIR001')
    await gotoChat(page)
    await page.getByTestId('conversations').getByRole('button', { name: dirTitle }).first().click()
    await expect(page).toHaveURL(new RegExp(`/chat/${dirConversation}$`))
    await expect(page.getByTestId('msg-user')).toContainText('octubre de 2026')
    await expect(page.getByTestId('msg-assistant').getByTestId('msg-text')).toContainText('9.680')
    await expect(page.getByTestId('verification')).toBeVisible()
    await expect(page.getByTestId('usage')).toBeVisible()
  })

  test('EMP001 hace una pregunta documental real: ve fuentes, la cita es clicable y abre el documento', async ({ page }) => {
    test.setTimeout(REAL_TIMEOUT + 30_000)
    const csp = watchCsp(page)
    await login(page, 'EMP001')
    await gotoChat(page)
    await ask(page, EMP_QUESTION)
    realTurns++
    await expect(page.getByTestId('usage')).toBeVisible({ timeout: REAL_TIMEOUT })
    const sources = page.getByTestId('sources')
    await expect(sources).toBeVisible()
    await expect(sources.getByRole('link', { name: /D27/ })).toBeVisible()
    await expect(sources.getByText('citada')).toBeVisible()
    // la cita [D27] dentro del texto es un enlace
    const inline = page.getByTestId('msg-assistant').getByTestId('msg-text').getByRole('link', { name: '[D27]' }).first()
    await expect(inline).toBeVisible()
    await inline.click()
    await expect(page).toHaveURL(/\/documentos\/[0-9a-f-]{36}$/)
    await expect(page.getByTestId('doc-code')).toHaveText('D27')
    await expect(page.getByRole('button', { name: 'Descargar' })).toBeVisible()
    expect(csp).toEqual([])
    await page.goBack() // al volver, la conversación se recarga del servidor con sus fuentes
    await expect(page.getByTestId('sources')).toBeVisible()
  })

  test('privacidad: EMP001 solo lista SUS conversaciones y la de DIR001 (por URL) da «no encontrada»', async ({ page }) => {
    await login(page, 'EMP001')
    await gotoChat(page)
    const mine = liveConversations('EMP001').map((c) => c.title).sort()
    await expect(page.getByTestId('conversations').getByRole('listitem')).toHaveCount(mine.length)
    const shown = (await page.getByTestId('conversations').locator('.conv-title').allInnerTexts()).map((t) => t.trim()).sort()
    expect(shown).toEqual(mine.map((t) => t.trim()))
    const others = new Set(['DIR001', 'ACC001', 'COM001', 'EMP002', 'EMP003', 'EMP004'].flatMap((u) => liveConversations(u).map((c) => c.id)))
    expect(others.has(dirConversation)).toBe(true)
    expect(shown).not.toContain(dirTitle)
    // abrir la conversación ajena por URL: la UI pide al API y muestra el 404; no aparece en la lista
    const status: number[] = []
    page.on('response', (r) => r.url().includes(`/chat/conversations/${dirConversation}/messages`) && status.push(r.status()))
    await page.goto(`/chat/${dirConversation}`)
    await expect(page.getByRole('alert')).toContainText('Conversación no encontrada.')
    expect(status).toEqual([404])
    await expect(page.getByTestId('msg-text')).toHaveCount(0)
    await expect(page.getByTestId('conversations').getByRole('button', { name: dirTitle })).toHaveCount(0)
  })

  test('DIR001 borra su conversación: pide confirmación, cancelar no borra, confirmar la quita y su URL da 404', async ({ page }) => {
    await login(page, 'DIR001')
    await gotoChat(page)
    const item = page.getByTestId('conversations').getByRole('listitem').filter({ hasText: dirTitle }).first()
    await expect(item).toBeVisible()
    const total = await page.getByTestId('conversations').getByRole('listitem').count()
    await item.getByRole('button', { name: /^Borrar conversación/ }).click()
    await expect(page.getByRole('group', { name: /Confirmar borrado/ })).toBeVisible()
    await page.getByRole('button', { name: 'Cancelar' }).click()
    expect(liveConversations('DIR001').map((c) => c.id)).toContain(dirConversation) // cancelar no borra
    await page.getByTestId('conversations').getByRole('listitem').filter({ hasText: dirTitle }).first().getByRole('button', { name: /^Borrar conversación/ }).click()
    await page.getByRole('button', { name: 'Sí, borrar' }).click()
    await expect(page.getByTestId('conversations').getByRole('listitem')).toHaveCount(total - 1)
    expect(liveConversations('DIR001').map((c) => c.id)).not.toContain(dirConversation)
    await page.goto(`/chat/${dirConversation}`)
    await expect(page.getByRole('alert')).toContainText('Conversación no encontrada.')
  })
})

test.describe('F5: errores y controles con POST /chat interceptado (0 llamadas al modelo)', () => {
  test('429 del servidor ⇒ mensaje de límite de uso, la pregunta vuelve al cuadro y se puede reintentar', async ({ page }) => {
    const calls = await mockChat(page, async (route, n) => {
      if (n === 1) return route.fulfill({ status: 429, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Too Many Requests', status: 429 }) })
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([CONV, ['delta', { text: 'Ya sí.' }], ['sources', { sources: [] }], ['verification', { ok: true, citationsValid: true, citedCodes: [], unknownCitations: [], numbersChecked: 0, unsupportedNumbers: [] }], DONE]) })
    })
    await login(page, 'EMP001')
    await gotoChat(page)
    await ask(page, 'pregunta de prueba')
    await expect(page.getByRole('alert')).toContainText('límite de uso del modelo')
    await expect(page.getByRole('alert')).toContainText('Reintenta en unos minutos')
    await expect(page.getByLabel('Pregunta al asistente')).toHaveValue('pregunta de prueba')
    await page.getByRole('button', { name: 'Enviar' }).click()
    await expect(page.getByTestId('msg-assistant')).toContainText('Ya sí.')
    await expect(page.getByRole('alert')).toHaveCount(0)
    expect(calls()).toBe(2)
  })

  test('503 (sin motor de IA) y evento error ai-unavailable muestran el mismo mensaje claro', async ({ page }) => {
    await mockChat(page, async (route, n) => {
      if (n === 1) return route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Service Unavailable', status: 503, detail: 'The assistant is temporarily unavailable. Please try again.' }) })
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([CONV, ['error', { status: 503, code: 'ai-unavailable', detail: 'x' }]]) })
    })
    await login(page, 'EMP001')
    await gotoChat(page)
    await ask(page, 'uno')
    await expect(page.getByRole('alert')).toContainText('límite de uso del modelo')
    await page.getByRole('button', { name: 'Enviar' }).click()
    await expect(page.getByRole('alert')).toContainText('Reintenta en unos minutos')
    await expect(page.getByLabel('Pregunta al asistente')).toBeEnabled()
  })

  test('timeout del modelo (evento error ai-timeout) ⇒ mensaje de que tardó demasiado', async ({ page }) => {
    await mockChat(page, (route) => route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([CONV, ['delta', { text: 'empiezo…' }], ['error', { status: 504, code: 'ai-timeout', detail: 'x' }]]) }))
    await login(page, 'EMP001')
    await gotoChat(page)
    await ask(page, 'lenta')
    await expect(page.getByRole('alert')).toContainText('tardado demasiado')
    await expect(page.getByTestId('msg-assistant')).toContainText('empiezo…')
    await expect(page.getByLabel('Pregunta al asistente')).toBeEnabled()
  })

  test('stream cortado sin «done» ⇒ respuesta incompleta, error recuperable y chat libre', async ({ page }) => {
    await mockChat(page, (route) => route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([CONV, ['delta', { text: 'a medias' }]]) }))
    await login(page, 'EMP001')
    await gotoChat(page)
    await ask(page, 'se corta')
    await expect(page.getByRole('alert')).toContainText('interrumpió')
    await expect(page.getByText('Respuesta incompleta.')).toBeVisible()
    await expect(page.getByLabel('Pregunta al asistente')).toBeEnabled()
    await expect(page.getByRole('button', { name: 'Cancelar' })).toHaveCount(0)
  })

  test('401 en la petición del chat ⇒ refresh único con la cookie y reintento transparente (sigue la sesión)', async ({ page }) => {
    const refreshes: string[] = []
    page.on('request', (r) => /\/api\/v1\/auth\/refresh$/.test(r.url()) && refreshes.push(r.url()))
    const auths: (string | undefined)[] = []
    await mockChat(page, async (route, n) => {
      auths.push(route.request().headers().authorization)
      if (n === 1) return route.fulfill({ status: 401, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Unauthorized', status: 401 }) })
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([CONV, ['delta', { text: 'tras el refresh' }], DONE]) })
    })
    await login(page, 'EMP001')
    await gotoChat(page)
    const before = refreshes.length
    await ask(page, 'con 401')
    await expect(page.getByTestId('msg-assistant')).toContainText('tras el refresh')
    expect(refreshes.length - before).toBe(1)
    expect(auths).toHaveLength(2)
    expect(auths[0]).not.toBe(auths[1]) // el reintento usa el access token nuevo
    await expect(page.getByRole('heading', { name: 'Chat', level: 1 })).toBeVisible() // no se volvió al login
  })

  test('Enter envía, Shift+Enter hace salto de línea y un envío repetido mientras genera no duplica la petición', async ({ page }) => {
    const bodies: string[] = []
    const calls = await mockChat(page, async (route) => {
      bodies.push(route.request().postData() ?? '')
      await new Promise((r) => setTimeout(r, 1500)) // la respuesta tarda: hay ventana para intentar el doble envío
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([CONV, ['delta', { text: 'listo' }], DONE]) })
    })
    await login(page, 'EMP001')
    await gotoChat(page)
    const box = page.getByLabel('Pregunta al asistente')
    await box.fill('línea 1')
    await page.keyboard.press('Shift+Enter')
    await page.keyboard.type('línea 2')
    expect(calls()).toBe(0)
    await expect(box).toHaveValue('línea 1\nlínea 2')
    await page.keyboard.press('Enter')
    await expect(page.getByRole('button', { name: 'Cancelar' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Enviar' })).toBeDisabled()
    await page.keyboard.press('Enter') // intento de doble envío (cuadro de solo lectura)
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('msg-assistant')).toContainText('listo')
    expect(calls()).toBe(1)
    expect(JSON.parse(bodies[0]).message).toBe('línea 1\nlínea 2')
    await expect(page.getByLabel('Pregunta al asistente')).toBeFocused() // el foco queda en el cuadro para seguir escribiendo
  })

  test('«Cancelar» aborta la petición en el navegador y deja el chat usable', async ({ page }) => {
    let aborted = false
    page.on('requestfailed', (r) => /\/api\/v1\/chat$/.test(r.url()) && (aborted = true))
    await mockChat(page, async (route) => {
      await new Promise((r) => setTimeout(r, 20_000)).catch(() => undefined)
      await route.abort().catch(() => undefined)
    })
    await login(page, 'EMP001')
    await gotoChat(page)
    await ask(page, 'cancela esto')
    await page.getByRole('button', { name: 'Cancelar' }).click()
    await expect(page.getByText('Respuesta cancelada.')).toBeVisible()
    await expect.poll(() => aborted).toBe(true)
    await expect(page.getByRole('alert')).toHaveCount(0)
    await expect(page.getByLabel('Pregunta al asistente')).toBeEditable()
  })

  test('contenido malicioso del modelo se pinta como texto con la CSP real: no se ejecuta nada y no hay violaciones', async ({ page }) => {
    const csp = watchCsp(page)
    const evil = '<img src=x onerror="window.__pwned=1"><script>window.__pwned=1</script>\n[clic](javascript:window.__pwned=1)\n<a href="javascript:window.__pwned=1">x</a>\n**negrita** y `code`'
    await mockChat(page, (route) => route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([CONV, ['delta', { text: evil }], ['sources', { sources: [] }], DONE]) }))
    await login(page, 'EMP001')
    await gotoChat(page)
    await ask(page, 'xss')
    const text = page.getByTestId('msg-assistant').getByTestId('msg-text')
    await expect(text).toContainText('<script>window.__pwned=1</script>')
    await expect(text).toContainText('[clic](javascript:window.__pwned=1)')
    expect(await text.locator('script, img, iframe, a, [onerror]').count()).toBe(0)
    expect(await text.locator('strong').innerText()).toBe('negrita')
    await page.waitForTimeout(500)
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined()
    expect(csp).toEqual([])
  })

  test('la cita de un documento que el usuario no puede leer muestra el mensaje del API (404), la UI no decide', async ({ page }) => {
    // fuente inventada con un UUID inexistente: el visor pide al API y este responde 404
    const fake = '11111111-1111-4111-8111-111111111111'
    await mockChat(page, (route) => route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([CONV, ['delta', { text: 'Según [D14].' }], ['sources', { sources: [{ documentId: fake, code: 'D14', title: 'Contrato', cited: true }] }], ['verification', { ok: true, citationsValid: true, citedCodes: ['D14'], unknownCitations: [], numbersChecked: 0, unsupportedNumbers: [] }], DONE]) }))
    await login(page, 'EMP002')
    await gotoChat(page)
    await ask(page, 'cita rara')
    await page.getByTestId('sources').getByRole('link', { name: /D14/ }).click()
    await expect(page.getByRole('alert')).toContainText('Documento no encontrado.')
  })

  test('verificación con cifras no respaldadas se muestra como aviso discreto pero visible', async ({ page }) => {
    await mockChat(page, (route) => route.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([CONV, ['delta', { text: 'Vendiste 7.777 €.' }], ['sources', { sources: [] }], ['verification', { ok: false, citationsValid: true, citedCodes: [], unknownCitations: [], numbersChecked: 1, unsupportedNumbers: ['7.777'] }], DONE]) }))
    await login(page, 'EMP003')
    await gotoChat(page)
    await ask(page, 'cifras')
    await expect(page.getByTestId('verification')).toContainText('cifras no respaldadas')
    await expect(page.getByTestId('verification')).toContainText('7.777')
  })

  test('accesibilidad: el log tiene aria-live y los controles tienen nombre accesible; Tab llega al cuadro de mensaje', async ({ page }) => {
    await login(page, 'EMP004')
    await gotoChat(page)
    await expect(page.getByRole('log', { name: 'Conversación' })).toHaveAttribute('aria-live', 'polite')
    await expect(page.getByRole('button', { name: 'Nueva conversación' })).toBeVisible()
    await expect(page.getByRole('form', { name: 'Mensaje al asistente' })).toBeVisible()
    await page.getByLabel('Pregunta al asistente').focus()
    await expect(page.getByLabel('Pregunta al asistente')).toBeFocused()
  })
})
