import { test, expect } from "@playwright/test";

// e2e de las URLs por slug en navegador real (odd/tasks/urls-por-slug.md):
// 1) /{slug} a secas = carta pública del local (sin sesión).
// 2) OWNER logueado → aterriza en /{slug}/admin; /admin viejo redirige ahí.
// 3) Deep link /{slug}/admin con sesión del mismo negocio renderiza el panel.
// 4) Deep link de otro negocio → re-login (el backend sólo sirve el token).
// 5) El operador ADMIN sigue en /platform.
test("urls por slug: el negocio vive en la URL", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));

  // 1) Carta pública sin sesión en /{slug} a secas.
  await page.goto("http://localhost:5173/demo-e2e");
  await page.waitForTimeout(1500);
  console.log("1) /demo-e2e (sin sesión) →", page.url());
  if (page.url().includes("/login")) throw new Error("la carta pública pidió login: " + page.url());

  // 2) Login OWNER → /demo-e2e/admin
  await page.goto("http://localhost:5173/login");
  await page.fill('input[name="username"], #username, input[type="text"]', "e2e-admin");
  await page.fill('input[type="password"]', "admin123");
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1500);
  console.log("2) OWNER tras login:", page.url());
  if (!page.url().includes("/demo-e2e/admin")) throw new Error("OWNER no aterrizó en /demo-e2e/admin: " + page.url());

  // 3) Legacy /admin → redirige a /demo-e2e/admin (bookmark viejo)
  await page.goto("http://localhost:5173/admin");
  await page.waitForTimeout(1200);
  console.log("3) legacy /admin →", page.url());
  if (!page.url().includes("/demo-e2e/admin")) throw new Error("legacy /admin no redirigió: " + page.url());
  const nav = await page.locator("nav button, aside button").allTextContents().catch(() => []);
  console.log("   nav:", JSON.stringify(nav.slice(0, 6)));
  if (!nav.some((t) => t.includes("Productos"))) throw new Error("el panel no muestra su nav");

  // 4) Deep link de OTRO negocio → re-login (la sesión es de demo-e2e)
  await page.goto("http://localhost:5173/otro-negocio/admin");
  await page.waitForTimeout(1200);
  console.log("4) deep link otro negocio →", page.url());
  if (!page.url().includes("/login")) throw new Error("un deep link de otro negocio no re-logueó: " + page.url());

  // 5) Operador ADMIN → /platform
  await page.goto("http://localhost:5173/login");
  await page.fill('input[name="username"], #username, input[type="text"]', "e2e-platform-admin");
  await page.fill('input[type="password"]', "admin123");
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1500);
  console.log("5) ADMIN tras login:", page.url());
  if (!page.url().includes("/platform")) throw new Error("ADMIN no aterrizó en /platform: " + page.url());
  await expect(page.locator("h1")).toHaveText("Panel de operador");

  if (errors.length > 0) throw new Error("errores de página: " + errors.join(" | "));
  console.log("E2E URLS SLUG OK");
});
