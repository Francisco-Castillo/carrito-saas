export type AppView = "login" | "menu" | "dashboard" | "admin" | "kds" | "orders" | "platform";

/**
 * Resolución slug-aware de una ruta (odd/tasks/urls-por-slug.md, S2/S3/S5):
 * el negocio vuelve a vivir en la URL con el formato limpio /{slug}/...
 * - /{slug} a secas → carta pública del local (S3).
 * - /{slug}/admin|dashboard|kds|pedidos → paneles del negocio (S2).
 * - Los paths limpios viejos (/admin, /dashboard, ...) resuelven su vista
 *   marcados legacy: el guard los redirige al equivalente con el slug de la
 *   sesión (S4) — los bookmarks no se rompen.
 * - La ruta /menu está jubilada (odd/tasks/retire-menu-route.md, S6): la
 *   única carta pública es /{slug}; /menu y /menu/{slug} caen al fallback.
 *   "menu" queda reservado para siempre (S7): ningún local puede tener ese
 *   slug.
 * - /platform y /login no llevan slug (el operador no tiene negocio).
 */
export interface ViewResolution {
  view: AppView;
  slug: string | null;
  legacy: boolean;
}

/** Paths limpios de negocio REEMPLAZADOS por /{slug}/{panel}: legacy (S4). */
const LEGACY_ROUTES: Array<[RegExp, AppView]> = [
  [/^\/dashboard(?:\/index\.html)?\/?$/, "dashboard"],
  [/^\/admin(?:\/admin\.html)?\/?$/, "admin"],
  [/^\/kds(?:\/index\.html)?\/?$/, "kds"],
  [/^\/pedidos(?:\/index\.html)?\/?$/, "orders"],
];

/** Vistas sin slug que NO cambian: canónicas como están (S5). */
const NO_SLUG_ROUTES: Array<[RegExp, AppView]> = [
  [/^\/platform(?:\/index\.html)?\/?$/, "platform"],
  [/^\/login(?:\/login\.html)?\/?$/, "login"],
];

/** Paneles de negocio que viven bajo /{slug}/{panel} (S2). */
const SLUG_PANELS: Record<string, AppView> = {
  admin: "admin",
  dashboard: "dashboard",
  kds: "kds",
  pedidos: "orders",
};

/** Forma de slug en el backend (PlatformClientServiceImpl.SLUG_SHAPE). */
const SLUG_SHAPE = /^[a-z0-9-]+$/;

/** Segmentos que nunca son un slug de local: vistas conocidas + infra. */
const RESERVED_SEGMENTS = new Set([
  "menu", "admin", "dashboard", "kds", "pedidos", "platform", "login",
  "api", "assets", "ws", "dev-media", "uploads", "actuator", "favicon.ico",
]);

/**
 * Resuelve vista + slug + marca legacy desde el pathname. Legacy=true NO
 * significa "renderizar otra cosa": significa que el guard redirige a la
 * URL canónica con slug (S4/S5) antes de pintar.
 */
export function resolveView(pathname: string): ViewResolution {
  for (const [pattern, view] of LEGACY_ROUTES) {
    if (pattern.test(pathname)) return { view, slug: null, legacy: true };
  }
  for (const [pattern, view] of NO_SLUG_ROUTES) {
    if (pattern.test(pathname)) return { view, slug: null, legacy: false };
  }

  const parts = pathname.split("/").filter(Boolean);
  if (
    parts.length >= 1 &&
    SLUG_SHAPE.test(parts[0]) &&
    !RESERVED_SEGMENTS.has(parts[0])
  ) {
    const [slug, panel] = parts;
    if (parts.length === 1) return { view: "menu", slug, legacy: false };
    if (parts.length === 2 && SLUG_PANELS[panel]) {
      return { view: SLUG_PANELS[panel], slug, legacy: false };
    }
  }

  // Escape hatch dev-only: fuerza una vista en paths no-route. La vista
  // carta NO está: la ruta /menu está jubilada y "menu" es un slug reservado
  // (retire-menu-route.md, S6/S7) — cae al fallback como cualquier valor
  // desconocido.
  const forced = new URLSearchParams(window.location.search).get("view");
  if (forced === "dashboard") return { view: "dashboard", slug: null, legacy: false };
  if (forced === "admin") return { view: "admin", slug: null, legacy: false };
  if (forced === "kds") return { view: "kds", slug: null, legacy: false };
  if (forced === "pedidos") return { view: "orders", slug: null, legacy: false };
  if (forced === "platform") return { view: "platform", slug: null, legacy: false };
  return { view: "login", slug: null, legacy: false };
}

/** Compat: la vista a secas (lo que App pintaba antes del slug). */
export function getView(): AppView {
  return resolveView(window.location.pathname).view;
}

/**
 * Browser tab title per view. The SPA ships ONE html shell whose static
 * <title> says “Login” (the pre-JS flash and the login view are the same
 * thing), so every other view must retitle the tab on mount — otherwise
 * admin/kds/pedidos all keep reading “Carrito SaaS — Login”.
 */
const VIEW_TITLES: Record<AppView, string> = {
  login: "Login",
  // Vista pública: tal como la fijaba Menu.tsx antes de que este mapa
  // concentrara los títulos.
  menu: "Carta Digital",
  dashboard: "Dashboard",
  admin: "Panel del local",
  kds: "Cocina",
  orders: "Pedidos",
  platform: "Plataforma",
};

export function titleForView(view: AppView): string {
  return VIEW_TITLES[view];
}

/**
 * Panel de negocio en formato URL: la vista interna a su segmento bajo el
 * slug (orders → pedidos, como se imprime en la URL).
 */
function panelSegment(view: AppView): string {
  return view === "orders" ? "pedidos" : view;
}

/**
 * Landing por rol tras autenticar, y destino de los redirects del guard de
 * vistas (App.tsx). Fuente única del mapeo — Login.tsx y el guard no pueden
 * divergir (odd/tasks/urls-por-slug.md, S1/S2):
 * - ADMIN es el rol exclusivo de la plataforma y NO tiene negocio →
 *   /platform (sin slug).
 * - Los roles de negocio viven bajo el slug de su local: OWNER →
 *   /{slug}/admin, KITCHEN → /{slug}/kds, PEDIDOS → /{slug}/pedidos.
 * - Sin slug en la sesión sólo el operador tiene destino: un rol de negocio
 *   sin slug no sabe a dónde ir → /login.
 */
export function landingForRole(role: string | null, slug: string | null): string {
  if (!role) return "/login";
  if (role.includes("ADMIN")) return "/platform";
  if (!slug) return "/login";
  if (role.includes("OWNER")) return `/${slug}/admin`;
  if (role.includes("KITCHEN")) return `/${slug}/kds`;
  if (role.includes("PEDIDOS")) return `/${slug}/pedidos`;
  return "/login";
}

/**
 * Decisión PURA del guard de vistas por rol (odd/tasks/simplificacion-roles.md,
 * S4/S6): devuelve el destino del redirect o null si la vista corresponde.
 * App.tsx la aplica en useLayoutEffect; los tests la ejercitan sin depender
 * de la semántica de navegación de jsdom, y el e2e de Playwright cubre la
 * navegación real en browser.
 *
 * - Carta pública: /{slug} es canónica (S3). La ruta /menu está jubilada
 *   (retire-menu-route.md, S6): view.ts ya no resuelve menú con legacy=true,
 *   así que la vista carta nunca redirige — es pública.
 * - /platform: sólo el operador (rol ADMIN) se queda; una sesión de negocio
 *   va a su panel con slug; sin sesión → login (la vista se pinta y el
 *   post-login la resuelve).
 * - Paneles de negocio: el slug del path es canónico (S6). Sin sesión →
 *   login; sesión de plataforma → /platform; sesión de OTRO negocio →
 *   login (re-login scopeado) — el backend sólo sirve datos del negocio
 *   del token.
 * - Paths legacy (/admin, /dashboard, ...) → equivalente con el slug de la
 *   sesión (S4); sin sesión → login.
 */
export function guardRedirect(
  resolution: { view: AppView; slug: string | null; legacy: boolean },
  session: { role: string | null; slug: string | null; hasSession: boolean },
): string | null {
  const { view, slug, legacy } = resolution;
  const isPlatformRole = session.role !== null && session.role.includes("ADMIN");
  const platformSession = isPlatformRole && session.slug === null;

  if (view === "menu") {
    // Carta pública: sin sesión no hay nada que gatear (S3). Sólo se llega
    // acá vía /{slug} (retire-menu-route.md, S6): no hay rama legacy.
    return null;
  }

  if (view === "login") return null;

  if (view === "platform") {
    if (!session.hasSession) return "/login";
    if (isPlatformRole) return null;
    return landingForRole(session.role, session.slug);
  }

  // Paneles de negocio (admin/dashboard/kds/orders), con y sin slug de path.
  if (platformSession) return "/platform";
  if (!session.hasSession) return "/login";
  if (legacy) {
    // S4: URL limpia vieja → equivalente con el slug de la sesión.
    if (!session.slug) return "/login";
    return `/${session.slug}/${panelSegment(view)}`;
  }
  // S6: el slug del path es canónico — una sesión de otro negocio re-loguea.
  if (session.slug !== slug) return "/login";
  return null;
}
