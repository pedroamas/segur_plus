import { defineConfig } from '@playwright/test'

// E2E contra el stack VIVO (https://192.168.0.28:5174, backend :8100, PostgreSQL y MinIO con los datos reales de F2).
// Usa el Chrome instalado en el sistema (no descarga navegadores). Sin trazas ni vídeo: no se guardan contraseñas tecleadas.
export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  workers: 1,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: 'test-results/e2e.json' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'https://192.168.0.28:5174',
    ignoreHTTPSErrors: true, // CA local de Caddy (tls internal)
    channel: 'chrome',
    headless: true,
    locale: 'es-ES',
    trace: 'off',
    video: 'off',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chrome', use: {} }],
})
