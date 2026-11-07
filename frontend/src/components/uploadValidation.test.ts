import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { describeServerError, validateUpload, type UploadValues } from './uploadValidation'

const EXT = ['pdf', 'xlsx', 'csv', 'json']
const MAX = 1000
const file = (name: string, size: number) => new File([new Uint8Array(size)], name)
const ok: UploadValues = { file: file('a.pdf', 10), code: 'TX1', title: 'Título', kb: 'GENERAL', category: 'MANUAL', classification: 'INTERNAL' }

describe('validateUpload (comodidad del cliente; el backend decide)', () => {
  it('acepta un formulario completo', () => expect(validateUpload(ok, EXT, MAX)).toEqual({}))
  it('exige archivo y campos obligatorios', () => {
    const e = validateUpload({ file: null, code: '', title: '  ', kb: '', category: '', classification: '' }, EXT, MAX)
    expect(Object.keys(e).sort()).toEqual(['category', 'classification', 'code', 'file', 'kb', 'title'])
  })
  it('rechaza extensión no admitida, vacío y exceso de tamaño', () => {
    expect(validateUpload({ ...ok, file: file('a.exe', 10) }, EXT, MAX).file).toMatch(/Tipo no admitido/)
    expect(validateUpload({ ...ok, file: file('a.pdf', 0) }, EXT, MAX).file).toMatch(/vacío/)
    expect(validateUpload({ ...ok, file: file('a.pdf', 1001) }, EXT, MAX).file).toMatch(/máximo/)
  })
  it('valida el formato del código y la longitud del título', () => {
    expect(validateUpload({ ...ok, code: 'a' }, EXT, MAX).code).toBeDefined()
    expect(validateUpload({ ...ok, code: 'ABCDEFGHI' }, EXT, MAX).code).toBeDefined()
    expect(validateUpload({ ...ok, code: 'T-1' }, EXT, MAX).code).toBeDefined()
    expect(validateUpload({ ...ok, title: 'x'.repeat(201) }, EXT, MAX).title).toBeDefined()
  })
})

describe('describeServerError', () => {
  const err = (status: number, detail: string) => new ApiError(status, detail, { detail, correlationId: 'c-1' })
  it('traduce 409, 413, 415 y 422 conservando el detalle del servidor', () => {
    expect(describeServerError(err(409, 'A document with this code already exists.'))).toMatchObject({ field: 'code', detail: 'A document with this code already exists.', correlationId: 'c-1' })
    expect(describeServerError(err(413, 'too big')).field).toBe('file')
    expect(describeServerError(err(415, 'bad type')).field).toBe('file')
    expect(describeServerError(err(422, 'RESTRICTED')).summary).toMatch(/combinación/)
    expect(describeServerError(err(403, 'x')).summary).toMatch(/permiso/)
  })
  it('un fallo de red no es un ApiError', () => expect(describeServerError(new TypeError('x')).summary).toMatch(/conectar/))
})
