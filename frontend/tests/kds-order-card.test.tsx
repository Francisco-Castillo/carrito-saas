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

/**
 * Mouse drag of a KDS order card (reported defect: "mover la card con el
 * mouse no funciona como debería"). The card binds mousemove/mouseup to
 * ITSELF and aborts the whole gesture on mouseleave — but a card that
 * follows the pointer while rotating can momentarily slide out from under
 * it: the pointer leaves the node, the drag dies mid-gesture and the card
 * snaps back. Pinned here: the mouse gesture tracks at WINDOW level (it
 * survives the pointer leaving the card) and ends on mouseup anywhere.
 */
function mouseCard() {
  const utils = renderCard(buildOrder({ orderId: 7, status: "NEW" }));
  const card = utils.container.querySelector(".order-card") as HTMLElement;
  return { utils, card };
}

function fireMouse(el: EventTarget, type: string, x = 0, y = 0): void {
  el.dispatchEvent(new MouseEvent(type, { clientX: x, clientY: y, bubbles: true }));
}

describe("KDS order card — mouse drag", () => {
  it("tracks the pointer at window level even when it leaves the card", () => {
    const { card } = mouseCard();

    fireMouse(card, "mousedown", 100, 100);
    // Pointer moves on the WINDOW (outside the card node): the drag must
    // keep following it, not die.
    fireMouse(window, "mousemove", 60, 100);
    expect(card.style.transform).toContain("translate(-40px");

    // A mouseleave of the card mid-gesture must NOT cancel the drag.
    card.dispatchEvent(new MouseEvent("mouseleave"));
    expect(card.style.transform).toContain("translate(-40px");

    fireMouse(window, "mouseup", 60, 100);
  });

  it("advances the order when the mouse swipe passes the threshold", () => {
    const onAdvance = vi.fn();
    const utils = render(
      <OrderCard order={buildOrder({ orderId: 7, status: "NEW" })} now={new Date()} animate={false} onAdvance={onAdvance} onRequestCancel={vi.fn()} />
    );
    const card = utils.container.querySelector(".order-card") as HTMLElement;

    fireMouse(card, "mousedown", 100, 100);
    fireMouse(window, "mousemove", 190, 100); // +90px > 80 threshold
    fireMouse(window, "mouseup", 190, 100);

    expect(onAdvance).toHaveBeenCalledWith(7, "PREPARING");
  });

  it("snaps back when the mouse swipe stays under the threshold", async () => {
    const { card } = mouseCard();

    fireMouse(card, "mousedown", 100, 100);
    fireMouse(window, "mousemove", 120, 100); // +20px < 80 threshold
    fireMouse(window, "mouseup", 120, 100);

    // dragCancel runs the return animation on a 300ms timer.
    await vi.waitFor(() => {
      expect(card.classList.contains("dragging")).toBe(false);
    });
  });
});
