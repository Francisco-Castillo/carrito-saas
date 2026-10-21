/**
 * Tematización de la carta con el color de marca del local (`primaryColor`).
 *
 * El dueño elige el color en el panel (Local → Datos del local) y la carta se
 * pinta con él a través de variables CSS en :root. Si el local NO cargó color
 * (o carga algo inválido), NO se setea nada: cada regla de Menu.css usa la
 * variable con su valor glass actual como fallback y la carta queda igual a
 * como siempre fue. El reemplazo es total: aplicar sin color limpia cualquier
 * variable previa (importante entre navegaciones y en tests).
 *
 * Las variables derivadas son cinco:
 *   --primary-color    el color tal cual (#RRGGBB)
 *   --primary-hover    el mismo oscurecido ~15% para estados hover
 *   --primary-soft     el mismo con alfa 0.16 (fondos suaves, badge abierto)
 *   --primary-border   el mismo con alfa 0.35 (bordes del badge)
 *   --on-primary       texto legible ENCIMA del color: blanco o casi negro,
 *                      elegido por luminancia WCAG — un amarillo de marca con
 *                      texto blanco sería ilegible, y eso no se asume acá.
 */

/** Variables que el helper escribe (en orden estable para limpiar). */
export const BRAND_VAR_NAMES = [
  "--primary-color",
  "--primary-hover",
  "--primary-soft",
  "--primary-border",
  "--primary-faint",
  "--primary-light",
  "--primary-deep",
  "--primary-veil",
  "--primary-veil-strong",
  "--primary-surface",
  "--primary-bg",
  "--on-primary",
] as const;

/** Mismo contrato que valida el backend (BusinessProfileServiceImpl). */
const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

/** Texto claro sobre el color de marca (blanco puro). */
const ON_PRIMARY_LIGHT = "#ffffff";
/** Texto oscuro sobre el color de marca: el slate-900 del tema. */
const ON_PRIMARY_DARK = "#0f172a";

/** Fondo base de la app (tokens.css --color-bg): NEGRO puro por decisión
 *  del dueño (antes #020617). Los velos de la carta se mezclan contra él. */
const BG_BASE = { r: 0, g: 0, b: 0 };
/** Superficie sólida actual de las hojas (rgba(15, 23, 42, 0.92)). */
const SURFACE_BASE = { r: 15, g: 23, b: 42 };

interface Rgb {
  r: number;
  g: number;
  b: number;
}

function parseHex(value: string): Rgb | null {
  if (!HEX_COLOR.test(value)) return null;
  return {
    r: parseInt(value.slice(1, 3), 16),
    g: parseInt(value.slice(3, 5), 16),
    b: parseInt(value.slice(5, 7), 16),
  };
}

/** Luminancia relativa WCAG (0 = negro, 1 = blanco). */
function relativeLuminance({ r, g, b }: Rgb): number {
  const channel = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/** Contraste WCAG entre dos luminancias (1 a 21). */
function contrastRatio(a: number, b: number): number {
  const lighter = Math.max(a, b);
  const darker = Math.min(a, b);
  return (lighter + 0.05) / (darker + 0.05);
}

/** Mezcla el color con negro (0..1 de negro) y devuelve hex. */
function darken({ r, g, b }: Rgb, amount: number): string {
  const mix = (c: number) => Math.round(c * (1 - amount));
  const hex = (c: number) => mix(c).toString(16).padStart(2, "0");
  return `#${hex(r)}${hex(g)}${hex(b)}`;
}

/** Mezcla el color con blanco (0..1 de blanco) y devuelve hex. */
function lighten({ r, g, b }: Rgb, amount: number): string {
  const mix = (c: number) => Math.round(c + (255 - c) * amount);
  const hex = (c: number) => mix(c).toString(16).padStart(2, "0");
  return `#${hex(r)}${hex(g)}${hex(b)}`;
}

/** Mezcla la marca DENTRO de un color base y devuelve rgba con alfa. Los
 *  velos glass tienen que seguir siendo oscuros para que el texto de la
 *  carta siga legible: la marca se disuelve en el fondo, nunca encima. */
function veil(base: Rgb, brand: Rgb, brandShare: number, alpha: number): string {
  const mix = (c: number, b: number) => Math.round(b * brandShare + c * (1 - brandShare));
  return `rgba(${mix(base.r, brand.r)}, ${mix(base.g, brand.g)}, ${mix(base.b, brand.b)}, ${alpha})`;
}

/** Igual que veil pero opaca y en hex: para la BASE de la página (body),
 *  que no lleva transparencia. */
function mixHex(base: Rgb, brand: Rgb, brandShare: number): string {
  const mix = (c: number, b: number) => Math.round(b * brandShare + c * (1 - brandShare));
  const hex = (c: number) => c.toString(16).padStart(2, "0");
  return `#${hex(mix(base.r, brand.r))}${hex(mix(base.g, brand.g))}${hex(mix(base.b, brand.b))}`;
}

function rgba({ r, g, b }: Rgb, alpha: number): string {
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

/**
 * Deriva las cinco variables del color de marca. Devuelve null si el valor
 * no es un #RRGGBB válido (o está vacío): la carta mantiene su tema glass.
 */
export function brandCssVars(
  color: string | null | undefined,
): Record<string, string> | null {
  if (typeof color !== "string") return null;
  const rgb = parseHex(color.trim());
  if (!rgb) return null;

  const lum = relativeLuminance(rgb);
  // contrastRatio(lum, 1) es el contraste del BLANCO sobre el color; si gana
  // contra el negro, el texto de encima es blanco. (Invertir esto elegía
  // texto oscuro sobre violeta y blanco sobre amarillo: ilegible.)
  const onPrimary =
    contrastRatio(lum, 1) >= contrastRatio(lum, 0) ? ON_PRIMARY_LIGHT : ON_PRIMARY_DARK;

  return {
    "--primary-color": color.trim(),
    "--primary-hover": darken(rgb, 0.15),
    "--primary-soft": rgba(rgb, 0.16),
    "--primary-border": rgba(rgb, 0.35),
    "--primary-faint": rgba(rgb, 0.09),
    "--primary-light": lighten(rgb, 0.35),
    "--primary-deep": darken(rgb, 0.25),
    "--primary-veil": veil(BG_BASE, rgb, 0.14, 0.62),
    "--primary-veil-strong": veil(BG_BASE, rgb, 0.14, 0.8),
    "--primary-surface": veil(SURFACE_BASE, rgb, 0.1, 0.92),
    "--primary-bg": mixHex(BG_BASE, rgb, 0.07),
    "--on-primary": onPrimary,
  };
}

/**
 * Aplica (o limpia) las variables del color de marca en :root. Idempotente:
 * llamarla sin color válido deja :root exactamente como estaba antes de
 * cualquier aplicación previa.
 */
export function applyBrandColor(color: string | null | undefined): void {
  const rootStyle = document.documentElement.style;
  const vars = brandCssVars(color);
  for (const name of BRAND_VAR_NAMES) {
    if (vars && vars[name]) {
      rootStyle.setProperty(name, vars[name]);
    } else {
      rootStyle.removeProperty(name);
    }
  }
}
