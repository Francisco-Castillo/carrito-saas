import { cleanup } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  advanceOrder,
  cancelOrder,
  loadCancellationReasons,
  loadOrders,
  RestaurantNotFoundError,
  UnauthenticatedError,
} from "../src/pages/kds/api";
import { KDS_TOAST_EVENT, type KdsToastDetail } from "../src/pages/kds/toast";

/**
 * Sesión muerta nunca silenciosa (odd/tasks/kds-session-errors.md S2/S3).
 *
 * El guard de sesión corre una sola vez al montar: un token que expira en
 * pleno turno deja el tablero "funcionando" con todas las llamadas
 * fallando, y el catch histórico se tragaba el error — columnas vacías sin
 * señal ("pantalla muerta"). El contrato: 401/403 en CUALQUIER llamada de
 * la API del KDS se reporta con UnauthenticatedError, la señal explícita
 * que la página traduce en "volver al login".
 */
function fireToasts(): KdsToastDetail[] {
  const seen: KdsToastDetail[] = [];
  const listener = (e: Event) => seen.push((e as CustomEvent<KdsToastDetail>).detail);
  window.addEventListener(KDS_TOAST_EVENT, listener);
  return seen;
}

function fetchAnswering(status: number): void {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify({ message: "Full authentication is required" }), { status }))
  );
}

describe("kds api: 401/403 son una señal explícita de sesión muerta", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it("loadOrders con 401 rechaza con UnauthenticatedError", async () => {
    fetchAnswering(401);

    await expect(loadOrders()).rejects.toBeInstanceOf(UnauthenticatedError);
  });

  it("loadOrders con 403 rechaza con UnauthenticatedError", async () => {
    fetchAnswering(403);

    await expect(loadOrders()).rejects.toBeInstanceOf(UnauthenticatedError);
  });

  it("loadOrders con 404 sigue siendo RestaurantNotFoundError (fatal propio)", async () => {
    fetchAnswering(404);

    await expect(loadOrders()).rejects.toBeInstanceOf(RestaurantNotFoundError);
  });

  it("advanceOrder con 401 rechaza con UnauthenticatedError y no dispara toasts", async () => {
    const toasts = fireToasts();
    fetchAnswering(401);

    await expect(advanceOrder(1, "READY")).rejects.toBeInstanceOf(UnauthenticatedError);
    expect(toasts).toHaveLength(0);
  });

  it("loadCancellationReasons con 401 rechaza con UnauthenticatedError", async () => {
    fetchAnswering(401);

    await expect(loadCancellationReasons()).rejects.toBeInstanceOf(UnauthenticatedError);
  });

  it("cancelOrder con 403 rechaza con UnauthenticatedError", async () => {
    fetchAnswering(403);

    await expect(cancelOrder(1, 2, "nota")).rejects.toBeInstanceOf(UnauthenticatedError);
  });
});
