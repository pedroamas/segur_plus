import { ApiError } from '../api/client'
import { formatBytes } from '../api/format'

/** Valores por defecto mientras llega la configuración del servidor (que es la autoridad). */
export const DEFAULT_EXTENSIONS = ['pdf', 'xlsx', 'csv', 'json']
export const DEFAULT_MAX_BYTES = 10 * 1024 * 1024
export const CODE_PATTERN = /^[A-Z0-9]{2,8}$/
export const TITLE_MAX = 200

export interface UploadValues {
  file: File | null
  code: string
  title: string
  kb: string
  category: string
  classification: string
}
export type FieldErrors = Partial<Record<keyof UploadValues, string>>

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

/** Validación de comodidad en el cliente. El backend decide siempre (tipo real, tamaño real, reglas de metadatos). */
export function validateUpload(v: UploadValues, extensions: string[], maxBytes: number): FieldErrors {
  const e: FieldErrors = {}
  if (!v.file) e.file = 'Selecciona un archivo.'
  else if (!extensions.includes(extensionOf(v.file.name))) e.file = `Tipo no admitido. Formatos permitidos: ${extensions.map((x) => `.${x}`).join(', ')}.`
  else if (v.file.size === 0) e.file = 'El archivo está vacío.'
  else if (v.file.size > maxBytes) e.file = `El archivo pesa ${formatBytes(v.file.size)} y el máximo es ${formatBytes(maxBytes)}.`
  if (!CODE_PATTERN.test(v.code)) e.code = 'El código debe tener de 2 a 8 caracteres (A-Z, 0-9).'
  if (!v.title.trim()) e.title = 'El título es obligatorio.'
  else if (v.title.length > TITLE_MAX) e.title = `El título admite como máximo ${TITLE_MAX} caracteres.`
  if (!v.kb) e.kb = 'Selecciona la base de conocimiento.'
  if (!v.category) e.category = 'Selecciona la categoría.'
  if (!v.classification) e.classification = 'Selecciona la clasificación.'
  return e
}

export interface ServerProblem {
  /** Explicación en español según el estado HTTP. */
  summary: string
  /** Texto exacto del ProblemDetail del servidor (la autoridad). */
  detail?: string
  correlationId?: string
  /** Campo del formulario al que apunta el error, si se puede deducir del estado. */
  field?: keyof UploadValues
}

/** Traduce un ProblemDetail del backend; nunca lo oculta: el detalle original se muestra siempre. */
export function describeServerError(e: unknown): ServerProblem {
  if (!(e instanceof ApiError)) return { summary: 'No se pudo conectar con el servidor. Inténtalo de nuevo.' }
  const detail = e.problem?.detail ?? e.problem?.title
  const base = { detail, correlationId: e.problem?.correlationId }
  switch (e.status) {
    case 409: return { ...base, field: 'code', summary: 'Ya existe un documento con ese código. Elige otro código.' }
    case 413: return { ...base, field: 'file', summary: 'El archivo supera el tamaño máximo permitido por el servidor.' }
    case 415: return { ...base, field: 'file', summary: 'Tipo de archivo no permitido: el servidor comprueba el contenido real y que coincida con la extensión.' }
    case 422: return { ...base, summary: 'El servidor ha rechazado los datos: revisa la combinación de clasificación, roles permitidos y propietario.' }
    case 403: return { ...base, summary: 'No tienes permiso para subir documentos.' }
    case 401: return { ...base, summary: 'Tu sesión ha caducado. Vuelve a iniciar sesión.' }
    case 400: return { ...base, summary: 'El servidor no ha aceptado la petición: revisa los campos.' }
    default: return { ...base, summary: e.status >= 500 ? 'El servidor ha tenido un problema. Inténtalo de nuevo más tarde.' : `Error ${e.status}.` }
  }
}
