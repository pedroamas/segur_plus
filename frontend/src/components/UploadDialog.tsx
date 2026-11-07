import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { api } from '../api/client'
import { formatBytes } from '../api/format'
import { CATEGORY_LABEL, CLASSIFICATION_LABEL, KB_LABEL, ROLE_LABEL } from '../api/labels'
import { CATEGORIES, CLASSIFICATIONS, KBS, type DocumentDetail, type Role, type UploadConfig, type UserOption } from '../api/types'
import {
  DEFAULT_EXTENSIONS, DEFAULT_MAX_BYTES, TITLE_MAX, describeServerError, validateUpload, type FieldErrors, type ServerProblem,
  type UploadValues,
} from './uploadValidation'

/** MANAGER siempre queda en allowed_roles (lo añade el servidor); solo se eligen los demás. */
const OPTIONAL_ROLES: Role[] = ['ACCOUNTANT', 'SALES_MANAGER', 'EMPLOYEE']

interface Props {
  onClose(): void
  onUploaded(doc: DocumentDetail): void
}

/**
 * Diálogo modal de subida. Solo se monta para MANAGER (la página decide qué mostrar), pero la autoridad es el backend:
 * un 403 se enseña tal cual. Un <dialog> nativo da foco atrapado, Esc y fondo inerte sin scripts ni estilos en línea.
 */
export default function UploadDialog({ onClose, onUploaded }: Props) {
  const uid = useId()
  const dialogRef = useRef<HTMLDialogElement>(null)
  const alertRef = useRef<HTMLDivElement>(null)
  const submitting = useRef(false)
  const [busy, setBusy] = useState(false)
  const [config, setConfig] = useState<UploadConfig | null>(null)
  const [users, setUsers] = useState<UserOption[] | null>(null)
  const [usersError, setUsersError] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [values, setValues] = useState({ code: '', title: '', kb: '', category: '', classification: '', ownerId: '' })
  const [roles, setRoles] = useState<Role[]>([])
  const [errors, setErrors] = useState<FieldErrors>({})
  const [problem, setProblem] = useState<ServerProblem | null>(null)

  const extensions = config?.allowedExtensions ?? DEFAULT_EXTENSIONS
  const maxBytes = config?.maxBytes ?? DEFAULT_MAX_BYTES

  useEffect(() => {
    const d = dialogRef.current
    if (d && !d.open) {
      if (typeof d.showModal === 'function') d.showModal()
      else d.setAttribute('open', '')
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    api.getJson<UploadConfig>('/documents/upload-config').then((c) => !cancelled && setConfig(c)).catch(() => undefined)
    api.getJson<UserOption[]>('/users').then((u) => !cancelled && setUsers(u)).catch(() => !cancelled && setUsersError(true))
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (problem) alertRef.current?.focus()
  }, [problem])

  function set<K extends keyof typeof values>(key: K, value: string) {
    setValues((v) => ({ ...v, [key]: value }))
    setErrors((e) => ({ ...e, [key]: undefined }))
    setProblem(null)
  }

  function toggleRole(r: Role) {
    setRoles((cur) => (cur.includes(r) ? cur.filter((x) => x !== r) : [...cur, r]))
    setProblem(null)
  }

  async function submit(ev: FormEvent) {
    ev.preventDefault()
    if (submitting.current) return // no permite doble envío
    const current: UploadValues = { file, code: values.code, title: values.title, kb: values.kb, category: values.category, classification: values.classification }
    const found = validateUpload(current, extensions, maxBytes)
    setErrors(found)
    setProblem(null)
    if (Object.keys(found).length > 0) {
      const first = Object.keys(found)[0]
      dialogRef.current?.querySelector<HTMLElement>(`[data-field="${first}"]`)?.focus()
      return
    }
    submitting.current = true
    setBusy(true)
    try {
      const form = new FormData()
      form.append('file', file as File)
      form.append('code', values.code)
      form.append('title', values.title.trim())
      form.append('kb', values.kb)
      form.append('category', values.category)
      form.append('classification', values.classification)
      if (values.ownerId) form.append('ownerId', values.ownerId)
      roles.forEach((r) => form.append('allowedRoles', r))
      const created = await api.postForm<DocumentDetail>('/documents', form)
      onUploaded(created)
    } catch (e) {
      const p = describeServerError(e)
      setProblem(p)
      if (p.field) setErrors((cur) => ({ ...cur, [p.field as keyof UploadValues]: p.summary }))
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  const id = (n: string) => `${uid}-${n}`
  const err = (n: keyof UploadValues) => errors[n]
  const field = (n: keyof UploadValues) => ({
    id: id(n), 'data-field': n, 'aria-invalid': err(n) ? true : undefined, 'aria-describedby': err(n) ? id(`${n}-err`) : undefined,
  })
  const fieldError = (n: keyof UploadValues) => err(n) && <p id={id(`${n}-err`)} className="field-error">{err(n)}</p>

  return (
    <dialog
      ref={dialogRef}
      className="upload-dialog"
      aria-labelledby={id('title')}
      onCancel={(e) => {
        e.preventDefault() // Esc: se cierra solo si no hay subida en curso
        if (!busy) onClose()
      }}
    >
      <form onSubmit={submit} noValidate aria-busy={busy} aria-label="Subir documento">
        <h2 id={id('title')}>Subir documento</h2>
        <p className="muted" data-testid="upload-limits">
          Formatos admitidos: {extensions.map((x) => `.${x}`).join(', ')}. Tamaño máximo: {formatBytes(maxBytes)}. El servidor comprueba el contenido real.
        </p>

        {problem && (
          <div ref={alertRef} tabIndex={-1} role="alert" className="error-box" data-testid="upload-error">
            <strong>{problem.summary}</strong>
            {problem.detail && <p>Respuesta del servidor: {problem.detail}</p>}
            {problem.correlationId && <p className="muted">ID de correlación: {problem.correlationId}</p>}
          </div>
        )}

        <div className="form-row">
          <label htmlFor={id('file')}>Archivo</label>
          <input
            {...field('file')} type="file" accept={extensions.map((x) => `.${x}`).join(',')}
            onChange={(e) => { setFile(e.target.files?.[0] ?? null); setErrors((c) => ({ ...c, file: undefined })); setProblem(null) }}
          />
          {file && <p className="muted">{file.name} · {formatBytes(file.size)}</p>}
          {fieldError('file')}
        </div>

        <div className="form-row">
          <label htmlFor={id('code')}>Código</label>
          <input
            {...field('code')} value={values.code} maxLength={8} autoComplete="off"
            onChange={(e) => set('code', e.target.value.toUpperCase())}
          />
          <p className="muted">2-8 caracteres, A-Z y 0-9. Debe ser único.</p>
          {fieldError('code')}
        </div>

        <div className="form-row">
          <label htmlFor={id('title-in')}>Título</label>
          <input
            {...field('title')} id={id('title-in')} aria-describedby={err('title') ? id('title-err') : undefined}
            value={values.title} maxLength={TITLE_MAX} onChange={(e) => set('title', e.target.value)}
          />
          {fieldError('title')}
        </div>

        <div className="form-grid">
          <div className="form-row">
            <label htmlFor={id('kb')}>Base de conocimiento</label>
            <select {...field('kb')} value={values.kb} onChange={(e) => set('kb', e.target.value)}>
              <option value="">Selecciona…</option>
              {KBS.map((k) => <option key={k} value={k}>{KB_LABEL[k]}</option>)}
            </select>
            {fieldError('kb')}
          </div>
          <div className="form-row">
            <label htmlFor={id('category')}>Categoría</label>
            <select {...field('category')} value={values.category} onChange={(e) => set('category', e.target.value)}>
              <option value="">Selecciona…</option>
              {CATEGORIES.map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
            </select>
            {fieldError('category')}
          </div>
          <div className="form-row">
            <label htmlFor={id('classification')}>Clasificación</label>
            <select {...field('classification')} value={values.classification} onChange={(e) => set('classification', e.target.value)}>
              <option value="">Selecciona…</option>
              {CLASSIFICATIONS.map((c) => <option key={c} value={c}>{CLASSIFICATION_LABEL[c]}</option>)}
            </select>
            {fieldError('classification')}
          </div>
        </div>

        <div className="form-row">
          <label htmlFor={id('owner')}>Propietario (opcional)</label>
          <select id={id('owner')} value={values.ownerId} onChange={(e) => set('ownerId', e.target.value)} disabled={!users}>
            <option value="">Sin propietario</option>
            {users?.map((u) => <option key={u.id} value={u.id}>{u.employeeCode} · {u.fullName}</option>)}
          </select>
          {usersError && <p className="muted">No se pudo cargar la lista de usuarios; se subirá sin propietario.</p>}
          <p className="muted">Los documentos de la base «Empleados» necesitan propietario.</p>
        </div>

        <fieldset className="form-row">
          <legend>Roles que pueden leerlo</legend>
          <label className="check"><input type="checkbox" checked disabled /> {ROLE_LABEL.MANAGER} (siempre incluido)</label>
          {OPTIONAL_ROLES.map((r) => (
            <label key={r} className="check">
              <input type="checkbox" checked={roles.includes(r)} onChange={() => toggleRole(r)} /> {ROLE_LABEL[r]}
            </label>
          ))}
          <p className="muted">El servidor valida la combinación con la clasificación y el propietario.</p>
        </fieldset>

        <div className="dialog-actions">
          <button type="button" className="secondary" onClick={onClose} disabled={busy}>Cancelar</button>
          <button type="submit" disabled={busy}>{busy ? 'Subiendo…' : 'Subir'}</button>
        </div>
        {busy && <p role="status" className="muted">Subiendo el documento…</p>}
      </form>
    </dialog>
  )
}
