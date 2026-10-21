import { test, expect } from "@playwright/test";
import { fakeAdminSession, expectNoHorizontalOverflow } from "./helpers";

/**
 * Smoke del panel admin: sesión fake + render de la vista dashboard en los
 * tres proyectos (mobile/tablet/desktop). Si esto no pasa, nada de lo demás
 * significa algo.
 */
test("el panel carga con sesión fake y no desborda horizontalmente", async ({ page }) => {
  await fakeAdminSession(page);
  await page.goto("/admin");

  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
  await expectNoHorizontalOverflow(page);
});
