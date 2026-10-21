/**
 * Fuentes curadas para la carta (spec S2 de odd/tasks/personalizacion-carta.md).
 *
 * Lista cerrada compartida por el panel (selector con vista previa) y la
 * carta (carga on demand): slugs que ESPEJAN la whitelist del backend
 * (BusinessProfileServiceImpl#MENU_FONTS). Mantener ambas listas en sync —
 * el backend revalida y un slug fuera de la lista es 400.
 *
 * La carga de Google Fonts es on demand e idempotente: `loadMenuFonts`
 * inyecta un único <link> con las familias pedidas (400/600/700) y recuerda
 * lo ya cargado, así la carta descarga sólo la fuente del local y el panel
 * descarga las de la vista previa una sola vez por sesión.
 */

export interface MenuFontOption {
  /** Slug canónico (minúsculas): lo que viaja en `menuFont`. */
  slug: string;
  /** Nombre para mostrar en el selector. */
  name: string;
  /** Font family exacta para CSS y para el query de Google Fonts. */
  family: string;
}

export const MENU_FONT_OPTIONS: MenuFontOption[] = [
  { slug: "inter", name: "Inter", family: "Inter" },
  { slug: "poppins", name: "Poppins", family: "Poppins" },
  { slug: "montserrat", name: "Montserrat", family: "Montserrat" },
  { slug: "playfair-display", name: "Playfair Display", family: "Playfair Display" },
  { slug: "lora", name: "Lora", family: "Lora" },
  { slug: "roboto-slab", name: "Roboto Slab", family: "Roboto Slab" },
  { slug: "dm-serif-display", name: "DM Serif Display", family: "DM Serif Display" },
  { slug: "nunito", name: "Nunito", family: "Nunito" },
];

/** Fuente por defecto de la carta cuando el local no eligió una. */
export const DEFAULT_MENU_FONT = "inter";

/** Resuelve la font family CSS para un slug (o el default si no está). */
export function menuFontFamily(slug: string | null | undefined): string {
  const option = MENU_FONT_OPTIONS.find((o) => o.slug === slug);
  return `"${(option ?? MENU_FONT_OPTIONS[0]).family}"`;
}

/**
 * Aplica (o limpia) la fuente de la carta en :root: setea `--menu-font` con
 * la family del slug y carga la familia on demand. Sin slug válido REMUEVE
 * la variable y no carga nada — reemplazo total, para que navegar de un
 * local con fuente a otro sin fuente no deje la anterior activa. Menu.css
 * la usa con fallback a la fuente del tema (`var(--menu-font,
 * var(--font-sans))`), así que sin variable la carta queda como siempre.
 */
export function applyMenuFont(slug: string | null | undefined): void {
  const rootStyle = document.documentElement.style;
  const option = MENU_FONT_OPTIONS.find((o) => o.slug === slug);
  if (option) {
    rootStyle.setProperty("--menu-font", `"${option.family}"`);
    loadMenuFonts([option.family]);
  } else {
    rootStyle.removeProperty("--menu-font");
  }
}

/**
 * Inyecta (una sola vez por familia, juzgando por el DOM) el <link> de Google
 * Fonts con los pesos que usa la carta. La idempotencia es DOM-based a
 * propósito: si el link se pierde (hot reload, limpieza de tests), se vuelve
 * a agregar — no hay caché de módulo que quede desincronizada del head.
 * Las preconecciones ya viven en index.html.
 */
export function loadMenuFonts(families: string[]): void {
  if (typeof document === "undefined") return;
  const pending = families.filter((family) => {
    if (!family) return false;
    const query = family.replace(/ /g, "+");
    return !document.querySelector(`link[href*="family=${query}"]`);
  });
  if (pending.length === 0) return;

  const query = pending
    .map((family) => `family=${family.replace(/ /g, "+")}:wght@400;600;700`)
    .join("&");
  const link = document.createElement("link");
  link.rel = "stylesheet";
  link.href = `https://fonts.googleapis.com/css2?${query}&display=swap`;
  document.head.appendChild(link);
}
