import { test, expect } from "@playwright/test";

// e2e del nuevo modelo de roles (odd/tasks/simplificacion-roles.md, S1-S4):
// - e2e-platform-admin (ADMIN sin membresía) entra a /platform y da de alta.
// - e2e-admin (OWNER del negocio demo) aterriza en /dashboard y la API de
//   plataforma le responde 403 con su token de negocio.
test("roles: ADMIN opera la plataforma, OWNER no la toca", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));

  // 1) Operador ADMIN → /platform, alta funcional.
  await page.goto("http://localhost:5173/login");
  await page.fill('input[name="username"], #username, input[type="text"]', "e2e-platform-admin");
  await page.fill('input[type="password"]', "admin123");
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1500);
  console.log("URL operador tras login:", page.url());
  if (!page.url().includes("/platform")) throw new Error("el operador ADMIN no aterrizó en /platform: " + page.url());
  await expect(page.locator("h1")).toHaveText("Panel de operador");

  const suffix = Date.now().toString().slice(-6);
  await page.fill('input[placeholder="Nombre del local"]', "Local Roles " + suffix);
  await page.fill('input[placeholder="Usuario del dueño"]', "dueno-roles-" + suffix);
  await page.fill('input[placeholder="Email del dueño"]', "dueno-roles-" + suffix + "@test.test");
  await page.fill('input[placeholder="Contraseña del dueño"]', "clave12345");
  await page.click('button:has-text("Dar de alta")');
  await page.waitForSelector('[role="status"]', { timeout: 8000 });
  console.log("ALTA:", await page.locator('[role="status"]').textContent());

  // 2) Sin opción ADMIN en el alta de usuarios del local (S2).
  await page.click('button:has-text("Usuarios") >> nth=0');
  await page.waitForTimeout(800);
  const roles = await page.locator(".platform-user-fields select option").allTextContents();
  console.log("ROLES EN SELECT:", JSON.stringify(roles));
  if (roles.includes("ADMIN")) throw new Error("el select de roles de local ofrece ADMIN");

  // 3) OWNER del negocio: dashboard, y la API de plataforma le da 403.
  await page.goto("http://localhost:5173/login");
  await page.fill('input[name="username"], #username, input[type="text"]', "e2e-admin");
  await page.fill('input[type="password"]', "admin123");
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1500);
  console.log("URL e2e-admin tras login:", page.url());
  if (!page.url().includes("/dashboard")) throw new Error("e2e-admin no aterrizó en /dashboard: " + page.url());

  const res = await page.evaluate(async () => {
    const r = await fetch("/api/platform/clients", {
      headers: { Authorization: "Bearer " + localStorage.getItem("token") },
    });
    return r.status;
  });
  console.log("GET /api/platform/clients con token OWNER:", res);
  if (res !== 403) throw new Error("un OWNER cruzó la barrera de plataforma: " + res);

  if (errors.length > 0) throw new Error("errores de página: " + errors.join(" | "));
  console.log("E2E ROLES OK");
});
