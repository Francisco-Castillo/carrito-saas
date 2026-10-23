/**
 * El KDS NO pide fullscreen en el primer click.
 *
 * Herencia del legacy (kds.js:299-303): el primer click del operador —
 * normalmente el cambio de estado de una card — disparaba
 * documentElement.requestFullscreen() y en el monitor real la pantalla
 * quedaba en negro (el fullscreen entra en medio del click, que además
 * avanza la card y re-renderiza). Reporte del dueño 2026-10-23.
 *
 * El desbloqueo de audio sigue (kds/audio.ts); el fullscreen automático no
 * vuelve: si la cocina lo quiere, será un control explícito.
 */
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Kds from "../src/pages/Kds";

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data });

beforeEach(() => {
  localStorage.clear();
  // hasPanelSession exige token + slug de negocio con JWT parseable y vigente.
  localStorage.setItem("token", "header.payload.signature");
  localStorage.setItem("restaurantSlug", "demo-e2e");
  localStorage.setItem("username", "e2e-kitchen");
  localStorage.setItem("expiresAt", String(Date.now() + 60_000));
  vi.stubGlobal("fetch", vi.fn(async () => okJson([])));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("kds: sin fullscreen automático", () => {
  it("el primer click (cambiar estado de una card) NO pide fullscreen", async () => {
    const requestFullscreen = vi.fn(() => Promise.resolve());
    (document.documentElement as unknown as { requestFullscreen: unknown }).requestFullscreen =
      requestFullscreen;

    const user = userEvent.setup();
    render(<Kds />);

    // La página montó: los <audio> del KDS están siempre presentes y los
    // listeners del effect ya están registrados.
    await vi.waitFor(() =>
      expect(document.getElementById("newOrderSound")).toBeTruthy(),
    );

    await user.click(document.body);

    expect(requestFullscreen).not.toHaveBeenCalled();
    // jsdom no implementa fullscreenElement (undefined): lo que pina el
    // contrato es que NADIE llamó a requestFullscreen.
    expect(document.fullscreenElement ?? null).toBeNull();
  });
});
