export type AppView = "login" | "menu" | "dashboard" | "admin" | "kds" | "orders" | "platform";

/**
 * Route table for real navigation: the SPA ships as separate entry paths —
 * the legacy static files (menu/index.html, login/login.html, admin/admin.html,
 * ...) that QR codes and old bookmarks use, and the clean paths (/menu,
 * /dashboard, /admin, /kds, /login) the app itself redirects to. Both
 * families resolve here, with and without the trailing slash (Spring MVC no
 * longer matches it implicitly, so this table owns it). Suffixes like
 * /menu/index.html resolve too.
 */
const PATH_ROUTES: Array<[RegExp, AppView]> = [
  [/^\/menu(?:\/index\.html)?\/?$/, "menu"],
  [/^\/dashboard(?:\/index\.html)?\/?$/, "dashboard"],
  [/^\/admin(?:\/admin\.html)?\/?$/, "admin"],
  [/^\/kds(?:\/index\.html)?\/?$/, "kds"],
  [/^\/pedidos(?:\/index\.html)?\/?$/, "orders"],
  [/^\/platform(?:\/index\.html)?\/?$/, "platform"],
  [/^\/login(?:\/login\.html)?\/?$/, "login"],
];

/**
 * Resolves the screen to render:
 * 1. from `window.location.pathname` when it matches a known route
 *    (`/menu/` → digital menu, `/dashboard/` → owner dashboard,
 *    `/admin/` → admin panel, `/kds/` → kitchen display,
 *    `/pedidos/` → orders console);
 * 2. otherwise from the dev-only `view` query param escape hatch
 *    (`?view=menu`, etc. — works for all six views);
 * 3. anything else → login.
 */
export function getView(): AppView {
  const path = window.location.pathname;
  for (const [pattern, view] of PATH_ROUTES) {
    if (pattern.test(path)) return view;
  }

  const view = new URLSearchParams(window.location.search).get("view");
  if (view === "menu") return "menu";
  if (view === "dashboard") return "dashboard";
  if (view === "admin") return "admin";
  if (view === "kds") return "kds";
  if (view === "pedidos") return "orders";
  if (view === "platform") return "platform";
  return "login";
}
