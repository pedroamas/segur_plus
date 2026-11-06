import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { formatMonth, formatMoney, formatPercent } from '../api/format'
import type { DashboardResponse } from '../api/types'
import { ErrorBox, Loading, errorMessage } from '../components/Async'

const SCOPE_LABEL: Record<string, string> = {
  OWN: 'Tus cifras',
  TEAM: 'Cifras del equipo comercial',
  GLOBAL: 'Cifras globales',
}

function Figure({ label, value, id }: { label: string; value: string; id: string }) {
  return (
    <div className="figure">
      <dt>{label}</dt>
      <dd data-testid={id}>{value}</dd>
    </div>
  )
}

export default function DashboardPage() {
  const [month, setMonth] = useState<string | undefined>(undefined)
  const [data, setData] = useState<DashboardResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setError(null)
    api
      .getJson<DashboardResponse>(`/dashboard${month ? `?month=${encodeURIComponent(month)}` : ''}`)
      .then((d) => !cancelled && setData(d))
      .catch((e) => !cancelled && setError(errorMessage(e)))
    return () => {
      cancelled = true
    }
  }, [month])

  if (error) return <ErrorBox message={error} />
  if (!data) return <Loading />

  const { sales, commissions, policiesByStatus: pol, targets, expenses } = data
  return (
    <section aria-labelledby="dash-title">
      <h1 id="dash-title">Panel</h1>
      <div className="toolbar">
        <label htmlFor="month">Mes</label>
        <select id="month" value={data.month} onChange={(e) => setMonth(e.target.value)}>
          {(data.availableMonths ?? []).map((m) => (
            <option key={m} value={m}>{formatMonth(m)}</option>
          ))}
        </select>
        <span className="muted" data-testid="dash-scope" data-scope={data.scope}>{SCOPE_LABEL[data.scope ?? ''] ?? data.scope}</span>
      </div>

      <h2>Resumen de {data.month ? formatMonth(data.month) : ''}</h2>
      <dl className="figures">
        <Figure id="sales-count" label="Pólizas vendidas" value={String(sales?.policyCount ?? 0)} />
        <Figure id="premiums" label="Primas" value={formatMoney(sales?.premiums)} />
        <Figure id="commissions-accrued" label="Comisiones devengadas" value={formatMoney(commissions?.accrued)} />
        <Figure id="commissions-clawbacks" label="Devoluciones por cancelación" value={formatMoney(commissions?.clawbacks)} />
        <Figure id="commissions-net" label="Comisiones netas" value={formatMoney(commissions?.net)} />
        {expenses && <Figure id="expenses" label="Gastos del mes" value={formatMoney(expenses)} />}
      </dl>

      <h2>Pólizas por estado</h2>
      <dl className="figures">
        <Figure id="pol-active" label="Activas" value={String(pol?.active ?? 0)} />
        <Figure id="pol-cancelled" label="Canceladas" value={String(pol?.cancelled ?? 0)} />
        <Figure id="pol-expired" label="Vencidas" value={String(pol?.expired ?? 0)} />
      </dl>

      {targets && (
        <>
          <h2>Cumplimiento de objetivos</h2>
          <table data-testid="targets-table">
            <caption className="sr-only">Cumplimiento de objetivos por empleado</caption>
            <thead>
              <tr><th scope="col">Empleado</th><th scope="col" className="num">Objetivo</th><th scope="col" className="num">Primas</th><th scope="col" className="num">Cumplimiento</th></tr>
            </thead>
            <tbody>
              {(targets.employees ?? []).map((r) => (
                <tr key={r.employeeCode} data-testid={`target-${r.employeeCode}`}>
                  <th scope="row">{r.fullName} <span className="muted">({r.employeeCode})</span></th>
                  <td className="num">{formatMoney(r.target)}</td>
                  <td className="num">{formatMoney(r.premiums)}</td>
                  <td className="num" data-testid={`percent-${r.employeeCode}`}>{formatPercent(r.percent)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Total</th>
                <td className="num">{formatMoney(targets.target)}</td>
                <td className="num">{formatMoney(targets.premiums)}</td>
                <td className="num" data-testid="percent-total">{formatPercent(targets.percent)}</td>
              </tr>
            </tfoot>
          </table>
        </>
      )}
    </section>
  )
}
