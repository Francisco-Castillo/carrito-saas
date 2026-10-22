/**
 * Pestaña "Personalización" de la vista Local (panel admin).
 *
 * Reglas que fija:
 *   - La pestaña existe y aloja la personalización visual de la carta: el
 *     color de marca (mudado desde "Datos del local") y el selector de fuente.
 *   - "Datos del local" ya NO tiene el grupo del color: vive acá.
 *   - Elegir una fuente curada y guardar envía su slug en `menuFont` del
 *     PUT /api/business/profile.
 *   - La fuente cargada del perfil arranca seleccionada; sin fuente no hay
 *     ninguna marcada (la carta usa Inter por defecto).
 *   - Solo se ofrecen las fuentes curadas: el dueño no puede inyectar un
 *     slug fuera de la lista (el backend revalida igual, 400).
 *
 * Nota de navegación: el Admin arranca en la vista "dashboard", así que todo
 * test que quiera el perfil del local tiene que hacer clic en el botón
 * "Local" del sidebar ANTES de buscar contenido de LocalSection — sin ese
 * click, LocalSection ni siquiera se monta y el GET del perfil nunca ocurre.
 */
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Admin from "../src/pages/Admin";

function futureJwt(): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${encode({ alg: "HS256", typ: "JWT" })}.${encode({ sub: "demo", exp: 4102444800 })}.signature`;
}

const TOKEN = futureJwt();

const PROFILE = {
  name: "Foodtruck Demo",
  primaryColor: "#7C3AED",
  menuFont: "",
};

interface RecordedCall {
  url: string;
  method: string;
  body: string | null;
}

let calls: RecordedCall[] = [];

function stubFetch(profile: typeof PROFILE = PROFILE) {
  return vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      const method = (init?.method ?? "GET").toUpperCase();
      calls.push({ url, method, body: typeof init?.body === "string" ? init.body : null });
      if (url === "/api/business/profile" && method === "GET") {
        return { ok: true, status: 200, json: async () => profile };
      }
      if (url === "/api/business/profile" && method === "PUT") {
        return { ok: true, status: 200, json: async () => profile };
      }
      // Dashboard loaders y demás: fallan en silencio, como en el resto.
      return { ok: false, status: 500, json: async () => ({}) };
    }),
  );
}

function seedStorage() {
  localStorage.setItem("token", TOKEN);
  localStorage.setItem("businessId", "1");
  localStorage.setItem("role", "OWNER");
  localStorage.setItem("username", "demo");
  localStorage.setItem("expiresAt", "2030-01-01T00:00:00Z");
}

function putCall(): RecordedCall {
  const put = calls.find((c) => c.method === "PUT" && c.url === "/api/business/profile");
  expect(put, "a PUT /api/business/profile must have been issued").toBeDefined();
  return put!;
}

/** Renderiza el Admin, entra a "Local" y espera el GET del perfil. */
async function openLocalView() {
  render(<Admin />);
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Local" }));
  await screen.findByDisplayValue("Foodtruck Demo");
  return user;
}

beforeEach(() => {
  localStorage.clear();
  seedStorage();
  calls = [];
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Pestaña Personalización de la vista Local", () => {
  it("existe la pestaña y aloja el color de la carta, que ya no vive en Datos del local", async () => {
    stubFetch();
    const user = await openLocalView();

    await user.click(screen.getByRole("tab", { name: "Personalización" }));
    expect(screen.getByRole("group", { name: "Color de la carta" })).toBeTruthy();

    await user.click(screen.getByRole("tab", { name: "Datos del local" }));
    expect(screen.queryByRole("group", { name: "Color de la carta" })).toBeNull();
  });

  it("elegir una fuente curada y guardar envía su slug en menuFont", async () => {
    stubFetch();
    const user = await openLocalView();
    await user.click(screen.getByRole("tab", { name: "Personalización" }));

    await user.click(screen.getByRole("button", { name: "Poppins" }));
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));

    expect(JSON.parse(putCall().body ?? "{}").menuFont).toBe("poppins");
    // El color elegido en la misma pestaña viaja también (el PUT es completo).
    expect(JSON.parse(putCall().body ?? "{}").primaryColor).toBe("#7C3AED");
  });

  it("la fuente cargada del perfil arranca seleccionada", async () => {
    stubFetch({ ...PROFILE, menuFont: "lora" });
    const user = await openLocalView();
    await user.click(screen.getByRole("tab", { name: "Personalización" }));

    expect(screen.getByRole("button", { name: "Lora" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Poppins" })).toHaveAttribute("aria-pressed", "false");
  });

  it("sin fuente no hay ninguna seleccionada y guardar no la inventa", async () => {
    stubFetch();
    const user = await openLocalView();
    await user.click(screen.getByRole("tab", { name: "Personalización" }));

    expect(screen.getByRole("button", { name: "Poppins" })).toHaveAttribute("aria-pressed", "false");
    await user.click(screen.getByRole("button", { name: "Guardar cambios" }));
    expect(JSON.parse(putCall().body ?? "{}").menuFont).toBe("");
  });

  it("al abrir la pestaña carga las familias curadas para la vista previa", async () => {
    // Sin este load, los previews piden font-family que no existe y TODAS las
    // opciones se ven idénticas (bug reportado por el dueño).
    stubFetch();
    const user = await openLocalView();
    await user.click(screen.getByRole("tab", { name: "Personalización" }));

    const hrefs = [...document.querySelectorAll<HTMLLinkElement>('link[href*="fonts.googleapis.com"]')]
      .map((l) => l.href)
      .join(" ");
    for (const family of ["Poppins", "Montserrat", "Playfair+Display", "Lora", "Nunito"]) {
      expect(hrefs).toContain(`family=${family}`);
    }
  });
});
