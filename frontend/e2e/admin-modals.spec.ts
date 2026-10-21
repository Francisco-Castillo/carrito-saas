import { test, expect, type Page } from "@playwright/test";
import { gotoAdminView, expectNoHorizontalOverflow } from "./helpers";

/**
 * Modales del admin en viewport chico (S4): tienen que entrar en el viewport,
 * no desbordar la página y cerrarse con Escape. Los tres modales comparten
 * .toast-modal (role="dialog").
 */

async function expectDialogFits(page: Page): Promise<void> {
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();

  const box = await dialog.boundingBox();
  expect(box).not.toBeNull();
  const viewport = page.viewportSize()!;
  // El modal entra en el viewport (con tolerancia de 1px por bordes).
  expect(box!.x).toBeGreaterThanOrEqual(-1);
  expect(box!.y).toBeGreaterThanOrEqual(-1);
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewport.width + 1);
  expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height + 1);

  await expectNoHorizontalOverflow(page);
}

test("modal nuevo producto: entra en viewport y cierra con Escape", async ({ page }) => {
  await gotoAdminView(page, "Productos");
  await page.getByRole("button", { name: "+ Nuevo producto" }).click();

  await expectDialogFits(page);

  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("modal nuevo combo: entra en viewport y cierra con Escape", async ({ page }) => {
  await gotoAdminView(page, "Combos");
  await page.getByRole("button", { name: "+ Nuevo combo" }).click();

  await expectDialogFits(page);

  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("modal nueva categoría: entra en viewport y cierra con Escape", async ({ page }) => {
  await gotoAdminView(page, "Categorías");
  await page.getByRole("button", { name: "+ Nuevo" }).click();

  await expectDialogFits(page);

  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
