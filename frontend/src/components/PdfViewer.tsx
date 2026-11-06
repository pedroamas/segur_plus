import * as pdfjs from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { ErrorBox, Loading, errorMessage } from './Async'

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl

/** Visor PDF con pdf.js: descarga `/view` con el access token (en memoria) y dibuja cada página en un canvas. */
export default function PdfViewer({ id }: { id: string }) {
  const container = useRef<HTMLDivElement>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState('')
  const [pages, setPages] = useState(0)

  useEffect(() => {
    let cancelled = false
    let task: pdfjs.PDFDocumentLoadingTask | undefined
    setState('loading')
    ;(async () => {
      try {
        const { blob } = await api.getBlob(`/documents/${id}/view`)
        const data = new Uint8Array(await blob.arrayBuffer())
        task = pdfjs.getDocument({ data })
        const doc = await task.promise
        if (cancelled || !container.current) return
        container.current.replaceChildren()
        setPages(doc.numPages)
        for (let n = 1; n <= doc.numPages; n++) {
          const page = await doc.getPage(n)
          if (cancelled || !container.current) return
          const viewport = page.getViewport({ scale: 1.3 })
          const canvas = document.createElement('canvas')
          canvas.width = Math.floor(viewport.width)
          canvas.height = Math.floor(viewport.height)
          canvas.className = 'pdf-page'
          canvas.setAttribute('role', 'img')
          canvas.setAttribute('aria-label', `Página ${n} de ${doc.numPages}`)
          container.current.appendChild(canvas)
          await page.render({ canvas, viewport }).promise
        }
        if (!cancelled) setState('ready')
      } catch (e) {
        if (!cancelled) {
          setError(errorMessage(e, 'No se ha podido mostrar el PDF.'))
          setState('error')
        }
      }
    })()
    return () => {
      cancelled = true
      void task?.destroy()
    }
  }, [id])

  return (
    <div>
      {state === 'loading' && <Loading what="Cargando PDF…" />}
      {state === 'error' && <ErrorBox message={error} />}
      <div ref={container} className="pdf-pages" data-testid="pdf-viewer" data-state={state} data-pages={pages} />
    </div>
  )
}
