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
      '/api': 'http://localhost:9090',
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
