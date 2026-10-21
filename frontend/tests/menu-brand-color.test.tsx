/**
 * Tematización de la carta con el color de marca del local (`primaryColor`).
 *
 * El dueño elige el color en el panel (Local → Datos del local) y la carta
 * tiene que pintarse completa con él: precios, botones principales ("Agregar",
 * "Ver pedido", envío del pedido), píldora activa del nav, badge de abierto,
 * links de contacto, totales y foco de los inputs. Si el local NO cargó color
 * (o carga algo inválido), la carta queda exactamente como hoy: tema glass.
 *
 * El matiz difícil es el contraste: el color lo elige un humano y puede ser
 * clarito (un amarillo) u oscuro (un violeta), así que el texto que va encima
 * de los botones pintados se calcula por luminancia WCAG (blanco o casi negro)
 * y no se asume blanco.
 */
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import Menu from "../src/pages/Menu";
import { applyBrandColor, brandCssVars } from "../src/pages/menu/brandColor";

const SLUG = "color-local";

const MENU_PAYLOAD = {
  products: [
    {
      id: 201,
      name: "Hamburguesa",
      description: "con cheddar",
      price: 4500,
      categoryId: 1,
      categoryName: "Burgers",
      imageUrl: "img-burger.jpg",
    },
  ],
  combos: [],
  business: { name: "Local Color", whatsappNumber: "1155550000" },
};

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data });

/** Limpia las variables que el helper escribe en :root entre tests. */
function clearRootVars() {
  for (const name of [
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
  ]) {
    document.documentElement.style.removeProperty(name);
  }
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  clearRootVars();
});

describe("brandCssVars (derivación de variables)", () => {
  it("color oscuro (violeta) → texto blanco encima", () => {
    const vars = brandCssVars("#7C3AED");
    expect(vars).not.toBeNull();
    expect(vars!["--primary-color"]).toBe("#7C3AED");
    expect(vars!["--on-primary"]).toBe("#ffffff");
  });

  it("color claro (amarillo) → texto casi negro encima", () => {
    const vars = brandCssVars("#FDE047");
    expect(vars).not.toBeNull();
    expect(vars!["--on-primary"]).toBe("#0f172a");
  });

  it("acepta minúsculas y deriva hover, fondo suave y borde", () => {
    const vars = brandCssVars("#7c3aed");
    expect(vars).not.toBeNull();
    expect(vars!["--primary-color"]).toBe("#7c3aed");
    expect(vars!["--primary-hover"]).toMatch(/^#/);
    expect(vars!["--primary-hover"]).not.toBe(vars!["--primary-color"]);
    expect(vars!["--primary-soft"]).toMatch(/^rgba\(124, 58, 237, 0\.16\)$/);
    expect(vars!["--primary-border"]).toMatch(/^rgba\(124, 58, 237, 0\.35\)$/);
  });

  it("deriva la familia de fondo: faint, claro, profundo y velos glass", () => {
    const vars = brandCssVars("#7C3AED");
    expect(vars).not.toBeNull();
    // Glow grande del fondo: alfa bien bajo.
    expect(vars!["--primary-faint"]).toBe("rgba(124, 58, 237, 0.09)");
    // Variantes para los blobs de la aurora: mezcla con blanco y con negro.
    expect(vars!["--primary-light"]).toBe("#aa7ff3");
    expect(vars!["--primary-deep"]).toBe("#5d2cb2");
    // Velos glass (barra sticky, barra del carrito): la marca se mezcla
    // DENTRO del fondo negro (no encima) para no perder legibilidad.
    expect(vars!["--primary-veil"]).toBe("rgba(17, 8, 33, 0.62)");
    expect(vars!["--primary-veil-strong"]).toBe("rgba(17, 8, 33, 0.8)");
    // Superficie sólida (hoja de detalle): tinte sutil sobre el slate actual.
    expect(vars!["--primary-surface"]).toBe("rgba(26, 27, 62, 0.92)");
    // Base de la página (negro #000000 desde el cambio de tema): la marca se
    // mezcla al 7% y el body la usa con fallback al negro original.
    expect(vars!["--primary-bg"]).toBe("#090411");
  });

  it("valor inválido o vacío → null (la carta queda con el tema glass)", () => {
    expect(brandCssVars("violeta")).toBeNull();
    expect(brandCssVars("")).toBeNull();
    expect(brandCssVars("#12345")).toBeNull();
    expect(brandCssVars(undefined)).toBeNull();
    expect(brandCssVars(null)).toBeNull();
  });
});

describe("applyBrandColor (:root)", () => {
  it("setea las variables en documentElement", () => {
    applyBrandColor("#7C3AED");
    expect(document.documentElement.style.getPropertyValue("--primary-color")).toBe("#7C3AED");
    expect(document.documentElement.style.getPropertyValue("--on-primary")).toBe("#ffffff");
  });

  it("sin color o inválido, limpia cualquier variable previa", () => {
    applyBrandColor("#7C3AED");
    applyBrandColor("violeta");
    expect(document.documentElement.style.getPropertyValue("--primary-color")).toBe("");
    expect(document.documentElement.style.getPropertyValue("--on-primary")).toBe("");
    applyBrandColor("#7C3AED");
    applyBrandColor(null);
    expect(document.documentElement.style.getPropertyValue("--primary-color")).toBe("");
  });
});

describe("Menu integra el color de marca", () => {
  it("con primaryColor en el local (ruta slug), la carta setea las variables", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string | URL | Request) => {
        const u = String(url instanceof Request ? url.url : url);
        if (u.startsWith("/api/restaurants/slug/")) {
          return okJson({
            name: "Local Color",
            whatsappNumber: "1155550000",
            primaryColor: "#7C3AED",
          });
        }
        if (u.startsWith("/api/menu/")) {
          return okJson(MENU_PAYLOAD);
        }
        return { ok: false, status: 404, json: async () => ({}) };
      }),
    );

    window.history.replaceState({}, "", `/menu/?restaurant=${SLUG}`);
    render(<Menu />);

    await waitFor(() => {
      expect(screen.getByText("Hamburguesa")).toBeTruthy();
    });
    expect(document.documentElement.style.getPropertyValue("--primary-color")).toBe("#7C3AED");
    expect(document.documentElement.style.getPropertyValue("--on-primary")).toBe("#ffffff");
  });

  it("sin primaryColor, la carta no deja variables seteadas", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string | URL | Request) => {
        const u = String(url instanceof Request ? url.url : url);
        if (u.startsWith("/api/restaurants/slug/")) {
          return okJson({ name: "Local Sin Color", whatsappNumber: "1155550000" });
        }
        if (u.startsWith("/api/menu/")) {
          return okJson(MENU_PAYLOAD);
        }
        return { ok: false, status: 404, json: async () => ({}) };
      }),
    );

    window.history.replaceState({}, "", `/menu/?restaurant=${SLUG}`);
    render(<Menu />);

    await waitFor(() => {
      expect(screen.getByText("Hamburguesa")).toBeTruthy();
    });
    expect(document.documentElement.style.getPropertyValue("--primary-color")).toBe("");
    expect(document.documentElement.style.getPropertyValue("--on-primary")).toBe("");
  });
});
