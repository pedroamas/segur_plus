import { lazy, Suspense, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import { formatBytes } from '../api/format'
import type { DocumentDetail } from '../api/types'
import { ErrorBox, Loading, errorMessage } from '../components/Async'
import ExcelPreviewView from '../components/ExcelPreviewView'

// pdf.js pesa ~700 KB: solo se descarga al abrir un PDF.
const PdfViewer = lazy(() => import('../components/PdfViewer'))

export default function DocumentDetailPage() {
  const { id = '' } = useParams()
  const [doc, setDoc] = useState<DocumentDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [downloadError, setDownloadError] = useState<string | null>(null)
  const [downloading, setDownloading] = useState(false)

  useEffect(() => {
    let cancelled = false
    setDoc(null)
    setError(null)
    api
      .getJson<DocumentDetail>(`/documents/${encodeURIComponent(id)}`)
      .then((d) => !cancelled && setDoc(d))
      .catch((e) => !cancelled && setError(errorMessage(e, 'Documento no encontrado.')))
    return () => {
      cancelled = true
    }
  }, [id])

  async function download() {
    setDownloading(true)
    setDownloadError(null)
    try {
      const { blob, filename } = await api.getBlob(`/documents/${encodeURIComponent(id)}/download`)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename ?? `${doc?.code ?? 'documento'}`
      document.body.appendChild(a)
      a.click()
      a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 10_000)
    } catch (e) {
      setDownloadError(errorMessage(e, 'Documento no encontrado.'))
    } finally {
      setDownloading(false)
    }
  }

  return (
    <section aria-labelledby="doc-title">
      <p><Link to="/documentos">← Volver a documentos</Link></p>
      {error && <ErrorBox message={error} />}
      {!doc && !error && <Loading />}
      {doc && (
        <>
          <h1 id="doc-title">{doc.title}</h1>
          <dl className="meta">
            <div><dt>Código</dt><dd data-testid="doc-code">{doc.code}</dd></div>
            <div><dt>Tipo</dt><dd>{doc.fileType}</dd></div>
            <div><dt>Tamaño</dt><dd>{formatBytes(doc.sizeBytes)}</dd></div>
          </dl>
          <p>
            <button type="button" onClick={download} disabled={downloading}>{downloading ? 'Descargando…' : 'Descargar'}</button>
          </p>
          {downloadError && <ErrorBox message={downloadError} />}
          {doc.viewable && (
            <Suspense fallback={<Loading what="Cargando visor…" />}>
              <PdfViewer id={doc.id} />
            </Suspense>
          )}
          {doc.previewable && <ExcelPreviewView id={doc.id} />}
          {!doc.viewable && !doc.previewable && <p className="muted">Este tipo de archivo no tiene vista previa; puedes descargarlo.</p>}
        </>
      )}
    </section>
  )
}
