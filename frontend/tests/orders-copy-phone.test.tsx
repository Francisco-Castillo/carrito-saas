/**
 * Copy-phone button in the orders console: next to every WhatsApp phone link
 * there is a button that copies the customer's number — in the SAME
 * international format the wa.me link uses, so pasting it into any chat app
 * just works.
 *
 * The panel is served over plain HTTP on the LAN (http://192.168.1.5:9090),
 * which is NOT a secure context: navigator.clipboard does not exist there.
 * The button must fall back to document.execCommand("copy").
 */
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import OrderListCard from "../src/pages/orders/OrderListCard";
import type { ManagementOrder } from "../src/pages/orders/api";

function orderWithPhone(phone: string): ManagementOrder {
  return {
    id: 1,
    orderNumber: 1001,
    status: "NEW",
    orderType: "RETIRO",
    customerName: "Juan Pérez",
    customerPhone: phone,
    createdAt: new Date().toISOString(),
    total: 1500,
    items: [{ name: "Milanesa", quantity: 1 }],
  } as unknown as ManagementOrder;
}

const baseProps = {
  onAdvance: vi.fn(),
  onCancel: vi.fn(),
  onDetail: vi.fn(),
  busy: false,
};

function clipboardStub() {
  const writeText = vi.fn<(text: string) => Promise<void>>(async () => {});
  Object.defineProperty(navigator, "clipboard", {
    value: { writeText },
    configurable: true,
  });
  return writeText;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  // Remove the stub so other tests see the pristine navigator.
  Object.defineProperty(navigator, "clipboard", {
    value: undefined,
    configurable: true,
  });
});

describe("copiar el número de WhatsApp en el panel de pedidos", () => {
  it("copia el número en formato internacional (54…) y confirma la copia", async () => {
    const user = userEvent.setup();
    const writeText = clipboardStub();
    render(<OrderListCard order={orderWithPhone("11 5555 1234")} {...baseProps} />);

    await user.click(screen.getByRole("button", { name: "Copiar el número de WhatsApp" }));

    // Same digits the wa.me link dials: trunk zero stripped, 54 prepended.
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("541155551234"));
    // Visible confirmation: the button flips to its copied state.
    expect(screen.getByRole("button", { name: "Número copiado" })).toBeInTheDocument();
  });

  it("sin clipboard API (HTTP LAN): cae a execCommand y copia igual", async () => {
    const user = userEvent.setup();
    // userEvent.setup() instala su propio stub de Clipboard cuando no existe:
    // lo sacamos para ejercitar el camino legacy real del panel HTTP.
    Object.defineProperty(navigator, "clipboard", {
      value: undefined,
      configurable: true,
    });
    // jsdom no implementa execCommand: se define directo.
    const execCommand = vi.fn(() => true);
    (document as unknown as { execCommand: unknown }).execCommand = execCommand;
    render(<OrderListCard order={orderWithPhone("5491155551234")} {...baseProps} />);

    await user.click(screen.getByRole("button", { name: "Copiar el número de WhatsApp" }));

    await waitFor(() => expect(execCommand).toHaveBeenCalledWith("copy"));
    delete (document as unknown as { execCommand?: unknown }).execCommand;
  });

  it("sin teléfono no hay botón de copia", () => {
    render(<OrderListCard order={orderWithPhone("")} {...baseProps} />);

    expect(
      screen.queryByRole("button", { name: "Copiar el número de WhatsApp" }),
    ).not.toBeInTheDocument();
  });
});
