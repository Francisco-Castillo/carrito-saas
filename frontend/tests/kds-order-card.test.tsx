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

  it("renders the phone as a formatted tel: link when the payload has it", () => {
    // El menú manda los dígitos normalizados (1155551234).
    renderCard(buildOrder({ customerPhone: "1155551234" }));

    const link = screen.getByRole("link", { name: /11 5555-1234/ });
    // En el href van sólo los dígitos: el dialer de la tablet tiene que marcar.
    expect(link).toHaveAttribute("href", "tel:1155551234");
  });

  it("keeps an unknown-length number readable instead of mangling it", () => {
    renderCard(buildOrder({ customerPhone: "123456789012" }));

    const link = screen.getByRole("link", { name: /123456789012/ });
    expect(link).toHaveAttribute("href", "tel:123456789012");
  });

  it("renders no phone element when the phone is null", () => {
    renderCard(buildOrder({ customerPhone: null }));

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    // No empty chip and no literal "null" anywhere on the card.
    expect(document.querySelector(".order-phone")).not.toBeInTheDocument();
    expect(screen.queryByText("null")).not.toBeInTheDocument();
  });
});
