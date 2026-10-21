import { test, expect, type Page } from "@playwright/test";

/**
 * Sesión fake: el panel exige token + restaurantSlug en localStorage
 * (hasPanelSession), y checkAuth() además decodifica el JWT y lo trata como
 * expirado si el payload no parsea — por eso el token fake es un JWT válido
 * con exp futura. Sin backend real, las llamadas a la API fallan (401) y las
 * vistas muestran sus estados vacíos/de error — exactamente el estado que
 * hay que testear responsive. Inyectamos ANTES de que cargue el bundle con
 * addInitScript.
 */
export async function fakeAdminSession(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const b64url = (obj: object) =>
      btoa(JSON.stringify(obj)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    // exp a ~10 años: los tests de layout no viven tanto.
    const exp = Math.floor(Date.now() / 1000) + 10 * 365 * 24 * 60 * 60;
    const fakeJwt = `${b64url({ alg: "HS384" })}.${b64url({
      sub: "e2e-owner",
      businessId: 990000001,
      exp,
    })}.fake-signature`;
    localStorage.setItem("token", fakeJwt);
    localStorage.setItem("restaurantSlug", "demo-e2e");
  });
}

export async function gotoAdminView(page: Page, view: string): Promise<void> {
  await fakeAdminSession(page);
  await page.goto("/admin");
  // El sidebar es navegable por labels en español (VIEW_LABELS de Admin.tsx).
  await page.getByRole("button", { name: view }).click();
}

/**
 * El body no debe scrollear horizontalmente en ningún breakpoint: todo
 * overflow tiene que vivir en contenedores scrolleables (.table-scroll,
 * .admin-sidebar), nunca en la página.
 */
export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  await expect
    .poll(
      async () =>
        page.evaluate(
          () => document.documentElement.scrollWidth - document.documentElement.clientWidth
        ),
      { message: "horizontal overflow en <html>" }
    )
    .toBeLessThanOrEqual(0);
}
