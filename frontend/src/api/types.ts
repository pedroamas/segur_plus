import type { components } from './schema'

type S = components['schemas']

/** springdoc no marca los campos como obligatorios: se exige el primer nivel (el backend siempre los envía). */
type Req<T> = { [K in keyof T]-?: T[K] }

export type UserProfile = Req<S['UserProfile']>
export type DocumentSummary = Req<S['DocumentSummary']>
export type DocumentDetail = Req<S['DocumentDetail']>
export type DocumentPage = Omit<Req<S['DocumentPage']>, 'items'> & { items: DocumentSummary[] }
export type ExcelPreview = { sheets: Req<S['Sheet']>[]; sheetsTruncated: boolean }
export type DashboardResponse = S['DashboardResponse']
export type Money = S['Money']

/** Filtros que SÍ soporta el API de listado (GET /documents): kb, category y fileType. */
export const KBS = ['GENERAL', 'DIRECCION', 'FINANZAS', 'EMPLEADOS'] as const
export const CATEGORIES = ['REPORT', 'STRATEGY', 'FINANCE', 'CONTRACT', 'PAYROLL', 'COMMERCIAL_ACTIVITY', 'MANUAL', 'POLICY'] as const
export const FILE_TYPES = ['PDF', 'XLSX', 'CSV', 'JSON'] as const

export type Role = NonNullable<S['UserOption']['role']>
export const ROLES: readonly Role[] = ['MANAGER', 'ACCOUNTANT', 'SALES_MANAGER', 'EMPLOYEE']
export const CLASSIFICATIONS = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'] as const
export type UserOption = Req<S['UserOption']>
export type UploadConfig = Req<S['UploadConfig']>

// ---- Chat (F5) ----
export type ConversationSummary = Req<S['ConversationSummary']>
export type MessageView = Omit<Req<S['MessageView']>, 'sources' | 'tools'> & {
  sources: Req<S['Source']>[]
  tools: Req<S['ToolUse']>[]
}
