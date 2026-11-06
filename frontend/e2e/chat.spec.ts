import { expect, test } from '@playwright/test'
import { USERS, login } from './support'

test.describe('F3 criterio 6: el chat es visible pero no disponible y no hace peticiones de chat', () => {
  for (const user of ['DIR001', 'EMP001'] as const) {
    test(`${user}: pantalla de chat «no disponible», sin peticiones a /chat`, async ({ page }) => {
      const requests: string[] = []
      page.on('request', (r) => requests.push(new URL(r.url()).pathname))
      await login(page, user)
      await expect(page.getByRole('link', { name: /Chat/ })).toContainText('No disponible') // visible en la navegación
      await page.getByRole('link', { name: /Chat/ }).click()
      await expect(page).toHaveURL(/\/chat$/)
      await expect(page.getByTestId('chat-unavailable')).toContainText('no disponible')
      await expect(page.getByLabel('Pregunta al asistente')).toBeDisabled()
      await expect(page.getByRole('button', { name: 'Enviar' })).toBeDisabled()
      // también tras recargar directamente en /chat y esperando un rato
      await page.reload()
      await expect(page.getByTestId('chat-unavailable')).toBeVisible()
      await page.waitForTimeout(1500)
      expect(requests.filter((p) => /chat/i.test(p) && p.startsWith('/api'))).toEqual([])
      expect(requests.some((p) => p.startsWith('/api/'))).toBe(true) // sí hay API (login, users/me…), pero ninguna de chat
    })
  }
  test('los 7 usuarios ven el chat como no disponible', async ({ page }) => {
    const chatCalls: string[] = []
    page.on('request', (r) => /\/api\/v1\/chat/.test(r.url()) && chatCalls.push(r.url()))
    for (const user of USERS) {
      await login(page, user)
      await page.getByRole('link', { name: /Chat/ }).click()
      await expect(page.getByTestId('chat-unavailable')).toBeVisible()
      await page.getByRole('button', { name: 'Cerrar sesión' }).click()
      await expect(page.getByRole('button', { name: 'Entrar' })).toBeVisible()
    }
    expect(chatCalls).toEqual([])
  })
})
