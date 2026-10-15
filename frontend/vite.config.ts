import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    // Build straight into the Spring Boot static classpath (served on :9090).
    // emptyOutDir stays false: the legacy vanilla app still lives there and a
    // later task removes it. Hashed assets go under the default assets/.
    outDir: '../api/src/main/resources/static',
    emptyOutDir: false,
  },
  server: {
    proxy: {
      // El backend evalúa CORS cuando el pedido trae `Origin`, y el navegador lo
      // manda en TODO POST/PUT aunque sea same-origin (el spec sólo lo omite en
      // GET/HEAD). Contra el backend directo eso no se nota —en producción el
      // bundle lo sirve él mismo, así que el pedido es del mismo origen— pero
      // detrás de este proxy el navegador ve `localhost:5173` y el backend
      // responde 403 "Invalid CORS request" en cualquier escritura: el panel
      // quedaba inservible en desarrollo. Sin el header, el pedido se ve como el
      // no-CORS que realmente es en producción.
      '/api': {
        target: 'http://localhost:9090',
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'));
        },
      },
      '/ws': {
        target: 'http://localhost:9090',
        ws: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './tests/setup.ts',
    include: ['tests/**/*.test.{ts,tsx}'],
  },
})
