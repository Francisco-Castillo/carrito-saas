/**
 * Avisos del pedido sin alert() del navegador: la confirmación y los errores
 * de validación del pedido se muestran como toast in-app (patrón compartido
 * con kds y admin), nunca como un diálogo nativo que corta la experiencia.
 */
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { UserEvent } from "@testing-library/user-event/dist/cjs/setup/setup";
import Menu from "../src/pages/Menu";

const SLUG = "ok-local";

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

/** Turno amplio de HOY: sin horarios el local computa cerrado y bloquea el submit. */
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

function stubFetch(orderResponse?: { ok: boolean; status: number }) {
  const business = businessOpenNow();
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string | URL | Request) => {
      const u = String(url instanceof Request ? url.url : url);
      if (u.startsWith("/api/orders/menu/")) {
        return {
          ok: orderResponse?.ok ?? true,
          status: orderResponse?.status ?? 200,
          json: async () => ({}),
        };
      }
      if (u.startsWith("/api/menu/")) {
        return okJson({ ...MENU_PAYLOAD, business });
      }
      return okJson({});
    }),
  );
}

async function renderMenuWithItem(orderResponse?: { ok: boolean; status: number }) {
  window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
  stubFetch(orderResponse);
  const user = userEvent.setup();
  render(<Menu />);
  await screen.findByText("Milanesa");

  await user.click(screen.getByRole("button", { name: "Agregar Milanesa" }));
  await user.click(screen.getByRole("button", { name: "Ver pedido" }));
  return user;
}

async function typeName(user: UserEvent) {
  const cart = screen.getByRole("complementary", { name: "Tu pedido" });
  await user.type(
    cart.querySelector<HTMLInputElement>(".order-input[type='text']")!,
    "Juan Pérez",
  );
}

async function typeNameAndPhone(user: UserEvent) {
  await typeName(user);
  const cart = screen.getByRole("complementary", { name: "Tu pedido" });
  await user.type(
    cart.querySelector<HTMLInputElement>(".order-input[type='tel']")!,
    "1155551234",
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("avisos del pedido en el menú público", () => {
  it("muestra el aviso de validación como toast in-app y no usa alert()", async () => {
    const alertSpy = vi.spyOn(window, "alert").mockImplementation(() => {});
    const user = await renderMenuWithItem();
    // Sin celular: la validación tiene que cortar antes del POST.
    await typeName(user);

    await user.click(screen.getByRole("button", { name: "Hacer el pedido" }));

    const toast = await screen.findByRole("alert");
    expect(toast).toHaveTextContent("El celular es obligatorio");
    expect(alertSpy).not.toHaveBeenCalled();
  });

  // Timeout propio: el caso espera el auto-dismiss del toast (4,5 s) y bajo
  // carga paralela los 5 s default no alcanzan.
  it("confirma el pedido con un toast in-app de éxito y no usa alert()", { timeout: 15_000 }, async () => {
    const alertSpy = vi.spyOn(window, "alert").mockImplementation(() => {});
    const user = await renderMenuWithItem();
    await typeNameAndPhone(user);

    await user.click(screen.getByRole("button", { name: "Hacer el pedido" }));

    const toast = await screen.findByRole("status");
    expect(toast).toHaveTextContent("¡Pedido recibido! Ya lo enviamos a la cocina.");
    expect(alertSpy).not.toHaveBeenCalled();

    // El toast se va solo: no queda un aviso eterno en pantalla.
    await vi.waitFor(
      () => expect(screen.queryByRole("status")).not.toBeInTheDocument(),
      { timeout: 7000 },
    );
  });

  it("anuncia el fallo de envío como toast in-app de error y no usa alert()", async () => {
    const alertSpy = vi.spyOn(window, "alert").mockImplementation(() => {});
    const user = await renderMenuWithItem({ ok: false, status: 500 });
    await typeNameAndPhone(user);

    await user.click(screen.getByRole("button", { name: "Hacer el pedido" }));

    const toast = await screen.findByRole("alert");
    expect(toast).toHaveTextContent(/No pudimos enviar tu pedido al local/);
    expect(alertSpy).not.toHaveBeenCalled();
  });
});
