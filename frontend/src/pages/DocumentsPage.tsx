import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { formatBytes } from '../api/format'
import { CATEGORIES, FILE_TYPES, KBS, type DocumentPage } from '../api/types'
import { ErrorBox, Loading, errorMessage } from '../components/Async'

const PAGE_SIZE = 20

const KB_LABEL: Record<string, string> = { GENERAL: 'General', DIRECCION: 'Dirección', FINANZAS: 'Finanzas', EMPLEADOS: 'Empleados' }
const CATEGORY_LABEL: Record<string, string> = {
  REPORT: 'Informe', STRATEGY: 'Estrategia', FINANCE: 'Finanzas', CONTRACT: 'Contrato', PAYROLL: 'Nómina',
  COMMERCIAL_ACTIVITY: 'Actividad comercial', MANUAL: 'Manual', POLICY: 'Política',
}
const CLASSIFICATION_LABEL: Record<string, string> = { PUBLIC: 'Pública', INTERNAL: 'Interna', CONFIDENTIAL: 'Confidencial', RESTRICTED: 'Restringida' }

export default function DocumentsPage() {
  const [kb, setKb] = useState('')
  const [category, setCategory] = useState('')
  const [fileType, setFileType] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<DocumentPage | null>(null)
  const [error, setError] = useState<string | null>(null)

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
  }, [kb, category, fileType, page])

  function filter(set: (v: string) => void) {
    return (e: React.ChangeEvent<HTMLSelectElement>) => {
      set(e.target.value)
      setPage(0)
    }
  }

  return (
    <section aria-labelledby="docs-title">
      <h1 id="docs-title">Documentos</h1>
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
