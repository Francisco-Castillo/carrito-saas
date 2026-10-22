import { test, expect } from "@playwright/test";

// e2e del panel rediseñado contra el backend real (vite dev + proxy :9090).
// Cubre el flujo completo reportado por el usuario: alta con slug automático,
// bloqueo inline de datos inválidos y persistencia real del cliente.
test("panel operador: alta con slug automático y validación inline", async ({ page }) => {
  const errors: string[] = [];
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  page.on("pageerror", (e) => errors.push(e.message));

  await page.goto("http://localhost:5173/login");
  await page.fill('input[name="username"], #username, input[type="text"]', "e2e-platform-admin");
  await page.fill('input[type="password"]', "admin123");
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1500);

  await page.goto("http://localhost:5173/platform");
  await page.waitForTimeout(1500);
  await expect(page.locator("h1")).toHaveText("Panel de operador");

  const suffix = Date.now().toString().slice(-6);

  // 1) El slug se auto-completa normalizado desde el nombre.
  await page.fill('input[placeholder="Nombre del local"]', "Café del Sur " + suffix);
  const slugValue = await page.inputValue('input[placeholder="Slug"]');
  console.log("SLUG AUTO:", slugValue);
  if (slugValue !== "cafe-del-sur-" + suffix) throw new Error("slug automático incorrecto: " + slugValue);

  // 2) Contraseña corta: error junto al campo, sin llamada de red.
  await page.fill('input[placeholder="Usuario del dueño"]', "dueno-e2e-" + suffix);
  await page.fill('input[placeholder="Email del dueño"]', "dueno-e2e-" + suffix + "@test.test");
  await page.fill('input[placeholder="Contraseña del dueño"]', "corta");
  await page.click('button:has-text("Dar de alta")');
  await page.waitForTimeout(800);
  const fieldError = await page.locator(".platform-field-error").allTextContents();
  console.log("ERROR DE CAMPO:", JSON.stringify(fieldError));
  if (!fieldError.some((t) => t.includes("8 caracteres"))) throw new Error("falta error inline de contraseña");
  const requests: string[] = [];
  page.on("request", (r) => { if (r.url().includes("/api/platform/clients") && r.method() === "POST") requests.push(r.url()); });
  if (requests.length > 0) throw new Error("el POST salió a pesar de la validación");

  // 3) Alta válida: POST real, success y fila nueva en la tabla.
  await page.fill('input[placeholder="Contraseña del dueño"]', "clave12345");
  await page.fill('input[placeholder="Teléfono"]', "115000" + suffix.slice(0, 4));
  await page.click('button:has-text("Dar de alta")');
  await page.waitForSelector('[role="status"]', { timeout: 8000 });
  console.log("SUCCESS:", await page.locator('[role="status"]').textContent());
  await expect(page.locator("table tbody tr", { hasText: "Café del Sur " + suffix })).toBeVisible();

  // 4) Badge de estado activo en el rediseño.
  await expect(page.locator("table tbody tr", { hasText: "Café del Sur " + suffix }).locator(".platform-badge--active")).toBeVisible();

  if (errors.length > 0) throw new Error("errores de consola: " + errors.join(" | "));
  console.log("E2E OK — sin errores de consola");
});
