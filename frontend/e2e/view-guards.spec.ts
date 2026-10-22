import { test, expect } from "@playwright/test";

// Guard de vistas por rol en navegador real (odd/tasks/simplificacion-roles.md,
// S2/S3): el router no puede dejar entrar por URL a quien no corresponde.
// 1) OWNER (e2e-admin) que tipea /platform → llevado a /admin, panel completo.
// 2) OWNER que tipea /admin → ve el panel del local con todo el nav.
// 3) Operador ADMIN (e2e-platform-admin) que tipea /admin → llevado a /platform.
test("guard de vistas: cada rol queda en su panel", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));

  // 1) OWNER tipea /platform → /admin
  await page.goto("http://localhost:5173/login");
  await page.fill('input[name="username"], #username, input[type="text"]', "e2e-admin");
  await page.fill('input[type="password"]', "admin123");
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1200);
  console.log("1) OWNER tras login:", page.url());
  if (!page.url().includes("/admin")) throw new Error("OWNER no aterrizó en /admin: " + page.url());

  await page.goto("http://localhost:5173/platform");
  await page.waitForTimeout(1200);
  console.log("2) OWNER tipea /platform →", page.url());
  if (page.url().includes("/platform")) throw new Error("OWNER sigue viendo /platform");
  if (!page.url().includes("/admin")) throw new Error("OWNER no fue llevado a /admin: " + page.url());

  // 2) OWNER en /admin: panel del local con el nav completo
  await page.goto("http://localhost:5173/admin");
  await page.waitForTimeout(1500);
  const nav = await page.locator("nav button, aside button").allTextContents().catch(() => []);
  console.log("3) OWNER en /admin nav:", JSON.stringify(nav.slice(0, 6)));
  if (!nav.some((t) => t.includes("Productos"))) throw new Error("el panel del local no muestra su nav: " + JSON.stringify(nav));

  // 3) Operador ADMIN tipea /admin → /platform
  await page.goto("http://localhost:5173/login");
  await page.fill('input[name="username"], #username, input[type="text"]', "e2e-platform-admin");
  await page.fill('input[type="password"]', "admin123");
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1200);
  console.log("4) ADMIN tras login:", page.url());
  if (!page.url().includes("/platform")) throw new Error("ADMIN no aterrizó en /platform: " + page.url());

  await page.goto("http://localhost:5173/admin");
  await page.waitForTimeout(1200);
  console.log("5) ADMIN tipea /admin →", page.url());
  if (!page.url().includes("/platform")) throw new Error("el operador quedó en /admin: " + page.url());
  await expect(page.locator("h1")).toHaveText("Panel de operador");

  if (errors.length > 0) throw new Error("errores de página: " + errors.join(" | "));
  console.log("E2E GUARD OK");
});
