export type AppView = "login" | "menu" | "dashboard" | "admin" | "kds";

/**
 * Route table for real navigation: the legacy app ships as separate HTML pages
 * into Spring static (menu/index.html, login/login.html, …), so each path
 * renders its view. Suffixes like /menu/index.html resolve too.
 */
const PATH_ROUTES: Array<[RegExp, AppView]> = [
  [/^\/menu\/(?:index\.html)?$/, "menu"],
  [/^\/dashboard\/(?:index\.html)?$/, "dashboard"],
  [/^\/admin\/(?:admin\.html)?$/, "admin"],
  [/^\/kds\/(?:index\.html)?$/, "kds"],
  [/^\/login\/(?:login\.html)?$/, "login"],
];

/**
 * Resolves the screen to render:
 * 1. from `window.location.pathname` when it matches a known route
 *    (`/menu/` → digital menu, `/dashboard/` → owner dashboard,
 *    `/admin/` → admin panel, `/kds/` → kitchen display);
 * 2. otherwise from the dev-only `view` query param escape hatch
 *    (`?view=menu`, etc. — works for all five views);
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
  return "login";
}
