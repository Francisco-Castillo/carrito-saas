export type AppView = "login" | "menu" | "dashboard" | "admin" | "kds";

/**
 * Dev-only view switch: resolves the screen to render from the `view` URL
 * query param (`view=menu` → digital menu, `view=dashboard` → owner
 * dashboard, `view=admin` → admin panel, `view=kds` → kitchen display,
 * anything else → login).
 *
 * No router library: the legacy app ships as separate HTML pages into Spring
 * static, so real navigation keeps using real paths (see Login redirects).
 */
export function getView(): AppView {
  const view = new URLSearchParams(window.location.search).get("view");
  if (view === "menu") return "menu";
  if (view === "dashboard") return "dashboard";
  if (view === "admin") return "admin";
  if (view === "kds") return "kds";
  return "login";
}
