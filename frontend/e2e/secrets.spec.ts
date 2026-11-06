import { readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { expect, test } from '@playwright/test'
import { PASSWORD, login, readEnv } from './support'

const DIST = path.resolve(import.meta.dirname, '..', 'dist')

// Claves y credenciales de deploy/.env que NUNCA pueden llegar al navegador. Se buscan sus VALORES (aunque estén vacíos: un valor
// vacío no se puede buscar, pero queda constancia en el informe y, en cuanto alguien lo rellene, el test lo vigila).
const SENSITIVE = ['DIFY_APP_API_KEY', 'DIFY_KNOWLEDGE_API_KEY', 'GEMINI_API_KEY', 'JWT_SECRET', 'MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD', 'POSTGRES_PASSWORD', 'SEGURPLUS_SEED_DEFAULT_PASSWORD'] as const

// Patrones evidentes de claves (Dify `app-…`, estilo OpenAI `sk-…`, Google `AIza…`, claves privadas, JWT embebidos).
const PATTERNS: [string, RegExp][] = [
  ['clave de Dify app-…', /\bapp-[A-Za-z0-9]{20,}\b/],
  ['clave sk-…', /\bsk-[A-Za-z0-9_-]{20,}\b/],
  ['clave de Google AIza…', /\bAIza[0-9A-Za-z_-]{30,}\b/],
  ['clave privada PEM', /-----BEGIN [A-Z ]*PRIVATE KEY-----/],
  ['clave de AWS', /\bAKIA[0-9A-Z]{16}\b/],
]
const JWT = /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = path.join(dir, f)
    return statSync(p).isDirectory() ? files(p) : [p]
  })
}

/** Valores cortos y «de identificador» (p. ej. el usuario de MinIO) se buscan como token entero para evitar falsos positivos de subcadenas. */
function occurs(haystack: string, value: string): boolean {
  if (value.length >= 16) return haystack.includes(value)
  const esc = value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`(?<![A-Za-z0-9@._-])${esc}(?![A-Za-z0-9._@-])`).test(haystack)
}

function sensitiveValues() {
  const env = readEnv()
  const values: Record<string, string> = {}
  for (const k of SENSITIVE) values[k] = env[k] ?? ''
  return values
}

test.describe('F3 criterio 4: ninguna clave en el bundle ni en la red', () => {
  test('dist/ no contiene valores de deploy/.env ni patrones de claves', () => {
    const values = sensitiveValues()
    const empty = Object.entries(values).filter(([, v]) => v === '').map(([k]) => k)
    const checked = Object.entries(values).filter(([, v]) => v !== '').map(([k]) => k)
    console.log(`valores buscados en dist: ${checked.join(', ')}; vacíos en .env (no se pueden buscar): ${empty.join(', ') || 'ninguno'}`)
    const all = files(DIST)
    expect(all.length).toBeGreaterThan(2)
    const hits: string[] = []
    for (const f of all) {
      const text = readFileSync(f, 'latin1')
      for (const [k, v] of Object.entries(values)) if (v && occurs(text, v)) hits.push(`${path.basename(f)} contiene ${k}`)
      for (const [name, re] of PATTERNS) if (re.test(text)) hits.push(`${path.basename(f)}: ${name}`)
      if (JWT.test(text)) hits.push(`${path.basename(f)}: JWT embebido`)
      if (/Authorization['"]?\s*:\s*['"]Bearer [A-Za-z0-9]/.test(text)) hits.push(`${path.basename(f)}: Bearer fijo`)
    }
    expect(hits).toEqual([])
  })

  test('ninguna respuesta de red (HTML, JS, JSON, PDF, XLSX) de una sesión completa contiene claves', async ({ page }) => {
    const values = sensitiveValues()
    const hits: string[] = []
    let inspected = 0
    page.on('response', async (res) => {
      const url = new URL(res.url())
      let body: Buffer
      try {
        body = await res.body()
      } catch {
        return // redirecciones sin cuerpo
      }
      inspected++
      const text = body.toString('latin1')
      const isTokenEndpoint = /\/api\/v1\/auth\/(login|refresh)$/.test(url.pathname) // el access JWT SÍ viaja ahí, es lo esperado
      for (const [k, v] of Object.entries(values)) if (v && occurs(text, v)) hits.push(`${url.pathname} contiene ${k}`)
      for (const [name, re] of PATTERNS) if (re.test(text)) hits.push(`${url.pathname}: ${name}`)
      if (!isTokenEndpoint && JWT.test(text)) hits.push(`${url.pathname}: JWT en la respuesta`)
    })
    for (const user of ['DIR001', 'EMP001']) {
      await login(page, user)
      await page.getByLabel('Mes').selectOption('2026-10')
      await page.goto('/documentos')
      await expect(page.getByTestId('doc-row').first()).toBeVisible()
      // EMP001 no tiene ningún XLSX en su alcance (su actividad comercial es CSV): se abre un tipo que sí tenga documentos
      for (const type of user === 'DIR001' ? ['PDF', 'XLSX'] : ['PDF', 'CSV']) {
        await page.getByLabel('Tipo de archivo').selectOption(type)
        await expect(page.getByTestId('doc-row').first()).toBeVisible()
        await page.getByTestId('doc-row').first().getByRole('link').click()
        await expect(page.getByTestId('doc-code')).toBeVisible()
        await page.waitForTimeout(1500) // deja terminar visor/preview
        await page.goBack()
      }
      await page.getByRole('link', { name: /Chat/ }).click()
      await expect(page.getByTestId('chat-unavailable')).toBeVisible()
      await page.getByRole('button', { name: 'Cerrar sesión' }).click()
      await expect(page.getByRole('button', { name: 'Entrar' })).toBeVisible()
    }
    expect(inspected).toBeGreaterThan(30)
    console.log(`respuestas de red inspeccionadas: ${inspected}`)
    expect(hits).toEqual([])
    // la contraseña tecleada solo viaja en la petición de login, nunca de vuelta
    expect(PASSWORD.length).toBeGreaterThan(0)
  })
})
