import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { api } from '../api/client'
import { formatBytes } from '../api/format'
import { CATEGORY_LABEL, CLASSIFICATION_LABEL, KB_LABEL } from '../api/labels'
import { CATEGORIES, FILE_TYPES, KBS, type DocumentDetail, type DocumentPage } from '../api/types'
import UploadDialog from '../components/UploadDialog'
import { ErrorBox, Loading, errorMessage } from '../components/Async'

const PAGE_SIZE = 20

export default function DocumentsPage() {
  const [kb, setKb] = useState('')
  const [category, setCategory] = useState('')
  const [fileType, setFileType] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<DocumentPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { user } = useAuth()
  // Solo visibilidad del botón: la autoridad es el backend (un no-MANAGER recibiría 403 igualmente).
  const canUpload = user?.role === 'MANAGER'
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploaded, setUploaded] = useState<DocumentDetail | null>(null)
  const [reload, setReload] = useState(0)
  const uploadButton = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    let cancelled = false
    const q = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) })
    if (kb) q.set('kb', kb)
    if (category) q.set('category', category)
    if (fileType) q.set('fileType', fileType)
    setError(null)
    api
      .getJson<DocumentPage>(`/documents?${q}`)
      .then((d) => !cancelled && setData(d))
      .catch((e) => !cancelled && setError(errorMessage(e)))
    return () => {
      cancelled = true
    }
  }, [kb, category, fileType, page, reload])

  function filter(set: (v: string) => void) {
    return (e: React.ChangeEvent<HTMLSelectElement>) => {
      set(e.target.value)
      setPage(0)
    }
  }

  function closeUpload() {
    setUploadOpen(false)
    uploadButton.current?.focus()
  }

  /** Tras subir: se cierra el diálogo, se filtra por la KB/categoría/tipo del nuevo documento y se recarga el listado. */
  function onUploaded(doc: DocumentDetail) {
    setUploaded(doc)
    setKb(doc.kb)
    setCategory(doc.category)
    setFileType(doc.fileType)
    setPage(0)
    setReload((n) => n + 1)
    closeUpload()
  }

  return (
    <section aria-labelledby="docs-title">
      <div className="page-head">
        <h1 id="docs-title">Documentos</h1>
        {canUpload && (
          <button type="button" ref={uploadButton} onClick={() => setUploadOpen(true)} data-testid="upload-open">Subir documento</button>
        )}
      </div>
      {uploaded && (
        <p role="status" className="success-box" data-testid="upload-success">
          Documento <strong>{uploaded.code}</strong> subido correctamente. <Link to={`/documentos/${uploaded.id}`}>Abrirlo</Link>. Los filtros se han ajustado para mostrarlo.
        </p>
      )}
      {canUpload && uploadOpen && <UploadDialog onClose={closeUpload} onUploaded={onUploaded} />}
      <form className="toolbar" aria-label="Filtros" onSubmit={(e) => e.preventDefault()}>
        <label htmlFor="f-kb">Base de conocimiento</label>
        <select id="f-kb" value={kb} onChange={filter(setKb)}>
          <option value="">Todas</option>
          {KBS.map((k) => <option key={k} value={k}>{KB_LABEL[k]}</option>)}
        </select>
        <label htmlFor="f-cat">Categoría</label>
        <select id="f-cat" value={category} onChange={filter(setCategory)}>
          <option value="">Todas</option>
          {CATEGORIES.map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
        </select>
        <label htmlFor="f-type">Tipo de archivo</label>
        <select id="f-type" value={fileType} onChange={filter(setFileType)}>
          <option value="">Todos</option>
          {FILE_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
      </form>
      <p className="muted">El listado solo admite estos filtros; no hay búsqueda por título ni filtro por clasificación en el servidor.</p>

      {error && <ErrorBox message={error} />}
      {!data && !error && <Loading />}
      {data && (
        <>
          <p data-testid="docs-total" data-total={data.totalElements} aria-live="polite">{data.totalElements} documentos</p>
          <table data-testid="docs-table">
            <caption className="sr-only">Documentos a los que tienes acceso</caption>
            <thead>
              <tr>
                <th scope="col">Código</th><th scope="col">Título</th><th scope="col">Base</th><th scope="col">Categoría</th>
                <th scope="col">Clasificación</th><th scope="col">Tipo</th><th scope="col" className="num">Tamaño</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((d) => (
                <tr key={d.id} data-testid="doc-row" data-code={d.code}>
                  <td>{d.code}</td>
                  <th scope="row"><Link to={`/documentos/${d.id}`}>{d.title}</Link></th>
                  <td>{KB_LABEL[d.kb] ?? d.kb}</td>
                  <td>{CATEGORY_LABEL[d.category] ?? d.category}</td>
                  <td>{CLASSIFICATION_LABEL[d.classification] ?? d.classification}</td>
                  <td>{d.fileType}</td>
                  <td className="num">{formatBytes(d.sizeBytes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <nav className="pager" aria-label="Paginación">
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Anterior</button>
            <span data-testid="docs-page">Página {data.page + 1} de {Math.max(data.totalPages, 1)}</span>
            <button type="button" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Siguiente</button>
          </nav>
        </>
      )}
    </section>
  )
}
