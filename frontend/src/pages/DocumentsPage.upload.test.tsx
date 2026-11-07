// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { DocumentDetail, DocumentPage } from '../api/types'

const h = vi.hoisted(() => ({ role: 'MANAGER', getJson: vi.fn(), postForm: vi.fn() }))
vi.mock('../api/client', async (orig) => ({ ...(await orig<typeof import('../api/client')>()), api: { getJson: h.getJson, postForm: h.postForm } }))
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ user: { role: h.role, fullName: 'Test' } }) }))

import DocumentsPage from './DocumentsPage'

const EMPTY: DocumentPage = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }
const CREATED = {
  id: '11111111-1111-1111-1111-111111111111', code: 'TUP1', title: 'Subido', fileType: 'PDF', category: 'MANUAL', kb: 'GENERAL',
  classification: 'INTERNAL', sizeBytes: 5,
} as DocumentDetail
const USERS = [{ id: 'u-1', employeeCode: 'EMP001', fullName: 'Ana Pérez', role: 'EMPLOYEE' }]

function routes(path: string) {
  if (path.startsWith('/documents/upload-config')) return Promise.resolve({ maxBytes: 1024, allowedExtensions: ['pdf', 'xlsx', 'csv', 'json'] })
  if (path === '/users') return Promise.resolve(USERS)
  if (path.startsWith('/documents?')) return Promise.resolve(EMPTY)
  return Promise.reject(new ApiError(404, 'no'))
}

function renderPage() {
  return render(<MemoryRouter><DocumentsPage /></MemoryRouter>)
}

async function openDialog() {
  const user = userEvent.setup()
  renderPage()
  await user.click(await screen.findByRole('button', { name: 'Subir documento' }))
  const dialog = await screen.findByRole('dialog', { name: 'Subir documento' })
  return { user, dialog }
}

async function fillValid(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement, code = 'TUP1') {
  await user.upload(within(dialog).getByLabelText('Archivo'), new File(['%PDF-1.4 %%EOF'], 'prueba.pdf', { type: 'application/pdf' }))
  await user.type(within(dialog).getByLabelText('Código'), code)
  await user.type(within(dialog).getByLabelText('Título'), 'Subido')
  await user.selectOptions(within(dialog).getByLabelText('Base de conocimiento'), 'GENERAL')
  await user.selectOptions(within(dialog).getByLabelText('Categoría'), 'MANUAL')
  await user.selectOptions(within(dialog).getByLabelText('Clasificación'), 'INTERNAL')
}

beforeEach(() => {
  h.role = 'MANAGER'
  h.getJson.mockReset().mockImplementation(routes)
  h.postForm.mockReset()
})
afterEach(cleanup)

describe('Documentos: visibilidad del botón «Subir documento» por rol', () => {
  it('MANAGER lo ve', async () => {
    renderPage()
    expect(await screen.findByRole('button', { name: 'Subir documento' })).toBeInTheDocument()
  })
  for (const role of ['ACCOUNTANT', 'SALES_MANAGER', 'EMPLOYEE']) {
    it(`${role} no lo ve y no se piden usuarios ni límites`, async () => {
      h.role = role
      renderPage()
      await screen.findByTestId('docs-total')
      expect(screen.queryByRole('button', { name: 'Subir documento' })).toBeNull()
      expect(h.getJson.mock.calls.map((c) => String(c[0])).filter((p) => p === '/users' || p.includes('upload-config'))).toEqual([])
    })
  }
})

describe('Diálogo de subida', () => {
  it('muestra formatos y tamaño máximo que da el servidor, y la lista de usuarios para el propietario', async () => {
    const { dialog } = await openDialog()
    await waitFor(() => expect(within(dialog).getByTestId('upload-limits')).toHaveTextContent('Tamaño máximo: 1.0 KB'))
    expect(within(dialog).getByTestId('upload-limits')).toHaveTextContent('.pdf, .xlsx, .csv, .json')
    expect(await within(dialog).findByRole('option', { name: /EMP001 · Ana Pérez/ })).toBeInTheDocument()
    expect(within(dialog).getByRole('checkbox', { name: /Dirección/ })).toBeDisabled()
  })

  it('con campos vacíos muestra errores de cliente y no envía nada', async () => {
    const { user, dialog } = await openDialog()
    await user.click(within(dialog).getByRole('button', { name: 'Subir' }))
    expect(await within(dialog).findByText('Selecciona un archivo.')).toBeInTheDocument()
    expect(within(dialog).getByText('El título es obligatorio.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Código')).toHaveAttribute('aria-invalid', 'true')
    expect(h.postForm).not.toHaveBeenCalled()
  })

  it('rechaza en cliente un archivo demasiado grande y uno de tipo no admitido', async () => {
    const { user, dialog } = await openDialog()
    await waitFor(() => expect(within(dialog).getByTestId('upload-limits')).toHaveTextContent('1.0 KB'))
    await user.upload(within(dialog).getByLabelText('Archivo'), new File([new Uint8Array(2048)], 'grande.pdf'))
    await user.click(within(dialog).getByRole('button', { name: 'Subir' }))
    expect(await within(dialog).findByText(/el máximo es 1.0 KB/)).toBeInTheDocument()
    expect(h.postForm).not.toHaveBeenCalled()
  })

  it('envía multipart con los campos y los roles elegidos, cierra el diálogo, avisa y recarga el listado', async () => {
    h.postForm.mockResolvedValue(CREATED)
    const { user, dialog } = await openDialog()
    await fillValid(user, dialog)
    await user.selectOptions(within(dialog).getByLabelText('Propietario (opcional)'), 'u-1')
    await user.click(within(dialog).getByRole('checkbox', { name: /Contabilidad/ }))
    const listCallsBefore = h.getJson.mock.calls.filter((c) => String(c[0]).startsWith('/documents?')).length
    await user.click(within(dialog).getByRole('button', { name: 'Subir' }))

    await waitFor(() => expect(h.postForm).toHaveBeenCalledTimes(1))
    const [path, form] = h.postForm.mock.calls[0] as [string, FormData]
    expect(path).toBe('/documents')
    expect(form.get('code')).toBe('TUP1')
    expect(form.get('title')).toBe('Subido')
    expect(form.get('kb')).toBe('GENERAL')
    expect(form.get('category')).toBe('MANUAL')
    expect(form.get('classification')).toBe('INTERNAL')
    expect(form.get('ownerId')).toBe('u-1')
    expect(form.getAll('allowedRoles')).toEqual(['ACCOUNTANT'])
    expect((form.get('file') as File).name).toBe('prueba.pdf')

    expect(await screen.findByTestId('upload-success')).toHaveTextContent('TUP1')
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(h.getJson.mock.calls.filter((c) => String(c[0]).startsWith('/documents?')).length).toBeGreaterThan(listCallsBefore))
    expect(String(h.getJson.mock.calls.filter((c) => String(c[0]).startsWith('/documents?')).at(-1)?.[0])).toContain('kb=GENERAL')
  })

  it('no permite el doble envío mientras la subida está en curso', async () => {
    let finish: (d: DocumentDetail) => void = () => undefined
    h.postForm.mockReturnValue(new Promise<DocumentDetail>((res) => { finish = res }))
    const { user, dialog } = await openDialog()
    await fillValid(user, dialog)
    const submit = within(dialog).getByRole('button', { name: 'Subir' })
    await user.click(submit)
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Subiendo…' })).toBeDisabled())
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    await user.dblClick(within(dialog).getByRole('button', { name: 'Subiendo…' }))
    expect(h.postForm).toHaveBeenCalledTimes(1)
    finish(CREATED)
    await screen.findByTestId('upload-success')
  })

  const serverErrors: [number, string, RegExp][] = [
    [409, 'A document with this code already exists.', /Ya existe un documento con ese código/],
    [415, 'The file extension does not match the actual file content.', /Tipo de archivo no permitido/],
    [413, 'The uploaded file exceeds the maximum allowed size.', /supera el tamaño máximo/],
    [422, 'RESTRICTED documents cannot be readable by the EMPLOYEE role.', /combinación de clasificación, roles permitidos y propietario/],
  ]
  for (const [status, detail, summary] of serverErrors) {
    it(`muestra el ProblemDetail del servidor (${status}), conserva el formulario y permite reintentar`, async () => {
      h.postForm.mockRejectedValueOnce(new ApiError(status, detail, { detail, status, correlationId: 'corr-9' }))
      const { user, dialog } = await openDialog()
      await fillValid(user, dialog)
      await user.click(within(dialog).getByRole('button', { name: 'Subir' }))
      const alert = await within(dialog).findByTestId('upload-error')
      expect(alert).toHaveTextContent(summary)
      expect(alert).toHaveTextContent(detail)
      expect(alert).toHaveTextContent('corr-9')
      expect(within(dialog).getByLabelText('Título')).toHaveValue('Subido')
      expect(within(dialog).getByRole('button', { name: 'Subir' })).toBeEnabled()
      expect(screen.queryByTestId('upload-success')).toBeNull()
    })
  }
})
