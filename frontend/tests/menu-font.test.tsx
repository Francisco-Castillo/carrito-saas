/**
 * Tipografía de la carta (spec S2 de odd/tasks/personalizacion-carta.md).
 *
 * El dueño elige la fuente en el panel (pestaña "Personalización"); el
 * perfil la persiste como `menuFont` (slug de la lista blanca) y la carta
 * tiene que aplicarla:
 *   - seteando `--menu-font` en :root (Menu.css la usa con fallback a la
 *     fuente del tema — sin slug, la carta queda exactamente como siempre);
 *   - cargando la familia on demand desde Google Fonts (un solo <link>,
 *     idempotente); sin slug NO se carga nada.
 *
 * Regla de reemplazo: aplicar sin slug limpia la variable — navegar de un
 * local con fuente a otro sin fuente no puede dejar la fuente vieja activa.
 */
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import Menu from "../src/pages/Menu";
import { applyMenuFont } from "../src/menuFonts";

const MENU_PAYLOAD = {
  products: [
    {
      id: 301,
      name: "Pizza",
      description: "muzzarella",
      price: 5200,
      categoryId: 1,
      categoryName: "Pizzas",
      imageUrl: "img-pizza.jpg",
    },
  ],
  combos: [],
  business: { name: "Local Fuente", whatsappNumber: "1155550000" },
};

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data });

/** Limpia :root y los <link> de Google Fonts que el helper pudo inyectar. */
function clearFontState() {
  document.documentElement.style.removeProperty("--menu-font");
  document.querySelectorAll('link[href*="fonts.googleapis.com"]').forEach((l) => l.remove());
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  clearFontState();
});

describe("applyMenuFont (:root + carga de fuente)", () => {
  it("con un slug de la lista setea --menu-font y carga la familia on demand", () => {
    applyMenuFont("poppins");
    expect(document.documentElement.style.getPropertyValue("--menu-font")).toBe('"Poppins"');
    const link = document.querySelector<HTMLLinkElement>('link[href*="fonts.googleapis.com"]');
    expect(link?.href).toContain("Poppins");
  });

  it("es idempotente: aplicar dos veces la misma fuente no duplica el <link>", () => {
    applyMenuFont("poppins");
    applyMenuFont("poppins");
    expect(
      document.querySelectorAll('link[href*="fonts.googleapis.com"]').length,
    ).toBe(1);
  });

  it("sin slug (o vacío/inválido) limpia la variable y no carga nada", () => {
    applyMenuFont("poppins");
    applyMenuFont("");
    expect(document.documentElement.style.getPropertyValue("--menu-font")).toBe("");

    applyMenuFont("poppins");
    applyMenuFont(undefined);
    expect(document.documentElement.style.getPropertyValue("--menu-font")).toBe("");

    const linksBefore = document.querySelectorAll('link[href*="fonts.googleapis.com"]').length;
    applyMenuFont("comic-sans");
    expect(document.querySelectorAll('link[href*="fonts.googleapis.com"]').length).toBe(linksBefore);
  });
});

describe("Menu integra la fuente del local", () => {
  it("con menuFont en el local (ruta slug), la carta setea --menu-font y carga la familia", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string | URL | Request) => {
        const u = String(url instanceof Request ? url.url : url);
        if (u.startsWith("/api/restaurants/slug/")) {
          return okJson({
            name: "Local Fuente",
            whatsappNumber: "1155550000",
            menuFont: "poppins",
          });
        }
        if (u.startsWith("/api/menu/")) {
          return okJson(MENU_PAYLOAD);
        }
        return { ok: false, status: 404, json: async () => ({}) };
      }),
    );

    window.history.replaceState({}, "", "/menu/?restaurant=fuente-local");
    render(<Menu />);

    await waitFor(() => {
      expect(screen.getByText("Pizza")).toBeTruthy();
    });
    expect(document.documentElement.style.getPropertyValue("--menu-font")).toBe('"Poppins"');
    expect(
      [...document.querySelectorAll<HTMLLinkElement>('link[href*="fonts.googleapis.com"]')]
        .some((l) => l.href.includes("Poppins")),
    ).toBe(true);
  });

  it("sin menuFont la carta no deja la variable seteada", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string | URL | Request) => {
        const u = String(url instanceof Request ? url.url : url);
        if (u.startsWith("/api/restaurants/slug/")) {
          return okJson({ name: "Local Sin Fuente", whatsappNumber: "1155550000" });
        }
        if (u.startsWith("/api/menu/")) {
          return okJson(MENU_PAYLOAD);
        }
        return { ok: false, status: 404, json: async () => ({}) };
      }),
    );

    window.history.replaceState({}, "", "/menu/?restaurant=fuente-local");
    render(<Menu />);

    await waitFor(() => {
      expect(screen.getByText("Pizza")).toBeTruthy();
    });
    expect(document.documentElement.style.getPropertyValue("--menu-font")).toBe("");
  });
});
