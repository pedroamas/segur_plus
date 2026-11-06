import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { expect, type Page } from '@playwright/test'
import { parse } from 'yaml'

const ROOT = path.resolve(import.meta.dirname, '..', '..')

export const USERS = ['DIR001', 'ACC001', 'COM001', 'EMP001', 'EMP002', 'EMP003', 'EMP004'] as const
export type UserCode = (typeof USERS)[number]

/** Lee deploy/.env sin imprimirlo ni copiarlo a ningún sitio. */
export function readEnv(): Record<string, string> {
  const env: Record<string, string> = {}
  for (const line of readFileSync(path.join(ROOT, 'deploy', '.env'), 'utf8').split('\n')) {
    const m = /^([A-Z0-9_]+)=(.*)$/.exec(line)
    if (m) env[m[1]] = m[2]
  }
  return env
}

export const PASSWORD = readEnv().SEGURPLUS_SEED_DEFAULT_PASSWORD

export async function login(page: Page, code: string): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('Usuario (código de empleado)').fill(code)
  await page.getByLabel('Contraseña').fill(PASSWORD)
  await page.getByRole('button', { name: 'Entrar' }).click()
  await expect(page.getByRole('heading', { name: 'Panel', level: 1 })).toBeVisible()
}

interface SpecDoc {
  code: string
  kb: string
  cat: string
  owner: string | null
  allowed_roles: string[]
}
const spec = parse(readFileSync(path.join(ROOT, 'docs', 'data-model', 'permissions-spec.yaml'), 'utf8')) as {
  users: Record<string, { role: string }>
  documents: SpecDoc[]
}

/** Oráculo independiente (YAML): códigos que cada usuario puede leer = rol ∈ allowed_roles o propietario. */
export function expectedCodes(user: string): string[] {
  const role = spec.users[user].role
  return spec.documents.filter((d) => d.allowed_roles.includes(role) || d.owner === user).map((d) => d.code).sort()
}
export const ALL_CODES = spec.documents.map((d) => d.code).sort()

/** Oráculo independiente 2: consulta SQL directa a PostgreSQL (docker exec; usa el entorno del contenedor, sin secretos en el test). */
export function sql(query: string): string {
  return execFileSync(
    'docker',
    ['exec', '-e', 'Q', 'segurplus-postgres-1', 'sh', '-c', 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -c "$Q"'],
    { env: { ...process.env, Q: query }, encoding: 'utf8' },
  ).trim()
}
