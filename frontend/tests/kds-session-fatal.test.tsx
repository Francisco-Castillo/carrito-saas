import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Sesión muerta como estado fatal del bucle KDS (odd/tasks/kds-session-errors.md S4).
 *
 * El hook traduce la señal explícita UnauthenticatedError a un fatal propio
 * ("unauthenticated"), distinto del fatal "restaurant-not-found" y de los
 * errores que NO son de sesión: un 500/red conserva los datos y no marca
 * fatal (S6), y un rechazo de advanceOrder por sesión muerta también fatalea
 * el tablero (el operador no puede seguir trabajando con sesión vencida).
 */

const loadOrders = vi.fn();
const advanceOrder = vi.fn();

vi.mock("../src/pages/kds/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/pages/kds/api")>();
  return {
    ...actual,
    loadOrders: (...args: Parameters<typeof actual.loadOrders>) => loadOrders(...args),
    advanceOrder: (...args: Parameters<typeof actual.advanceOrder>) => advanceOrder(...args),
  };
});

vi.mock("../src/pages/kds/audio", () => ({
  playNewOrderSound: vi.fn(),
  playReadySound: vi.fn(),
  attachAudioUnlockListeners: vi.fn(),
}));

vi.mock("@stomp/stompjs", () => ({
  Client: class {
    activate(): void {}
    deactivate(): void {}
    subscribe(): void {}
  },
}));

import { UnauthenticatedError } from "../src/pages/kds/api";
import { useKdsOrders } from "../src/pages/kds/useKdsOrders";

beforeEach(() => {
  loadOrders.mockReset();
  advanceOrder.mockReset();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
});

function renderEnabledHook() {
  return renderHook(() => useKdsOrders("demo-e2e", true));
}

describe("useKdsOrders: sesión muerta es fatal propio", () => {
  it("loadOrders con UnauthenticatedError marca fatal unauthenticated", async () => {
    loadOrders.mockRejectedValue(new UnauthenticatedError());

    const { result } = renderEnabledHook();

    await waitFor(() => expect(result.current.fatal).toBe("unauthenticated"));
  });

  it("un refresh con error que NO es de sesión conserva los datos y no marca fatal", async () => {
    const order = {
      orderId: 1,
      orderNumber: 10,
      customerName: "Cliente",
      customerPhone: null,
      orderType: "RETIRO",
      status: "NEW",
      notes: null,
      createdAt: new Date().toISOString(),
      items: [],
    };
    loadOrders.mockResolvedValueOnce([order]).mockRejectedValueOnce(new Error("Error cargando pedidos"));

    const { result } = renderEnabledHook();

    await waitFor(() => expect(result.current.orders).toHaveLength(1));

    act(() => {
      result.current.refresh();
    });

    await waitFor(() => expect(loadOrders).toHaveBeenCalledTimes(2));

    // kds.js:76-77: la UI conserva los datos en pantalla.
    expect(result.current.orders).toHaveLength(1);
    expect(result.current.fatal).toBeNull();
  });

  it("advance con UnauthenticatedError marca fatal unauthenticated", async () => {
    loadOrders.mockResolvedValue([]);
    advanceOrder.mockRejectedValue(new UnauthenticatedError());

    const { result } = renderEnabledHook();
    await waitFor(() => expect(result.current.fatal).toBeNull());

    act(() => {
      result.current.advance(1, "READY");
    });

    await waitFor(() => expect(result.current.fatal).toBe("unauthenticated"));
  });

  it("advance con rechazo que NO es de sesión re-sincroniza sin fatal", async () => {
    loadOrders.mockResolvedValue([]);
    advanceOrder.mockResolvedValue(false);

    const { result } = renderEnabledHook();
    await waitFor(() => expect(result.current.fatal).toBeNull());

    act(() => {
      result.current.advance(1, "READY");
    });

    await waitFor(() => expect(advanceOrder).toHaveBeenCalled());
    expect(result.current.fatal).toBeNull();
  });
});
