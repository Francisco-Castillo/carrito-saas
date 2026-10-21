import { defineConfig, devices } from "@playwright/test";

/**
 * E2E responsive del panel admin (odd/tasks/admin-responsive.md).
 *
 * - webServer: levanta el dev server de Vite (proxy /api → :9090). El backend
 *   NO corre en estos tests: la sesión es fake (localStorage con token y slug)
 *   y las vistas muestran sus estados vacíos/de error, que es lo que hay que
 *   testear responsive — el layout con datos reales es problema del seed.
 * - Los specs viven en e2e/ con sufijo .spec.ts para que vitest (que includea
 *   tests con sufijo .test.ts/.test.tsx) no los levante dos veces.
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  retries: 0,
  use: {
    baseURL: "http://localhost:5173",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "mobile", use: { ...devices["Pixel 7"] } },
    { name: "tablet", use: { viewport: { width: 768, height: 1024 } } },
    { name: "desktop", use: { viewport: { width: 1280, height: 800 } } },
  ],
  webServer: {
    command: "npm run dev",
    url: "http://localhost:5173",
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
