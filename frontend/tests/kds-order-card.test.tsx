/**
 * KDS order card customer contact: the kitchen card renders NO phone element
 * at all — no tel: link, no WhatsApp, no chip — whether the payload carries a
 * phone or not (S6 of odd/tasks/orders-console.md: customer contact lives in
 * the /pedidos console, which opens WhatsApp). Pinned here so a regression
 * cannot reintroduce it: absence is asserted against the concrete class and
 * against any contact link (tel:/wa.me), class or role notwithstanding.
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import OrderCard from "../src/pages/kds/OrderCard";
import type { KdsOrder } from "../src/pages/kds/api";

function buildOrder(overrides: Partial<KdsOrder> = {}): KdsOrder {
  return {
    orderId: 1,
    orderNumber: 12,
    customerName: "Cliente Test",
    orderType: "RETIRO",
    status: "NEW",
    notes: null,
    createdAt: new Date().toISOString(),
    items: [{ quantity: 1, productName: "Milanesa" }],
    ...overrides,
  };
}

function renderCard(order: KdsOrder) {
  return render(
    <OrderCard
      order={order}
      now={new Date()}
      animate={false}
      onAdvance={vi.fn()}
      onRequestCancel={vi.fn()}
    />
  );
}

describe("KDS order card — customer phone", () => {
  afterEach(cleanup);

  // Preferencias por ítem ("sin mayonesa"): la cocina las tiene que ver en
  // la tarjeta, junto al ítem — es el destino final de la feature.
  it("renders the item note next to the item for the kitchen", () => {
    renderCard(buildOrder({
      items: [{ quantity: 1, productName: "Milanesa", notes: "sin mayonesa, sin lechuga" }],
    }));

    expect(screen.getByText("sin mayonesa, sin lechuga")).toBeTruthy();
  });

  // El KDS prioriza la información del pedido (S6 de odd/tasks/orders-console.md):
  // el teléfono ya no se muestra — el contacto con el cliente vive en la consola
  // de /pedidos, que abre WhatsApp. La cocina no llama ni chatea.

  it("renders no phone element even when the payload has a phone", () => {
    renderCard(buildOrder({ customerPhone: "1155551234" }));

    expect(document.querySelector(".order-phone")).not.toBeInTheDocument();
    // Sin ningún link de contacto, aunque la clase o el rol cambien: ni tel:
    // ni WhatsApp pueden volver por una regresión de estilo.
    expect(
      document.querySelector('a[href^="tel:"], a[href^="wa.me"]')
    ).not.toBeInTheDocument();
  });

  it("renders no link at all on the card", () => {
    renderCard(buildOrder({ customerPhone: "1155551234" }));

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("renders no phone element when the phone is null", () => {
    renderCard(buildOrder({ customerPhone: null }));

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    // No empty chip and no literal "null" anywhere on the card.
    expect(document.querySelector(".order-phone")).not.toBeInTheDocument();
    expect(screen.queryByText("null")).not.toBeInTheDocument();
  });
});
