import { expect, test } from '@playwright/test'
import { PASSWORD, login } from './support'

const REFRESH = '/api/v1/auth/refresh'

test.describe('F3 criterio 5: sesión, renovación automática y logout', () => {
  test('la cookie de refresh es HttpOnly, Secure, SameSite=Strict y de ruta acotada; el access token no se guarda en el navegador', async ({ page, context }) => {
    await login(page, 'DIR001')
    const cookie = (await context.cookies()).find((c) => c.name === 'segurplus_refresh')
    expect(cookie, 'cookie de refresh presente tras el login').toBeTruthy()
    expect(cookie!.httpOnly).toBe(true)
    expect(cookie!.secure).toBe(true)
    expect(cookie!.sameSite).toBe('Strict')
    expect(cookie!.path).toBe('/api/v1/auth')
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }) + document.cookie)
    expect(stored).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}/) // ningún JWT
    expect(stored).not.toContain('segurplus_refresh')
  })

  test('recargar la página restaura la sesión por refresh (sin pedir login)', async ({ page }) => {
    await login(page, 'COM001')
    const refreshed = page.waitForResponse((r) => r.url().endsWith(REFRESH) && r.status() === 200)
    await page.reload()
    await refreshed
    await expect(page.getByRole('heading', { name: 'Panel', level: 1 })).toBeVisible()
    await expect(page.getByTestId('user-name')).not.toBeEmpty()
  })

  test('access token invalidado: el backend responde 401, la UI renueva por refresh y reintenta sin pedir login', async ({ page }) => {
    await login(page, 'DIR001')
    // Se corrompe el Bearer de la PRÓXIMA petición a /documents: el 401 lo da el backend real (firma inválida).
    let corrupted = 0
    await page.route('**/api/v1/documents?**', async (route) => {
      if (corrupted++ === 0) {
        await route.continue({ headers: { ...route.request().headers(), authorization: 'Bearer invalido.invalido.invalido' } })
      } else {
        await route.continue()
      }
    })
    const statuses: number[] = []
    page.on('response', (r) => {
      if (r.url().includes('/api/v1/documents?')) statuses.push(r.status())
      if (r.url().endsWith(REFRESH)) statuses.push(-r.status())
    })
    await page.getByRole('link', { name: 'Documentos' }).click()
    await expect(page.getByTestId('doc-row').first()).toBeVisible()
    await expect(page.getByTestId('docs-total')).toHaveAttribute('data-total', '32')
    expect(statuses).toEqual([401, -200, 200]) // 401 → refresh 200 → reintento 200 (una sola vez)
    await expect(page).toHaveURL(/\/documentos$/)
  })

  test('tras el logout no se puede renovar: la cookie vieja ya no sirve y la sesión no se restaura', async ({ page, context }) => {
    await login(page, 'EMP002')
    const before = (await context.cookies()).find((c) => c.name === 'segurplus_refresh')!.value
    await page.getByRole('button', { name: 'Cerrar sesión' }).click()
    await expect(page.getByRole('button', { name: 'Entrar' })).toBeVisible()
    // el navegador ya no conserva la cookie
    expect((await context.cookies()).find((c) => c.name === 'segurplus_refresh' && c.value !== '')).toBeUndefined()
    // aunque alguien la hubiera copiado, el servidor la rechaza
    const replay = await page.context().request.post(REFRESH, { headers: { Cookie: `segurplus_refresh=${before}` } })
    expect(replay.status()).toBe(401)
    // recargar o visitar una pantalla protegida lleva al login, sin sesión
    await page.goto('/documentos')
    await expect(page).toHaveURL(/\/login$/)
    await page.reload()
    await expect(page.getByRole('button', { name: 'Entrar' })).toBeVisible()
  })

  test('credenciales incorrectas: mensaje de error y sin sesión', async ({ page }) => {
    await page.goto('/login')
    await page.getByLabel('Usuario (código de empleado)').fill('EMP001')
    await page.getByLabel('Contraseña').fill(PASSWORD + 'x')
    await page.getByRole('button', { name: 'Entrar' }).click()
    await expect(page.getByRole('alert')).toContainText('Usuario o contraseña incorrectos')
    await expect(page).toHaveURL(/\/login$/)
  })
})
