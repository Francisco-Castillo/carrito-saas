import { act, cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import KdsToastHost from "../src/pages/kds/KdsToastHost";
import { KDS_TOAST_EVENT, type KdsToastDetail } from "../src/pages/kds/toast";

/**
 * Toast lifetime contract: a toast must disappear ~3.3s after it APPEARS,
 * no matter how often the board re-renders. The Kds screen re-renders every
 * second (the clock), and every re-render creates a new onDone closure for
 * each toast — if the toast's dismissal timers depend on that prop, they
 * reset every tick and the toast stays on screen FOREVER (observed live:
 * order toasts from minutes earlier still covering the header).
 */
function fireToast(message: string) {
  const detail: KdsToastDetail = { message, type: "info" };
  window.dispatchEvent(new CustomEvent<KdsToastDetail>(KDS_TOAST_EVENT, { detail }));
}

describe("kds toast lifetime survives parent re-renders", () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("a toast is removed ~3.3s after appearing even if the host re-renders every second", () => {
    vi.useFakeTimers();
    const { rerender } = render(<KdsToastHost />);

    fireToast("pedido efímero");

    // Ticks del reloj del Kds: cada uno re-renderiza el host y crea
    // closures onDone nuevos para el toast montado. Los avances de tiempo
    // van en act() para que los setState de los timers se commitan.
    for (let i = 0; i < 3; i++) {
      rerender(<KdsToastHost />);
      act(() => {
        vi.advanceTimersByTime(1000);
      });
    }
    act(() => {
      vi.advanceTimersByTime(700); // t+3.7s: pasado el remove de 3300ms
    });

    expect(document.querySelector(".toast")).toBeNull();
  });

  it("the toast is still visible during its 3s lifetime while re-renders happen", () => {
    vi.useFakeTimers();
    const { rerender } = render(<KdsToastHost />);

    fireToast("pedido visible");

    rerender(<KdsToastHost />);
    vi.advanceTimersByTime(1000);
    expect(document.querySelector(".toast")).not.toBeNull();
    vi.advanceTimersByTime(1500); // t+2.5s
    expect(document.querySelector(".toast")).not.toBeNull();
  });
});
