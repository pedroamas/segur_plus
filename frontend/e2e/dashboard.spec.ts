import { expect, test } from '@playwright/test'
import { USERS, login, sql } from './support'

const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ').trim()
const EMPLOYEES = ['EMP001', 'EMP002', 'EMP003', 'EMP004']
const OCT_PREMIUMS: Record<string, string> = { EMP001: '3.150,00 €', EMP002: '2.280,00 €', EMP003: '2.050,00 €', EMP004: '2.200,00 €' }
const OCT_PERCENT: Record<string, string> = { EMP001: '105,0 %', EMP002: '76,0 %', EMP003: '73,2 %', EMP004: '68,8 %' }

/** Cifras de octubre calculadas directamente en PostgreSQL para el alcance de cada usuario (oráculo independiente del backend). */
function dbOctober(user: string) {
  const scope =
    user.startsWith('EMP') ? `p.employee_id = (select id from users where employee_code = '${user}')`
    : user === 'COM001' ? `p.employee_id in (select id from users where role = 'EMPLOYEE')`
    : 'true'
  const cscope = scope.replaceAll('p.employee_id', 'c.employee_id')
  const [count, premiums] = sql(
    `select count(*), coalesce(sum(premium),0) from policies p where sale_date between '2026-10-01' and '2026-10-31' and ${scope}`,
  ).split('|')
  const accrued = sql(`select coalesce(sum(amount),0) from commissions c where period_month = '2026-10-01' and kind='ACCRUAL' and ${cscope}`)
  return { count, premiums, accrued }
}
const eur = (n: string) => norm(new Intl.NumberFormat('es-ES', { style: 'currency', currency: 'EUR', useGrouping: 'always' }).format(Number(n)))

test.describe('F3 criterio 1: los 7 usuarios ven el dashboard de su rol, sin cifras ajenas', () => {
  for (const user of USERS) {
    test(`dashboard de ${user} (octubre de 2026)`, async ({ page }) => {
      await login(page, user)
      await page.getByLabel('Mes').selectOption('2026-10')
      await expect(page.getByTestId('premiums')).toBeVisible()
      const expected = dbOctober(user)
      // cifras = base de datos para ese alcance
      await expect(page.getByTestId('sales-count')).toHaveText(expected.count)
      expect(norm(await page.getByTestId('premiums').textContent())).toBe(eur(expected.premiums))
      expect(norm(await page.getByTestId('commissions-accrued').textContent())).toBe(eur(expected.accrued))

      const scope = await page.getByTestId('dash-scope').getAttribute('data-scope')
      const body = norm(await page.locator('main').innerText())

      if (user === 'DIR001' || user === 'COM001') {
        expect(scope).toBe(user === 'DIR001' ? 'GLOBAL' : 'TEAM')
        // anclas del brief: 12 pólizas, 9.680,00 €, 968,00 €, 105,0/76,0/73,2/68,8 % y 80,7 %
        await expect(page.getByTestId('sales-count')).toHaveText('12')
        expect(norm(await page.getByTestId('premiums').textContent())).toBe('9.680,00 €')
        expect(norm(await page.getByTestId('commissions-accrued').textContent())).toBe('968,00 €')
        for (const e of EMPLOYEES) {
          expect(norm(await page.getByTestId(`percent-${e}`).textContent())).toBe(OCT_PERCENT[e])
          expect(norm(await page.getByTestId(`target-${e}`).innerText())).toContain(OCT_PREMIUMS[e])
        }
        expect(norm(await page.getByTestId('percent-total').textContent())).toBe('80,7 %')
        await expect(page.getByTestId('expenses')).toHaveCount(0)
      } else if (user === 'ACC001') {
        expect(scope).toBe('GLOBAL')
        await expect(page.getByTestId('sales-count')).toHaveText('12')
        expect(norm(await page.getByTestId('expenses').textContent())).toBe('7.250,00 €')
        await expect(page.getByTestId('targets-table')).toHaveCount(0) // sin cumplimiento por empleado
        for (const e of EMPLOYEES) expect(body).not.toContain(e)
      } else {
        expect(scope).toBe('OWN')
        await expect(page.getByTestId('sales-count')).toHaveText('3')
        expect(norm(await page.getByTestId('premiums').textContent())).toBe(OCT_PREMIUMS[user])
        expect(norm(await page.getByTestId(`percent-${user}`).textContent())).toBe(OCT_PERCENT[user])
        await expect(page.getByTestId('targets-table').locator('tbody tr')).toHaveCount(1)
        await expect(page.getByTestId('expenses')).toHaveCount(0)
        // ninguna cifra ni código de otro empleado
        for (const other of EMPLOYEES.filter((e) => e !== user)) {
          expect(body).not.toContain(other)
          expect(body).not.toContain(OCT_PREMIUMS[other])
          expect(body).not.toContain(OCT_PERCENT[other])
        }
        expect(body).not.toContain('9.680,00')
        expect(body).not.toContain('80,7 %')
      }
      expect(body.toLowerCase()).not.toContain('salario')
      expect(body.toLowerCase()).not.toContain('salary')
    })
  }

  test('mes por defecto: el último mes cerrado (diciembre de 2026)', async ({ page }) => {
    await login(page, 'DIR001')
    await expect(page.getByLabel('Mes')).toHaveValue('2026-12')
    await expect(page.getByLabel('Mes').locator('option')).toHaveCount(4)
  })
})
