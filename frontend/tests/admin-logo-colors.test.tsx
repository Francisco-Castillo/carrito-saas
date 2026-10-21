/**
 * Colores sugeridos desde el logo (spec S3 de odd/tasks/personalizacion-carta.md).
 *
 * Dos capas:
 *   - extractDominantColors: función PURA sobre los bytes RGBA de la imagen
 *     (canvas en producción, arrays sintéticos acá). Reglas: los píxeles
 *     transparentes no aportan, el fondo casi-blanco/casi-negro se descarta
 *     (regla por canales, no por luminancia — un logo azul puro tiene
 *     luminancia baja y NO puede perderse), los colores similares se
 *     fusionan (cuantización 4 bits + distancia RGB), y devuelve a lo sumo
 *     `max` colores en hex mayúscula.
 *   - Pestaña "Personalización": con logo cargado aparece "Sugerir desde el
 *     logo"; las sugerencias son swatches clickeables que setean el color;
 *     sin logo no hay botón; si el análisis falla, error legible y el resto
 *     de la pestaña sigue usable.
 */
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  extractDominantColors,
  suggestColorsFromImage,
} from "../src/pages/admin/logoColors";

// La UI usa suggestColorsFromImage (canvas: jsdom no lo implementa); los
// tests de UI la mockean. extractDominantColors queda REAL para probar la
// extracción pura.
vi.mock(import("../src/pages/admin/logoColors"), async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, suggestColorsFromImage: vi.fn() };
});

const { suggestColorsFromImage: suggestMock } = await import("../src/pages/admin/logoColors");
const suggestMockFn = suggestMock as unknown as ReturnType<typeof vi.fn>;

/** Construye un buffer RGBA sintético a partir de píxeles [r,g,b,a]. */
function pixelsOf(px: Array<[number, number, number, number]>): Uint8ClampedArray {
  return new Uint8ClampedArray(px.flat());
}

describe("extractDominantColors (extracción pura)", () => {
  it("una imagen sólida devuelve exactamente ese color en hex mayúscula", () => {
    const px = pixelsOf([
      [255, 0, 0, 255],
      [255, 0, 0, 255],
      [255, 0, 0, 255],
      [255, 0, 0, 255],
    ]);
    expect(extractDominantColors(px, 2, 2)).toEqual(["#FF0000"]);
  });

  it("dos franjas de color dominan en orden de cantidad", () => {
    // Columna izquierda roja (3 px), derecha verde (1 px).
    const px = pixelsOf([
      [255, 0, 0, 255],
      [255, 0, 0, 255],
      [255, 0, 0, 255],
      [0, 200, 0, 255],
    ]);
    expect(extractDominantColors(px, 2, 2)).toEqual(["#FF0000", "#00C800"]);
  });

  it("los píxeles transparentes no aportan dominantes", () => {
    const px = pixelsOf([
      [0, 0, 0, 0],
      [0, 0, 0, 0],
      [0, 0, 0, 0],
      [255, 0, 0, 255],
    ]);
    expect(extractDominantColors(px, 2, 2)).toEqual(["#FF0000"]);
  });

  it("el fondo casi-blanco se descarta por canales (un logo azul puro sobrevive)", () => {
    const px = pixelsOf([
      [255, 255, 255, 255],
      [255, 255, 255, 255],
      [0, 0, 255, 255],
      [0, 0, 255, 255],
    ]);
    expect(extractDominantColors(px, 2, 2)).toEqual(["#0000FF"]);
  });

  it("rojos parecidos se fusionan en un solo dominante", () => {
    const px = pixelsOf([
      [255, 0, 0, 255],
      [255, 0, 0, 255],
      [230, 20, 20, 255],
      [230, 20, 20, 255],
    ]);
    expect(extractDominantColors(px, 2, 2)).toEqual(["#FF0000"]);
  });

  it("respeta el máximo pedido", () => {
    const px = pixelsOf([
      [255, 0, 0, 255],
      [0, 200, 0, 255],
      [0, 0, 255, 255],
      [255, 200, 0, 255],
    ]);
    expect(extractDominantColors(px, 2, 2, { max: 2 }).length).toBe(2);
  });
});

describe("Pestaña Personalización — sugerencias del logo", () => {
  const PROFILE = {
    name: "Foodtruck Demo",
    logoUrl: "/api/images/business/logo.png",
    primaryColor: "",
    menuFont: "",
  };

  function futureJwt(): string {
    const encode = (value: object) =>
      Buffer.from(JSON.stringify(value)).toString("base64url");
    return `${encode({ alg: "HS256", typ: "JWT" })}.${encode({ sub: "demo", exp: 4102444800 })}.signature`;
  }

  let calls: Array<{ url: string; method: string; body: string | null }> = [];

  function stubFetch(profile: typeof PROFILE) {
    return vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url =
          typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
        const method = (init?.method ?? "GET").toUpperCase();
        calls.push({ url, method, body: typeof init?.body === "string" ? init.body : null });
        if (url === "/api/business/profile" && method === "GET") {
          return { ok: true, status: 200, json: async () => profile };
        }
        if (url === "/api/business/profile" && method === "PUT") {
          return { ok: true, status: 200, json: async () => profile };
        }
        return { ok: false, status: 500, json: async () => ({}) };
      }),
    );
  }

  beforeEach(() => {
    localStorage.clear();
    for (const key of ["token", "businessId", "role", "username", "expiresAt"]) {
      localStorage.setItem(key, key === "token" ? futureJwt() : key === "expiresAt" ? "2030-01-01T00:00:00Z" : "1");
    }
    localStorage.setItem("username", "demo");
    calls = [];
    suggestMockFn.mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  async function openPersonalization(profile: typeof PROFILE) {
    const { render } = await import("@testing-library/react");
    const { default: Admin } = await import("../src/pages/Admin");
    stubFetch(profile);
    render(<Admin />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Local" }));
    await screen.findByDisplayValue("Foodtruck Demo");
    await user.click(screen.getByRole("tab", { name: "Personalización" }));
    return user;
  }

  it("con logo, el botón sugiere colores y aplicar uno lo manda en el PUT", async () => {
    suggestMockFn.mockResolvedValue(["#FF0000", "#00C800"]);
    const user = await openPersonalization(PROFILE);

    await user.click(screen.getByRole("button", { name: "Sugerir desde el logo" }));
    const group = await screen.findByRole("group", { name: "Colores sugeridos del logo" });
    expect(group).toBeTruthy();

    await user.click(screen.getByRole("button", { name: "Sugerido #FF0000" }));
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));

    const put = calls.find((c) => c.method === "PUT" && c.url === "/api/business/profile");
    expect(JSON.parse(put!.body ?? "{}").primaryColor).toBe("#FF0000");
  });

  it("sin logo no hay botón de sugerencias", async () => {
    const user = await openPersonalization({ ...PROFILE, logoUrl: "" });
    expect(screen.queryByRole("button", { name: "Sugerir desde el logo" })).toBeNull();
  });

  it("si el análisis falla, error legible y la pestaña sigue usable", async () => {
    suggestMockFn.mockRejectedValue(new Error("No se pudo analizar el logo"));
    const user = await openPersonalization(PROFILE);

    await user.click(screen.getByRole("button", { name: "Sugerir desde el logo" }));
    await waitFor(() => {
      expect(screen.getByRole("alert").textContent).toContain("No se pudo analizar el logo");
    });
    // La pestaña sigue usable: el color manual sigue ahí.
    expect(screen.getByRole("group", { name: "Color de la carta" })).toBeTruthy();
  });
});
