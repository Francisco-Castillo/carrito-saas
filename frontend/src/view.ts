export type AppView = "login" | "menu" | "dashboard";

/**
 * Dev-only view switch: resolves the screen to render from the `view` URL
 * query param (`view=menu` → digital menu, `view=dashboard` → owner
 * dashboard, anything else → login).
 *
 * No router library: the legacy app ships as separate HTML pages into Spring
 * static, so real navigation keeps using real paths (see Login redirects).
 */
export function getView(): AppView {
  const view = new URLSearchParams(window.location.search).get("view");
  return view === "menu" ? "menu" : view === "dashboard" ? "dashboard" : "login";
}
