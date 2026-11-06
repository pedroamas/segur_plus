import { expect, test, type Page } from '@playwright/test'
import { ALL_CODES, USERS, expectedCodes, login } from './support'

/** Recorre todas las páginas del listado del UI y devuelve los códigos mostrados. */
async function listedCodes(page: Page): Promise<string[]> {
  await page.goto('/documentos')
  await expect(page.getByTestId('docs-total')).toBeVisible()
  const codes: string[] = []
  for (;;) {
    await expect(page.getByTestId('doc-row').first()).toBeVisible()
    codes.push(...(await page.getByTestId('doc-row').evaluateAll((rows) => rows.map((r) => r.getAttribute('data-code') ?? ''))))
    const next = page.getByRole('button', { name: 'Siguiente' })
    if (await next.isDisabled()) break
    const before = await page.getByTestId('docs-page').textContent()
    await next.click()
    await expect(page.getByTestId('docs-page')).not.toHaveText(before ?? '')
  }
  return codes.sort()
}

test.describe('F3 criterio 2: la lista de documentos del UI coincide con la matriz', () => {
  for (const user of USERS) {
    test(`${user}: lista = oráculo del YAML`, async ({ page }) => {
      await login(page, user)
      const expected = expectedCodes(user)
      const codes = await listedCodes(page)
      expect(codes).toEqual(expected)
      expect(new Set(codes).size).toBe(codes.length) // sin duplicados entre páginas
      await expect(page.getByTestId('docs-total')).toHaveAttribute('data-total', String(expected.length))
      console.log(`${user}: ${codes.length} documentos en el UI = ${expected.length} en la matriz`)
    })
  }

  test('DIR001 ve los 32 y los filtros del API (kb, categoría, tipo) funcionan', async ({ page }) => {
    await login(page, 'DIR001')
    expect(await listedCodes(page)).toEqual(ALL_CODES)
    await page.getByLabel('Base de conocimiento').selectOption('FINANZAS')
    await expect(page.getByTestId('docs-total')).toHaveAttribute('data-total', '7')
    await page.getByLabel('Categoría').selectOption('REPORT')
    await expect(page.getByTestId('docs-total')).toHaveAttribute('data-total', '1') // D13
    await expect(page.getByTestId('doc-row')).toHaveAttribute('data-code', 'D13')
    await page.getByLabel('Base de conocimiento').selectOption('')
    await page.getByLabel('Categoría').selectOption('')
    await page.getByLabel('Tipo de archivo').selectOption('XLSX')
    const total = Number(await page.getByTestId('docs-total').getAttribute('data-total'))
    expect(total).toBeGreaterThan(0)
    expect(total).toBeLessThan(32)
  })

  test('el listado no ofrece búsqueda por título ni filtro por clasificación (no los soporta el API)', async ({ page }) => {
    await login(page, 'DIR001')
    await page.goto('/documentos')
    await expect(page.getByRole('searchbox')).toHaveCount(0)
    await expect(page.getByLabel('Clasificación')).toHaveCount(0)
  })
})
