import { expect, test, type Page } from '@playwright/test'
import { ALL_CODES, cleanupE2eDocuments, login, minioObjectExists, sql } from './support'

/** PDF mínimo pero válido para el backend (firma %PDF- y %%EOF; tipo real comprobado en el servidor). */
const PDF = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n')
const CODE = 'TE2E1'

async function openUpload(page: Page) {
  await page.goto('/documentos')
  await expect(page.getByTestId('docs-total')).toBeVisible()
  await page.getByRole('button', { name: 'Subir documento' }).click()
  const dialog = page.getByRole('dialog', { name: 'Subir documento' })
  await expect(dialog).toBeVisible()
  return dialog
}

async function fill(dialog: ReturnType<Page['getByRole']>, o: { code: string; file?: { name: string; mimeType: string; buffer: Buffer }; classification?: string }) {
  await dialog.getByLabel('Archivo').setInputFiles(o.file ?? { name: 'prueba-e2e.pdf', mimeType: 'application/pdf', buffer: PDF })
  await dialog.getByLabel('Código').fill(o.code)
  await dialog.getByLabel('Título').fill('Documento de prueba E2E')
  await dialog.getByLabel('Base de conocimiento').selectOption('GENERAL')
  await dialog.getByLabel('Categoría').selectOption('MANUAL')
  await dialog.getByLabel('Clasificación').selectOption(o.classification ?? 'INTERNAL')
}

const countDocs = () => Number(sql('SELECT count(*) FROM documents'))

test.describe('F3b: subida de documentos (solo MANAGER)', () => {
  test.beforeAll(cleanupE2eDocuments)
  test.afterAll(cleanupE2eDocuments)

  test('DIR001 sube un PDF, aparece en la lista y queda en BD y MinIO; la limpieza deja los 32 documentos', async ({ page }) => {
    expect(countDocs()).toBe(32)
    await login(page, 'DIR001')
    const dialog = await openUpload(page)
    await expect(dialog.getByTestId('upload-limits')).toContainText('.pdf, .xlsx, .csv, .json')
    await expect(dialog.getByTestId('upload-limits')).toContainText('Tamaño máximo: 10.0 MB')
    await expect(dialog.getByRole('option', { name: /EMP001 ·/ })).toHaveCount(1) // selector de propietario poblado desde GET /users
    await fill(dialog, { code: CODE })
    await dialog.getByRole('checkbox', { name: 'Empleado' }).check()
    await dialog.getByRole('button', { name: 'Subir', exact: true }).click()

    await expect(page.getByTestId('upload-success')).toContainText(CODE)
    await expect(dialog).toBeHidden()
    await expect(page.locator(`[data-testid="doc-row"][data-code="${CODE}"]`)).toBeVisible()

    const row = sql(`SELECT file_type, kb, category, classification, allowed_roles, storage_key FROM documents WHERE code = '${CODE}'`)
    const [fileType, kb, category, classification, roles, key] = row.split('|')
    expect([fileType, kb, category, classification]).toEqual(['PDF', 'GENERAL', 'MANUAL', 'INTERNAL'])
    expect(roles).toContain('MANAGER')
    expect(roles).toContain('EMPLOYEE')
    expect(await minioObjectExists(key)).toBe(true)

    // El documento nuevo se abre desde el aviso y su visor funciona.
    await page.getByTestId('upload-success').getByRole('link', { name: 'Abrirlo' }).click()
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Documento de prueba E2E')

    await cleanupE2eDocuments()
    expect(await minioObjectExists(key)).toBe(false)
    expect(countDocs()).toBe(32)
    expect(sql(`SELECT count(*) FROM documents WHERE code = '${CODE}'`)).toBe('0')
  })

  for (const user of ['ACC001', 'COM001', 'EMP001']) {
    test(`${user} no ve el botón «Subir documento»`, async ({ page }) => {
      await login(page, user)
      await page.goto('/documentos')
      await expect(page.getByTestId('docs-total')).toBeVisible()
      await expect(page.getByRole('button', { name: 'Subir documento' })).toHaveCount(0)
    })
  }

  test('código duplicado: el 409 del servidor se muestra y no se crea nada', async ({ page }) => {
    const before = countDocs()
    await login(page, 'DIR001')
    const dialog = await openUpload(page)
    await fill(dialog, { code: ALL_CODES[0] }) // código que ya existe en el dataset
    await dialog.getByRole('button', { name: 'Subir', exact: true }).click()
    const alert = dialog.getByTestId('upload-error')
    await expect(alert).toContainText('Ya existe un documento con ese código')
    await expect(alert).toContainText('A document with this code already exists.')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Subir', exact: true })).toBeEnabled()
    expect(countDocs()).toBe(before)
  })

  test('tipo no permitido: contenido que no es PDF con extensión .pdf ⇒ 415 del servidor; .exe ⇒ bloqueado en cliente', async ({ page }) => {
    const before = countDocs()
    await login(page, 'DIR001')
    const dialog = await openUpload(page)
    await fill(dialog, { code: 'TE2E2', file: { name: 'falso.pdf', mimeType: 'application/pdf', buffer: Buffer.from('esto no es un pdf') } })
    await dialog.getByRole('button', { name: 'Subir', exact: true }).click()
    const alert = dialog.getByTestId('upload-error')
    await expect(alert).toContainText('Tipo de archivo no permitido')
    await expect(alert).toContainText('The file extension does not match the actual file content.')

    await dialog.getByLabel('Archivo').setInputFiles({ name: 'programa.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('MZ') })
    await dialog.getByRole('button', { name: 'Subir', exact: true }).click()
    await expect(dialog.getByText(/Tipo no admitido/)).toBeVisible()
    expect(countDocs()).toBe(before)
  })

  test('tamaño: un archivo mayor que el máximo se rechaza antes de enviarlo', async ({ page }) => {
    const before = countDocs()
    await login(page, 'DIR001')
    const dialog = await openUpload(page)
    await fill(dialog, { code: 'TE2E3', file: { name: 'enorme.pdf', mimeType: 'application/pdf', buffer: Buffer.alloc(10 * 1024 * 1024 + 1, 1) } })
    await dialog.getByRole('button', { name: 'Subir', exact: true }).click()
    await expect(dialog.getByText(/el máximo es 10.0 MB/)).toBeVisible()
    expect(countDocs()).toBe(before)
  })

  test('combinación inválida (RESTRICTED + rol Empleado): el 422 del servidor se muestra', async ({ page }) => {
    const before = countDocs()
    await login(page, 'DIR001')
    const dialog = await openUpload(page)
    await fill(dialog, { code: 'TE2E4', classification: 'RESTRICTED' })
    await dialog.getByRole('checkbox', { name: 'Empleado' }).check()
    await dialog.getByRole('button', { name: 'Subir', exact: true }).click()
    const alert = dialog.getByTestId('upload-error')
    await expect(alert).toContainText('combinación de clasificación, roles permitidos y propietario')
    await expect(alert).toContainText('RESTRICTED documents cannot be readable by the EMPLOYEE role.')
    expect(countDocs()).toBe(before)
  })
})
