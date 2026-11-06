/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// En desarrollo (npm run dev) /api se proxifica al backend del host; en producción lo hace Caddy (deploy/Caddyfile).
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:8100' } },
  // pdf.js (visor PDF) es un chunk propio de ~700 KB que solo se carga al abrir un PDF.
  build: { sourcemap: false, chunkSizeWarningLimit: 800 },
  test: { include: ['src/**/*.test.{ts,tsx}'], environment: 'node' },
})
