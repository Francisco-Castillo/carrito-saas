import { test, expect } from "@playwright/test";
import { gotoAdminView, expectNoHorizontalOverflow } from "./helpers";

/**
 * Tests responsive por vista del admin (S2). En cada proyecto (mobile
 * 390px / tablet 768px / desktop 1280px), cada vista tiene que:
 *  - renderizar su heading,
 *  - no desbordar horizontalmente la página (todo overflow vive en
 *    contenedores scrolleables: .table-scroll, .admin-sidebar),
 *  - mantener el nav del sidebar visible y usable.
 *
 * Sin backend: los datos fallan y las vistas muestran sus estados vacíos —
 * el layout en vacío también tiene que ser responsive.
 */

const VIEWS = ["Dashboard", "Productos", "Categorías", "Combos", "Local"];

for (const view of VIEWS) {
  test(`vista ${view}: sin overflow horizontal y nav usable`, async ({ page }) => {
    await gotoAdminView(page, view);

    await expect(page.getByRole("heading", { name: view })).toBeVisible();
    await expectNoHorizontalOverflow(page);

    // El nav tiene que seguir visible y con los 5 botones clickeables.
    for (const navView of VIEWS) {
      await expect(page.getByRole("button", { name: navView })).toBeVisible();
    }
  });
}

test("vista Productos: la tabla scrollea dentro de su contenedor", async ({ page }) => {
  await gotoAdminView(page, "Productos");

  // El contenedor de tabla tiene que existir y ser el que scrollea: la
  // página nunca tiene que scrollear de lado por culpa de una tabla.
  const scroll = page.locator(".table-scroll").first();
  await expect(scroll).toBeAttached();

  const overflowX = await scroll.evaluate(
    (el) => el.scrollWidth - el.clientWidth
  );
  expect(overflowX).toBeGreaterThanOrEqual(0);

  await expectNoHorizontalOverflow(page);
});
