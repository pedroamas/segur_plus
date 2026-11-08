import { createHash, createHmac } from 'node:crypto'
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

// ---- Limpieza de documentos subidos por los E2E (código TE2E*): fila en BD, objeto en MinIO y filas de auditoría ----

const BUCKET = 'segurplus-documents'
const MINIO_URL = process.env.E2E_MINIO_URL ?? 'http://localhost:9000'

const hmac = (key: Buffer | string, data: string) => createHmac('sha256', key).update(data).digest()
const sha = (data: string) => createHash('sha256').update(data).digest('hex')

/** Petición S3 firmada (SigV4, path-style) con las credenciales de deploy/.env; no se imprimen ni se guardan. */
async function minioRequest(method: 'DELETE' | 'HEAD', key: string): Promise<number> {
  const env = readEnv()
  const url = new URL(`${MINIO_URL}/${BUCKET}/${key}`)
  const now = new Date().toISOString().replace(/[:-]|\.\d{3}/g, '')
  const day = now.slice(0, 8)
  const empty = sha('')
  const headers = `host:${url.host}\nx-amz-content-sha256:${empty}\nx-amz-date:${now}\n`
  const signed = 'host;x-amz-content-sha256;x-amz-date'
  const canonical = [method, url.pathname, '', headers, signed, empty].join('\n')
  const scope = `${day}/us-east-1/s3/aws4_request`
  const toSign = ['AWS4-HMAC-SHA256', now, scope, sha(canonical)].join('\n')
  const k = hmac(hmac(hmac(hmac(`AWS4${env.MINIO_ROOT_PASSWORD}`, day), 'us-east-1'), 's3'), 'aws4_request')
  const signature = createHmac('sha256', k).update(toSign).digest('hex')
  const res = await fetch(url, {
    method,
    headers: {
      'x-amz-date': now,
      'x-amz-content-sha256': empty,
      Authorization: `AWS4-HMAC-SHA256 Credential=${env.MINIO_ROOT_USER}/${scope}, SignedHeaders=${signed}, Signature=${signature}`,
    },
  })
  return res.status
}

export const minioObjectExists = async (key: string) => (await minioRequest('HEAD', key)) === 200

/** Borra de BD, MinIO y auditoría todo documento cuyo código empiece por TE2E. Idempotente. */
export async function cleanupE2eDocuments(): Promise<void> {
  const rows = sql("SELECT id || '|' || storage_key FROM documents WHERE code LIKE 'TE2E%'").split('\n').filter(Boolean)
  for (const row of rows) {
    const [id, key] = row.split('|')
    await minioRequest('DELETE', key)
    sql(`DELETE FROM audit_logs WHERE resource_id = '${id}'`)
    sql(`DELETE FROM documents WHERE id = '${id}'`)
  }
}

// ---- Conversaciones de chat creadas por los E2E: se limpian por diferencia con una foto previa (las de la evaluación F4 no se tocan) ----

export const conversationIds = (): Set<string> => new Set(sql('SELECT id FROM conversations').split('\n').filter(Boolean))

/** Borra de BD (mensajes, auditoría y conversación) las conversaciones que no estaban en `before`. Idempotente. */
export function cleanupE2eConversations(before: Set<string>): number {
  const created = [...conversationIds()].filter((id) => !before.has(id))
  for (const id of created) {
    sql(`DELETE FROM messages WHERE conversation_id = '${id}'`)
    sql(`DELETE FROM audit_logs WHERE resource_id = '${id}'`)
    sql(`DELETE FROM conversations WHERE id = '${id}'`)
  }
  return created.length
}

/** Conversaciones vivas (sin borrado lógico) de un usuario, con título. */
export function liveConversations(user: string): { id: string; title: string }[] {
  return sql(`SELECT c.id || '|' || coalesce(c.title,'') FROM conversations c JOIN users u ON u.id = c.user_id WHERE u.employee_code = '${user}' AND c.deleted_at IS NULL`)
    .split('\n')
    .filter(Boolean)
    .map((r) => {
      const i = r.indexOf('|')
      return { id: r.slice(0, i), title: r.slice(i + 1) }
    })
}
