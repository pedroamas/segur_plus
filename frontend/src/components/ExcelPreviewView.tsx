import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ExcelPreview } from '../api/types'
import { ErrorBox, Loading, errorMessage } from './Async'

/** Preview de Excel: el API devuelve texto plano ya calculado; se pinta como TEXTO (nunca como HTML), una tabla por hoja. */
export default function ExcelPreviewView({ id }: { id: string }) {
  const [data, setData] = useState<ExcelPreview | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api
      .getJson<ExcelPreview>(`/documents/${id}/preview`)
      .then((d) => !cancelled && setData(d))
      .catch((e) => !cancelled && setError(errorMessage(e, 'No se ha podido mostrar la vista previa.')))
    return () => {
      cancelled = true
    }
  }, [id])

  if (error) return <ErrorBox message={error} />
  if (!data) return <Loading what="Cargando vista previa…" />
  return (
    <div data-testid="excel-preview" data-sheets={data.sheets.length}>
      {data.sheetsTruncated && <p className="muted">Solo se muestran las primeras hojas del libro.</p>}
      {data.sheets.map((s) => (
        <section key={s.name} aria-label={`Hoja ${s.name}`} className="sheet">
          <h3>{s.name}</h3>
          <div className="table-scroll">
            <table data-testid="sheet-table">
              <tbody>
                {s.rows.map((row, r) => (
                  <tr key={r}>
                    {row.map((cell, c) => (r === 0 ? <th key={c} scope="col">{cell}</th> : <td key={c}>{cell}</td>))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {(s.rowsTruncated || s.columnsTruncated) && <p className="muted">Vista recortada: {s.rowsTruncated && 'filas'}{s.rowsTruncated && s.columnsTruncated && ' y '}{s.columnsTruncated && 'columnas'} omitidas. Descarga el archivo para verlo completo.</p>}
        </section>
      ))}
    </div>
  )
}
