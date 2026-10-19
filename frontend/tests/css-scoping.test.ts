/**
 * Guard: ninguna hoja de página puede declarar selectores de elemento "pelados".
 *
 * Cada página de la SPA comparte un mismo bundle de CSS (el build emite un solo
 * archivo), así que un selector como `main { max-width: 1100px }` en
 * Dashboard.css también se aplica al <main> del menú público y lo limita a
 * 1100px, sin ningún error visible. Ya pasó dos veces: `main` recortando la
 * carta y `.topbar`/`.restaurant-name` fugándose entre vistas.
 *
 * Se permiten selectores de elemento cuando están acotados (`.dashboard-screen
 * main`) o cuando son parte de una clase; sólo se rechaza el elemento solo.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/** vitest corre con cwd = frontend. */
const PAGES_DIR = join(process.cwd(), "src", "pages");

const PAGE_STYLESHEETS = [
  "Dashboard.css",
  "Menu.css",
  "Admin.css",
  "Kds.css",
  "Login.css",
  "Orders.css",
];

/** Elementos cuyo estilo global afecta a otra pantalla (todos los layouts usan
 *  main/header/nav/section, y las tablas y títulos se repiten). */
const UNSCOPED_ELEMENTS = [
  "main",
  "header",
  "nav",
  "section",
  "aside",
  "footer",
  "table",
  "h1",
  "h2",
  "h3",
  "ul",
  "li",
  "img",
  "input",
  "button",
  "select",
  "textarea",
];

function selectorsOf(css: string): string[] {
  const withoutComments = css.replace(/\/\*[\s\S]*?\*\//g, "");
  const selectors: string[] = [];
  for (const block of withoutComments.split("}")) {
    const braceIndex = block.lastIndexOf("{");
    if (braceIndex === -1) continue;
    const head = block.slice(0, braceIndex).split(";").pop() ?? "";
    for (const part of head.split(",")) {
      const selector = part.trim();
      if (selector === "" || selector.startsWith("@")) continue;
      selectors.push(selector);
    }
  }
  return selectors;
}

describe("CSS scope between screens", () => {
  it("no page stylesheet styles a bare element selector", () => {
    const offenders: string[] = [];

    for (const file of PAGE_STYLESHEETS) {
      const css = readFileSync(join(PAGES_DIR, file), "utf8");
      for (const selector of selectorsOf(css)) {
        if (UNSCOPED_ELEMENTS.includes(selector)) {
          offenders.push(`${file} → ${selector}`);
        }
      }
    }

    expect(offenders, "un selector de elemento sin scope se filtra a las otras pantallas").toEqual([]);
  });

  it("the guard actually sees the selectors it checks", () => {
    // Si el parser de selectores se rompe, el test de arriba pasaría vacío.
    const css = readFileSync(join(PAGES_DIR, "Menu.css"), "utf8");
    const selectors = selectorsOf(css);
    expect(selectors.length).toBeGreaterThan(50);
    expect(selectors).toContain(".menu-sticky");
  });
});
