import { ApiError } from '../api/client'

/** Mensaje en español para un error del API. No decide nada: solo traduce lo que devolvió. */
export function errorMessage(e: unknown, notFound = 'No se ha encontrado el recurso.'): string {
  if (e instanceof ApiError) {
    if (e.status === 404) return notFound
    if (e.status === 403) return 'No tienes permiso para esta operación.'
    if (e.status === 401) return 'Tu sesión ha caducado. Vuelve a iniciar sesión.'
    if (e.status === 400) return e.message
    if (e.status >= 500) return 'El servidor ha tenido un problema. Inténtalo de nuevo más tarde.'
    return e.message
  }
  return 'No se pudo conectar con el servidor.'
}

export function Loading({ what = 'Cargando…' }: { what?: string }) {
  return <p role="status" className="muted">{what}</p>
}

export function ErrorBox({ message }: { message: string }) {
  return <p role="alert" className="error">{message}</p>
}
