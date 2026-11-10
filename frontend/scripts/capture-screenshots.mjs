// Capturas del README (F6): Playwright + Chrome del sistema contra el stack VIVO (https://192.168.0.28:5174) con los datos
// FICTICIOS del seed. NO gasta modelo: el chat se muestra abriendo una conversación YA guardada (la evaluación de F4/F6 las dejó
// con citas). La contraseña se lee de deploy/.env y se teclea en el formulario; nunca se imprime ni aparece en las capturas
// (el campo es de tipo password y la captura de login se toma ANTES de rellenarlo).
//   node scripts/capture-screenshots.mjs [directorio-de-salida]     (desde frontend/)
import { chromium } from '@playwright/test'
import { readFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(import.meta.dirname, '..', '..')
const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, 'docs', 'img'))
const BASE = process.env.E2E_BASE_URL ?? 'https://192.168.0.28:5174'
const env = Object.fromEntries(readFileSync(path.join(ROOT, 'deploy', '.env'), 'utf8').split('\n').map((l) => /^([A-Z0-9_]+)=(.*)$/.exec(l)).filter(Boolean).map((m) => [m[1], m[2]]))
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const ctx = await browser.newContext({ baseURL: BASE, ignoreHTTPSErrors: true, locale: 'es-ES', viewport: { width: 1280, height: 800 } })
const page = await ctx.newPage()

await page.goto('/login')
await page.getByLabel('Usuario (código de empleado)').waitFor()
await page.screenshot({ path: path.join(OUT, '01-login.png') })

async function login(code) {
  await page.goto('/login')
  await page.getByLabel('Usuario (código de empleado)').fill(code)
  await page.getByLabel('Contraseña').fill(env.SEGURPLUS_SEED_DEFAULT_PASSWORD)
  await page.getByRole('button', { name: 'Entrar' }).click()
  await page.getByRole('heading', { name: 'Panel', level: 1 }).waitFor()
}

await login('DIR001')
await page.waitForTimeout(2500)   // deja pintar las tarjetas del panel
await page.screenshot({ path: path.join(OUT, '02-dashboard-direccion.png') })

await page.getByRole('link', { name: 'Documentos' }).click()
await page.getByRole('heading', { name: 'Documentos', level: 1 }).waitFor()
await page.waitForTimeout(1500)
await page.screenshot({ path: path.join(OUT, '03-documentos-direccion.png') })

// Chat con citas: conversación guardada de DIR001 cuya respuesta cita el plan estratégico (D04)
await page.getByRole('link', { name: 'Chat' }).click()
await page.getByRole('heading', { name: 'Chat', level: 1 }).waitFor()
const items = page.getByTestId('conversations').getByRole('button')
const target = items.filter({ hasText: /plan estrat/i }).first()
await (await target.count() ? target : items.first()).click()
await page.getByTestId('msg-assistant').first().waitFor()
await page.waitForTimeout(1500)
await page.screenshot({ path: path.join(OUT, '04-chat-con-citas.png') })

// Vista de un empleado (solo ve lo suyo): documentos de EMP001
await page.getByRole('button', { name: /cerrar sesión|salir/i }).first().click().catch(() => {})
await ctx.clearCookies()
await login('EMP001')
await page.getByRole('link', { name: 'Documentos' }).click()
await page.getByRole('heading', { name: 'Documentos', level: 1 }).waitFor()
await page.waitForTimeout(1500)
await page.screenshot({ path: path.join(OUT, '05-documentos-empleado.png') })

await browser.close()
console.log('capturas en', OUT)
