/**
 * Los precios de la carta, en el formato de plata de la app.
 *
 * El dueño reportó dos lugares con el número crudo ("$3200") y eran cinco:
 * la tarjeta, la hoja de detalle, la línea del carrito (que además multiplica
 * por cantidad), el total del carrito y la barra pegada de abajo. La regla es
 * una sola y se verifica en todas: la plata se ve "$ 3.200", nunca "$3200"
 * ni "$3200.5". El total del mensaje de WhatsApp también, porque es el número
 * que el local lee en el teléfono.
 *
 * La aserción que las cubre a todas de una vez es el regex sobre el texto de
 * la página: `$` pegado a un dígito es exactamente el formato crudo.
 */
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import Menu from "../src/pages/Menu";

const SLUG = "ok-local";
const WHATSAPP = "1155550000";

const MENU_PAYLOAD = {
  products: [
    {
      id: 101,
      name: "Milanesa",
      description: "con papas",
      price: 3200,
      categoryId: 1,
      categoryName: "Platos",
      imageUrl: "img-milanesa.jpg",
    },
  ],
  combos: [],
};

// Semana siempre abierta: createOrder respeta el horario efectivo del local
// (negocio sin filas = cerrado), así que el payload mockeado abre 24/7.
const ALWAYS_OPEN_HOURS = [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({
  dayOfWeek,
  shifts: [
    { opensAt: "00:00", closesAt: "12:00" },
    { opensAt: "12:00", closesAt: "00:00" },
  ],
}))

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data });

function stubFetch(opts: { orderFails?: boolean } = {}) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url);
      if (u.startsWith("/api/restaurants/slug/")) {
        return okJson({ name: "Local OK", whatsappNumber: WHATSAPP });
      }
      if (u.startsWith("/api/menu/")) {
        return okJson({
          ...MENU_PAYLOAD,
          business: { name: "Local OK", whatsappNumber: WHATSAPP, hours: ALWAYS_OPEN_HOURS },
        });
      }
      if (opts.orderFails && u.startsWith("/api/orders/menu/") && init?.method === "POST") {
        return { ok: false, status: 500, json: async () => ({}) };
      }
      return okJson({});
    }),
  );
}

async function renderMenu(opts: { orderFails?: boolean } = {}) {
  window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
  stubFetch(opts);
  render(<Menu />);
  await screen.findByText("Milanesa");
}

/** Texto del primer nodo que matchea el selector (cada superficie lleva su clase). */
function textOf(selector: string): string {
  const node = document.querySelector(selector);
  if (!node) throw new Error(`no está en el DOM: ${selector}`);
  return node.textContent ?? "";
}

/** Agrega una unidad y abre la hoja del carrito (el formulario vive ahí adentro). */
async function openCart(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Agregar Milanesa" }));
  await user.click(within(screen.getByRole("dialog", { name: "Detalles de Milanesa" })).getByRole("button", { name: "Agregar al pedido" }));
  await user.click(screen.getByRole("button", { name: "Ver pedido" }));
  await screen.findByRole("complementary", { name: "Tu pedido" });
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("precios de la carta", () => {
  it("la tarjeta y la hoja de detalle muestran el precio formateado, no el número crudo", async () => {
    const user = userEvent.setup();
    await renderMenu();

    expect(textOf(".product-price")).toBe("$ 3.200");

    await user.click(document.querySelector(".product-open") as HTMLElement);
    await screen.findByRole("dialog");
    expect(textOf(".sheet-price")).toBe("$ 3.200");

    // El crudo, textualmente, ya no está en ningún lado.
    expect(screen.queryByText("$3200")).toBeNull();
  });

  it("el carrito formatea la línea (multiplicando por cantidad) y el total", async () => {
    const user = userEvent.setup();
    await renderMenu();

    // Se vuelve a buscar el botón en cada click: al primer ítem la tarjeta
    // reemplaza el "+" por el stepper, así que el nodo anterior queda suelto.
    for (let i = 0; i < 2; i++) {
      await user.click(screen.getByRole("button", { name: "Agregar Milanesa" }));
      await user.click(within(screen.getByRole("dialog", { name: "Detalles de Milanesa" })).getByRole("button", { name: "Agregar al pedido" }));
    }
    await user.click(screen.getByRole("button", { name: "Ver pedido" }));
    await screen.findByRole("complementary", { name: "Tu pedido" });

    // 2 x 3.200 = 6.400: el valor se conserva y se formatea, no se redondea ni pierde el separador.
    expect(textOf(".cart-line-total")).toBe("$ 6.400");
    expect(textOf(".cart-bar-total")).toBe("$ 6.400");
    expect(textOf(".cart-total")).toContain("$ 6.400");
  });

  it("no queda ningún precio crudo en la carta", async () => {
    const user = userEvent.setup();
    await renderMenu();
    await openCart(user);

    const text = document.body.textContent ?? "";
    expect(text).toContain("$ 3.200");
    expect(text).not.toMatch(/\$\d/);
  });

  it("el mensaje de WhatsApp lleva el total formateado", async () => {
    const user = userEvent.setup();
    // El stub tiene que traer el `search`: la carta saca el slug de la URL, y
    // sin él la página muestra (correctamente) el estado de enlace roto.
    const location = { href: "", search: `?restaurant=${SLUG}`, replace: vi.fn() };
    vi.stubGlobal("location", location);
    vi.spyOn(window, "alert").mockImplementation(() => {});

    // El envío por API falla: el local tiene WhatsApp, así que el pedido sale por ahí.
    await renderMenu({ orderFails: true });
    await openCart(user);

    await user.type(screen.getByPlaceholderText("Nombre y Apellido"), "Cliente Test");
    await user.type(screen.getByPlaceholderText("Celular (ej. 11 5555 1234)"), "11 5555 1234");
    await user.click(screen.getByRole("button", { name: "Hacer el pedido" }));

    await waitFor(() => {
      expect(location.href).toContain("wa.me");
    });
    // Ese total lo lee el local: mismo formato que el resto de la app.
    expect(location.href).toContain("*Total:* $ 3.200");
    expect(location.href).not.toMatch(/\*Total:\* \$\d/);
  });
});
