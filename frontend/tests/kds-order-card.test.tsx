/**
 * KDS order card phone chip: when the kitchen payload carries the customer's
 * phone, the card must render it as a tap-to-call `tel:` link (a cook can
 * call from a tablet); when it is null, no phone element at all — no empty
 * chip and no "null" text.
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

  // El KDS prioriza la información del pedido (S6 de odd/tasks/orders-console.md):
  // el teléfono ya no se muestra — el contacto con el cliente vive en la consola
  // de /pedidos, que abre WhatsApp. La cocina no llama ni chatea.

  it("renders no phone element even when the payload has a phone", () => {
    renderCard(buildOrder({ customerPhone: "1155551234" }));

    expect(document.querySelector(".order-phone")).not.toBeInTheDocument();
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
