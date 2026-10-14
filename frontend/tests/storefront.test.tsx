/**
 * La portada (storefront) del menú público: banner, logo, nombre, insignia de
 * abierto/cerrado con horarios de hoy, y la fila de contacto (WhatsApp,
 * Instagram y dirección como enlace a Maps). Cada dato es opcional: cuando
 * falta, su elemento desaparece sin dejar huecos.
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import Menu from "../src/pages/Menu";
import Storefront from "../src/pages/menu/Storefront";
import type { StorefrontBusiness } from "../src/pages/menu/types";

// Payload realista, tal como lo sirve GET /api/menu/{slug} dentro de `business`.
const BUSINESS: StorefrontBusiness = {
  id: 990000001,
  name: "Foodtruck Demo",
  slug: "demo-e2e",
  whatsappNumber: "1123456789",
  logoUrl: "/dev-media/logo-local.jpg",
  bannerUrl: "/dev-media/banner-local.jpg",
  address: "Av. Siempreviva 742, Springfield",
  instagramUrl: "https://instagram.com/foodtruckdemo",
  hours: [
    { dayOfWeek: 1, opensAt: "11:00:00", closesAt: "23:00:00", closed: false },
    { dayOfWeek: 5, opensAt: "11:00:00", closesAt: "00:30:00", closed: false },
    { dayOfWeek: 7, opensAt: null, closesAt: null, closed: true },
  ],
};

// Lunes 15:00 de enero de 2024 (1/1/2024 es lunes): el local está abierto con
// el horario del lunes (11:00–23:00). Reloj congelado para copy determinista.
const MONDAY_AFTERNOON = new Date(2024, 0, 1, 15, 0, 0);

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function renderStorefront(business: StorefrontBusiness | null | undefined) {
  vi.useFakeTimers();
  vi.setSystemTime(MONDAY_AFTERNOON);
  return render(<Storefront business={business} />);
}

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data });

describe("Storefront — portada completa", () => {
  it("renderiza banner, logo, nombre, insignia abierta, línea de hoy y contacto", () => {
    renderStorefront(BUSINESS);

    // Banner con su foto y alt.
    expect(screen.getByAltText("Portada de Foodtruck Demo")).toHaveAttribute(
      "src",
      "/dev-media/banner-local.jpg",
    );
    // Logo circular junto al nombre.
    expect(screen.getByAltText("Logo de Foodtruck Demo")).toHaveAttribute(
      "src",
      "/dev-media/logo-local.jpg",
    );
    expect(screen.getByRole("heading", { name: "Foodtruck Demo" })).toBeInTheDocument();

    // Lunes 15:00 con rango 11:00–23:00: abierto, cierra HOY a las 23:00.
    expect(screen.getByText("Abierto")).toHaveClass("is-open");
    expect(screen.getByText("Cierra a las 23:00")).toBeInTheDocument();
    expect(screen.getByText("Hoy: 11:00–23:00")).toBeInTheDocument();

    // Fila de contacto: enlaces externos en pestaña nueva, sin referer.
    const wa = screen.getByRole("link", { name: "WhatsApp" });
    expect(wa).toHaveAttribute("href", "https://wa.me/1123456789");
    expect(wa).toHaveAttribute("target", "_blank");
    expect(wa).toHaveAttribute("rel", "noreferrer noopener");

    const ig = screen.getByRole("link", { name: "Instagram" });
    expect(ig).toHaveAttribute("href", "https://instagram.com/foodtruckdemo");
    expect(ig).toHaveAttribute("rel", "noreferrer noopener");

    // La dirección es un enlace a Maps construido con la dirección codificada
    // (no hay coordenadas guardadas).
    const maps = screen.getByRole("link", { name: "Av. Siempreviva 742, Springfield" });
    expect(maps).toHaveAttribute(
      "href",
      "https://www.google.com/maps/search/?api=1&query=" +
        encodeURIComponent("Av. Siempreviva 742, Springfield"),
    );
    expect(maps).toHaveAttribute("rel", "noreferrer noopener");
  });

  it("viernes 11:00 → 00:30 a la tarde: abierto con cierre MAÑANA (honesto)", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2024, 0, 5, 15, 0, 0)); // viernes 15:00
    render(<Storefront business={BUSINESS} />);

    expect(screen.getByText("Abierto")).toBeInTheDocument();
    expect(screen.getByText("Cierra mañana a las 00:30")).toBeInTheDocument();
    expect(screen.getByText("Hoy: 11:00–00:30")).toBeInTheDocument();
  });

  it("domingo cerrado: insignia Cerrado y próxima apertura mañana", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2024, 0, 7, 12, 0, 0)); // domingo 12:00
    render(<Storefront business={BUSINESS} />);

    expect(screen.getByText("Cerrado")).toHaveClass("is-closed");
    expect(screen.getByText("Abre mañana a las 11:00")).toBeInTheDocument();
    expect(screen.getByText("Hoy: cerrado")).toBeInTheDocument();
  });
});

describe("Storefront — cada dato es opcional y desaparece sin dejar hueco", () => {
  it("sin bannerUrl: no hay bloque de banner (ni espacio vacío)", () => {
    renderStorefront({ ...BUSINESS, bannerUrl: null });
    expect(document.querySelector(".storefront-banner")).toBeNull();
    expect(screen.getByRole("heading", { name: "Foodtruck Demo" })).toBeInTheDocument();
  });

  it("sin logoUrl: no hay imagen de logo", () => {
    renderStorefront({ ...BUSINESS, logoUrl: null });
    expect(document.querySelector(".storefront-logo")).toBeNull();
  });

  it("sin whatsappNumber: no hay enlace a WhatsApp", () => {
    renderStorefront({ ...BUSINESS, whatsappNumber: null });
    expect(screen.queryByRole("link", { name: "WhatsApp" })).toBeNull();
  });

  it("sin instagramUrl: no hay enlace a Instagram", () => {
    renderStorefront({ ...BUSINESS, instagramUrl: null });
    expect(screen.queryByRole("link", { name: "Instagram" })).toBeNull();
  });

  it("sin address: no hay enlace a Maps", () => {
    renderStorefront({ ...BUSINESS, address: null });
    expect(
      screen.queryByRole("link", { name: /Siempreviva/ }),
    ).toBeNull();
    expect(document.querySelectorAll('a[href*="google.com/maps"]')).toHaveLength(0);
  });

  it("sin horarios (ausentes o lista vacía): ni insignia ni línea de hoy", () => {
    for (const hours of [undefined, []]) {
      renderStorefront({ ...BUSINESS, hours });
      expect(screen.queryByText("Abierto")).toBeNull();
      expect(screen.queryByText("Cerrado")).toBeNull();
      expect(screen.queryByText(/Hoy:/)).toBeNull();
      cleanup();
    }
  });

  it("sin portada (business null o undefined): no renderiza nada", () => {
    const { container } = renderStorefront(null);
    expect(container).toBeEmptyDOMElement();
    cleanup();
    const { container: container2 } = renderStorefront(undefined);
    expect(container2).toBeEmptyDOMElement();
  });
});

describe("Storefront dentro de la carta (Menu)", () => {
  const MENU_PAYLOAD = {
    products: [
      {
        id: 101,
        name: "Milanesa",
        description: "con papas",
        price: 100,
        categoryId: 1,
        categoryName: "Platos",
        imageUrl: "img.jpg",
      },
    ],
    combos: [],
  };

  function stubMenuFetch(menuPayload: unknown) {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string | URL | Request) => {
        const u = String(url instanceof Request ? url.url : url);
        if (u.startsWith("/api/restaurants/slug/")) {
          return okJson({ name: "Local OK", whatsappNumber: "" });
        }
        if (u.startsWith("/api/menu/")) return okJson(menuPayload);
        return okJson({});
      }),
    );
  }

  it("se renderiza ARRIBA de la barra sticky con el business del menú", async () => {
    window.history.replaceState({}, "", "/?restaurant=ok-local");
    stubMenuFetch({ ...MENU_PAYLOAD, business: BUSINESS });
    render(<Menu />);

    await screen.findByText("Milanesa");
    expect(screen.getByAltText("Portada de Foodtruck Demo")).toBeInTheDocument();

    // Orden: la portada va antes que la barra sticky (identidad + píldoras).
    const sticky = document.querySelector(".menu-sticky")!;
    expect(sticky).not.toBeNull();
    expect(sticky.previousElementSibling?.className).toContain("storefront");
  });

  it("menú sin business: sin portada y el resto de la carta intacto", async () => {
    window.history.replaceState({}, "", "/?restaurant=ok-local");
    stubMenuFetch(MENU_PAYLOAD);
    render(<Menu />);

    await screen.findByText("Milanesa");
    expect(screen.queryByAltText(/Portada/)).toBeNull();
    expect(screen.queryByText(/Hoy:/)).toBeNull();
    // La barra sticky sigue igual.
    expect(document.querySelector(".menu-sticky")).not.toBeNull();
    expect(screen.getByText("Milanesa")).toBeInTheDocument();
  });
});
