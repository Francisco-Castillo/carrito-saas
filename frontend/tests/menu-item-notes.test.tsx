/**
 * Notas de preferencia por producto ("sin mayonesa, sin lechuga"): el
 * cliente las escribe en el sheet del producto, viajan por ítem en el
 * payload del pedido (items[].notes, validado por el backend, máx 300) y
 * las líneas del carrito con notas distintas NO se mezclan.
 */
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { UserEvent } from "@testing-library/user-event/dist/cjs/setup/setup";
import Menu from "../src/pages/Menu";

const SLUG = "notas-local";

const MENU_PAYLOAD = {
  products: [
    {
      id: 101,
      name: "Milanesa",
      description: "con papas",
      price: 100,
      categoryId: 1,
      categoryName: "Platos",
      imageUrl: "img-milanesa.jpg",
    },
  ],
  combos: [],
};

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data });

function businessOpenNow() {
  const dayOfWeek = ((new Date().getDay() + 6) % 7) + 1;
  return {
    id: 1,
    name: "Local OK",
    slug: SLUG,
    whatsappNumber: "",
    logoUrl: null,
    bannerUrl: null,
    address: null,
    instagramUrl: null,
    hours: [{ dayOfWeek, shifts: [{ opensAt: "00:00", closesAt: "23:59" }] }],
  };
}

const orderPayloads: unknown[] = [];

function stubFetch() {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url);
      if (u.startsWith("/api/orders/menu/")) {
        orderPayloads.push(JSON.parse(String(init?.body)));
        return okJson({});
      }
      if (u.startsWith("/api/menu/")) {
        return okJson({ ...MENU_PAYLOAD, business: businessOpenNow() });
      }
      return okJson({});
    }),
  );
}

async function openSheetAndAdd(user: UserEvent, note?: string): Promise<void> {
  await user.click(screen.getByRole("button", { name: "Agregar Milanesa" }));
  const dialog = screen.getByRole("dialog", { name: "Detalles de Milanesa" });
  if (note !== undefined) {
    await user.type(within(dialog).getByLabelText(/preferencias/i), note);
  }
  await user.click(within(dialog).getByRole("button", { name: "Agregar al pedido" }));
}

async function typeNameAndPhone(user: UserEvent): Promise<void> {
  const cart = screen.getByRole("complementary", { name: "Tu pedido" });
  await user.type(cart.querySelector<HTMLInputElement>(".order-input[type='text']")!, "Juan Pérez");
  await user.type(
    cart.querySelector<HTMLInputElement>(".order-input[type='tel']")!,
    "1155551234",
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  orderPayloads.length = 0;
});

describe("preferencias por producto en la carta", () => {
  it("el texto del sheet viaja por ítem en el payload del pedido", async () => {
    stubFetch();
    const user = userEvent.setup();
    window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
    stubFetch();
    render(<Menu />);
    await screen.findByText("Milanesa");

    await openSheetAndAdd(user, "sin mayonesa, sin lechuga");
    await user.click(screen.getByRole("button", { name: "Ver pedido" }));

    // La línea del carrito muestra la preferencia, no sólo el producto.
    const cart = screen.getByRole("complementary", { name: "Tu pedido" });
    expect(within(cart).getByText(/sin mayonesa, sin lechuga/)).toBeTruthy();

    await typeNameAndPhone(user);
    await user.click(screen.getByRole("button", { name: "Hacer el pedido" }));

    await vi.waitFor(() => expect(orderPayloads.length).toBeGreaterThan(0));
    const items = (orderPayloads[0] as { items: Array<{ notes?: string }> }).items;
    expect(items[0].notes).toBe("sin mayonesa, sin lechuga");
  });

  it("dos líneas del mismo producto con notas distintas no se mezclan", async () => {
    window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
    stubFetch();
    const user = userEvent.setup();
    render(<Menu />);
    await screen.findByText("Milanesa");

    await openSheetAndAdd(user, "sin cebolla");
    await openSheetAndAdd(user, "bien cocida");
    await user.click(screen.getByRole("button", { name: "Ver pedido" }));

    const cart = screen.getByRole("complementary", { name: "Tu pedido" });
    expect(within(cart).getByText(/sin cebolla/)).toBeTruthy();
    expect(within(cart).getByText(/bien cocida/)).toBeTruthy();
    // 2 unidades por línea, no una línea con qty 2 y notas mezcladas.
    expect(within(cart).getAllByText(/Milanesa/).length).toBeGreaterThanOrEqual(2);

    await typeNameAndPhone(user);
    await user.click(screen.getByRole("button", { name: "Hacer el pedido" }));

    await vi.waitFor(() => expect(orderPayloads.length).toBeGreaterThan(0));
    const items = (orderPayloads[0] as { items: Array<{ notes?: string; quantity: number }> }).items;
    expect(items).toHaveLength(2);
    expect(items[0].notes).toBe("sin cebolla");
    expect(items[1].notes).toBe("bien cocida");
  });

  it("el textarea limita las preferencias a 300 caracteres como el backend", async () => {
    window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
    stubFetch();
    const user = userEvent.setup();
    render(<Menu />);
    await screen.findByText("Milanesa");

    await user.click(screen.getByRole("button", { name: "Agregar Milanesa" }));
    const dialog = screen.getByRole("dialog", { name: "Detalles de Milanesa" });
    const textarea = within(dialog).getByLabelText(/preferencias/i) as HTMLTextAreaElement;
    expect(textarea.maxLength).toBe(300);
  });
});
