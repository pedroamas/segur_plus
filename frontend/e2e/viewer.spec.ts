import { statSync } from 'node:fs'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { PASSWORD, login } from './support'

async function openFirstOfType(page: Page, type: 'PDF' | 'XLSX'): Promise<void> {
  await page.goto('/documentos')
  await page.getByLabel('Tipo de archivo').selectOption(type)
  await expect(page.getByTestId('doc-row').first()).toBeVisible()
  await page.getByTestId('doc-row').first().getByRole('link').click()
  await expect(page.getByTestId('doc-code')).toBeVisible()
}

/** Id de un documento por código, obtenido por API con el usuario indicado (solo para preparar el escenario del test). */
async function docIdByCode(ctx: APIRequestContext, as: string, code: string): Promise<string> {
  const res = await ctx.post('/api/v1/auth/login', { data: { username: as, password: PASSWORD } })
  const token = (await res.json()).accessToken as string
  const list = await (await ctx.get('/api/v1/documents?size=100', { headers: { Authorization: `Bearer ${token}` } })).json()
  return list.items.find((d: { code: string }) => d.code === code).id
}

test.describe('F3 criterio 3: PDF y Excel se visualizan y la descarga respeta la matriz', () => {
  test('el PDF se dibuja con pdf.js (canvas con contenido)', async ({ page }) => {
    const errors: string[] = []
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
    await login(page, 'DIR001')
    await openFirstOfType(page, 'PDF')
    const viewer = page.getByTestId('pdf-viewer')
    await expect(viewer).toHaveAttribute('data-state', 'ready', { timeout: 30_000 })
    const canvases = viewer.locator('canvas')
    expect(await canvases.count()).toBeGreaterThan(0)
    // el canvas no está en blanco: hay píxeles no blancos
    const nonWhite = await canvases.first().evaluate((c: HTMLCanvasElement) => {
      const d = c.getContext('2d')!.getImageData(0, 0, c.width, c.height).data
      let n = 0
      for (let i = 0; i < d.length; i += 4) if (d[i] < 200 || d[i + 1] < 200 || d[i + 2] < 200) n++
      return n
    })
    expect(nonWhite).toBeGreaterThan(500)
    expect(errors.filter((e) => /Content Security Policy|CSP/i.test(e))).toEqual([])
  })

  test('el Excel se muestra como tablas por hoja con valores de texto', async ({ page }) => {
    await login(page, 'DIR001')
    await openFirstOfType(page, 'XLSX')
    const preview = page.getByTestId('excel-preview')
    await expect(preview).toBeVisible()
    expect(Number(await preview.getAttribute('data-sheets'))).toBeGreaterThan(0)
    const cells = await page.getByTestId('sheet-table').first().locator('td').count()
    expect(cells).toBeGreaterThan(5)
    await expect(preview.locator('script, iframe')).toHaveCount(0)
  })

  test('descarga permitida (DIR001, PDF y XLSX): llega un archivo con contenido', async ({ page }) => {
    await login(page, 'DIR001')
    for (const type of ['PDF', 'XLSX'] as const) {
      await openFirstOfType(page, type)
      const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Descargar' }).click()])
      expect(statSync(await download.path()).size).toBeGreaterThan(1000)
      expect(download.suggestedFilename().toLowerCase()).toMatch(type === 'PDF' ? /\.pdf$/ : /\.xlsx$/)
    }
  })

  test('descarga permitida a un propietario sobre su documento RESTRICTED (EMP001 → D16 nómina)', async ({ page, request }) => {
    await login(page, 'EMP001')
    const id = await docIdByCode(request, 'EMP001', 'D16')
    await page.goto(`/documentos/${id}`)
    await expect(page.getByTestId('doc-code')).toHaveText('D16')
    const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Descargar' }).click()])
    expect(statSync(await download.path()).size).toBeGreaterThan(1000)
  })

  test('documento ajeno (EMP001 → D17 de EMP002 y D01 de Dirección): mensaje de no encontrado, sin visor ni descarga', async ({ page, request }) => {
    const ids = { D17: await docIdByCode(request, 'DIR001', 'D17'), D01: await docIdByCode(request, 'DIR001', 'D01') }
    await login(page, 'EMP001')
    for (const code of ['D17', 'D01'] as const) {
      await page.goto(`/documentos/${ids[code]}`)
      await expect(page.getByRole('alert')).toContainText('Documento no encontrado')
      await expect(page.getByRole('button', { name: 'Descargar' })).toHaveCount(0)
      await expect(page.getByTestId('pdf-viewer')).toHaveCount(0)
      await expect(page.getByTestId('excel-preview')).toHaveCount(0)
    }
    // y el endpoint de descarga/vista/preview responde 404 igual (misma respuesta que un id inexistente)
    const ctx = request
    const token = (await (await ctx.post('/api/v1/auth/login', { data: { username: 'EMP001', password: PASSWORD } })).json()).accessToken
    for (const suffix of ['', '/view', '/preview', '/download']) {
      const res = await ctx.get(`/api/v1/documents/${ids.D17}${suffix}`, { headers: { Authorization: `Bearer ${token}` } })
      expect(res.status(), `D17${suffix}`).toBe(404)
    }
    const nonexistent = await ctx.get('/api/v1/documents/00000000-0000-0000-0000-000000000000/download', { headers: { Authorization: `Bearer ${token}` } })
    expect(nonexistent.status()).toBe(404)
  })
})
