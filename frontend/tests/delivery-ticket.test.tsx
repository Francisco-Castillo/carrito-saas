/**
 * Delivery ticket layout (delivery-ticket-copy S3): the mirror of the kitchen
 * comanda — it carries everything the driver needs (customer, phone, address,
 * items with options, TOTAL and the payment line) and nothing the kitchen
 * keeps (no option surcharges). The payment line is dynamic: TRANSFERENCIA
 * orders passed the payment gate, so the ticket says PAGADO; EFECTIVO orders
 * say COBRAR with the amount.
 */
import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import DeliveryTicket from "../src/pages/orders/DeliveryTicket";
import { managementOrder } from "./helpers/ordersFixtures";

const ORDER = managementOrder({
  orderId: 7,
  orderNumber: 101,
  orderType: "DELIVERY",
  customerName: "Juan Pérez",
  customerPhone: "1155551234",
  customerAddress: "Calle Falsa 123",
  paymentMethod: "EFECTIVO",
  notes: "Tocar timbre",
  total: 6400,
  items: [
    {
      name: "Hamburguesa",
      quantity: 2,
      unitPrice: 3200,
      subtotal: 6400,
      selectedOptions: [{ groupName: "Tipo de pan", optionName: "Integral", priceDelta: 200 }],
    },
  ],
});

afterEach(cleanup);

describe("delivery ticket layout", () => {
  it("carries the driver data: customer, phone, address, items, total and payment", () => {
    render(<DeliveryTicket order={ORDER} />);

    const ticket = screen.getByTestId("delivery-ticket");
    expect(within(ticket).getByText(/#101/)).toBeTruthy();
    expect(within(ticket).getByText(/DELIVERY/i)).toBeTruthy()
    expect(within(ticket).getByText(/Juan Pérez/)).toBeTruthy();
    expect(within(ticket).getByText(/1155551234/)).toBeTruthy();
    expect(within(ticket).getByText(/Calle Falsa 123/)).toBeTruthy();
    expect(within(ticket).getByText(/2\s*×\s*Hamburguesa/)).toBeTruthy();
    expect(within(ticket).getByText(/Tipo de pan: Integral/)).toBeTruthy();
    // El total y la línea de cobro ambos llevan el monto: al menos uno.
    expect(within(ticket).getAllByText(/\$\s*6\.400/).length).toBeGreaterThanOrEqual(1);
    // EFECTIVO: the driver collects on hand-off.
    expect(within(ticket).getByText(/COBRAR \$ 6\.400|COBRAR\s*\$\s*6\.400/)).toBeTruthy();
    expect(within(ticket).getByText(/Tocar timbre/)).toBeTruthy();
  });

  it("a TRANSFERENCIA order was paid through the gate: the ticket says PAGADO, not COBRAR", () => {
    render(<DeliveryTicket order={{ ...ORDER, paymentMethod: "TRANSFERENCIA" }} />);

    const ticket = screen.getByTestId("delivery-ticket");
    expect(within(ticket).getByText(/PAGADO/i)).toBeTruthy();
    expect(within(ticket).queryByText(/COBRAR/)).toBeNull();
  });

  it("no option surcharges and no kitchen-only data on the delivery copy", () => {
    render(<DeliveryTicket order={ORDER} />);

    const ticket = screen.getByTestId("delivery-ticket");
    // The surcharge is money detail for the customer's receipt, not the
    // delivery copy — the TOTAL already carries it.
    expect(within(ticket).queryByText(/\+\$\s*200/)).toBeNull();
    expect(within(ticket).queryByText(/COMANDA/i)).toBeNull();
  });

  it("a SALON order shows the table instead of the address", () => {
    render(
      <DeliveryTicket
        order={{
          ...ORDER,
          orderType: "SALON",
          table: { id: 3, name: "Mesa 5", number: 5 },
          customerAddress: null,
        }}
      />
    );

    const ticket = screen.getByTestId("delivery-ticket");
    expect(within(ticket).getByText(/Mesa 5/)).toBeTruthy();
    expect(within(ticket).queryByText(/Calle Falsa/)).toBeNull();
  });
});
