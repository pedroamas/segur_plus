import type { Money } from './types'

/** Solo presentación: el importe llega del API como cadena decimal. */
export function formatMoney(m: Money | undefined): string {
  if (!m?.amount) return '—'
  return new Intl.NumberFormat('es-ES', { style: 'currency', currency: m.currency ?? 'EUR' }).format(Number(m.amount))
}

export function formatPercent(p: number | undefined | null): string {
  return p === undefined || p === null ? '—' : `${new Intl.NumberFormat('es-ES', { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(p)} %`
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre']
/** «2026-10» → «octubre de 2026». */
export function formatMonth(ym: string): string {
  const [y, m] = ym.split('-')
  return `${MONTHS[Number(m) - 1] ?? ym} de ${y}`
}
