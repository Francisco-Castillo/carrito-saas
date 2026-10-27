import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Volver al login con la sesión muerta (odd/tasks/kds-session-errors.md S5).
 *
 * El hook ya marca fatal "unauthenticated"; la página lo traduce en limpiar
 * la sesión (token + slug, misma semántica que logoutKds) y navegar al
 * login. La navegación vive a nivel página, nunca en el scope del hook
 * (incidente histórico: un redirect en scope de módulo re-cargaba el login
 * en bucle).
 */

const logoutKdsSpy = vi.fn();

vi.mock("../src/pages/kds/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/pages/kds/api")>();
  return {
    ...actual,
    // Espía que envuelve la implementación REAL: el contrato S5 es que la
    // página invoque logoutKds (que limpia token+slug y navega), no una
    // función propia. jsdom loguea "Not implemented: navigation" al asignar
    // window.location.href; no afecta las aserciones.
    logoutKds: (...args: Parameters<typeof actual.logoutKds>) => {
      logoutKdsSpy(...args);
      return actual.logoutKds(...args);
    },
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

import Kds from "../src/pages/Kds";

function answerUnauthorized(): void {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify({ message: "expired" }), { status: 401 }))
  );
}

beforeEach(() => {
  logoutKdsSpy.mockClear();
  localStorage.clear();
  // hasPanelSession exige token + slug de negocio con JWT parseable y vigente.
  localStorage.setItem("token", "header.payload.signature");
  localStorage.setItem("restaurantSlug", "demo-e2e");
  localStorage.setItem("username", "e2e-kitchen");
  localStorage.setItem("expiresAt", String(Date.now() + 60_000));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("kds: sesión muerta vuelve al login", () => {
  it("un 401 en la carga limpia la sesión y navega al login vía logoutKds", async () => {
    answerUnauthorized();

    render(<Kds />);

    await vi.waitFor(() => expect(logoutKdsSpy).toHaveBeenCalled());
    expect(localStorage.getItem("token")).toBeNull();
    expect(localStorage.getItem("restaurantSlug")).toBeNull();
  });

  it("una sesión viva NO dispara el logout", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify([]), { status: 200 }))
    );

    render(<Kds />);

    await vi.waitFor(() =>
      expect(document.getElementById("newOrderSound")).toBeTruthy()
    );
    // Dejo respirar al effect de fatal: no hay que llamarlo jamás.
    await new Promise((r) => setTimeout(r, 50));

    expect(logoutKdsSpy).not.toHaveBeenCalled();
    expect(localStorage.getItem("token")).toBe("header.payload.signature");
  });
});
