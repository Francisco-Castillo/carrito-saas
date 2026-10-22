import { test, expect } from "@playwright/test";

/**
 * Bug reportado por el usuario (2026-10-22): el modal de usuarios del panel
 * /platform "se abre debajo de todos los componentes".
 *
 * Causa raíz (pre-existente desde el rediseño del panel): la regla
 * `.platform-page > *:not(.aurora) { position: relative; z-index: 1 }` tiene
 * más especificidad que `.platform-modal-backdrop { position: fixed;
 * z-index: 50 }`, así que el backdrop pierde su position: fixed y fluye al
 * final de la página en vez de cubrir el viewport.
 *
 * Contrato: el backdrop es un overlay fijo que cubre el viewport y el punto
 * central cae dentro del modal (queda por encima de todo).
 */
test("modal del panel /platform: overlay fijo que cubre el viewport", async ({ page }) => {
  await page.goto("/login");
  await page.fill('input[name="username"], #username, input[type="text"]', "e2e-platform-admin");
  await page.fill('input[type="password"]', "admin123");
  await Promise.all([
    page.waitForResponse((r) => r.url().includes("/api/auth/login")),
    page.click('button[type="submit"]'),
  ]);
  await page.waitForFunction(() => Boolean(localStorage.getItem("token")));
  await page.goto("/platform");
  await expect(page.locator("h1")).toHaveText("Panel de operador");

  // Abrir el modal de usuarios de la primera fila con dueño.
  await page.getByRole("button", { name: "Usuarios" }).first().click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();

  // El backdrop NO puede ser pisado por la regla de stacking de la página:
  // debe conservar position: fixed.
  const backdrop = page.locator(".platform-modal-backdrop");
  await expect(backdrop).toHaveCSS("position", "fixed");

  // Y el centro del viewport debe caer dentro del modal: queda ENCIMA del
  // resto del contenido, no debajo.
  const hit = await page.evaluate(() => {
    const el = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
    return Boolean(el?.closest(".platform-modal"));
  });
  expect(hit, "el centro del viewport debe caer dentro del modal abierto").toBe(true);
});
