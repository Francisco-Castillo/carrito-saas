/**
 * El badge de abierto/cerrado se refresca SOLO cuando el reloj cruza un borde
 * del horario: la carta puede quedar abierta en una pestaña durante horas y el
 * estado no puede quedarse viejo. Tres disparadores:
 *
 *  1. un timeout agendado en el PRÓXIMO borde (apertura o cierre de algún turno),
 *  2. un re-chequeo acotado de respaldo (reloj que salta, pestaña suspendida),
 *  3. `visibilitychange`: al volver a una pestaña en segundo plano se recalcula.
 *
 * Mismos fixtures que storefront.test.tsx: enero 2024, el 1/1 es lunes,
 * el 5/1 viernes y el 6/1 sábado. Fechas locales, cero sorpresas de zona.
 */
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import Storefront from "../src/pages/menu/Storefront";
import type { StorefrontBusiness } from "../src/pages/menu/types";

const BUSINESS: StorefrontBusiness = {
  id: 990000001,
  name: "Foodtruck Demo",
  slug: "demo-e2e",
  hours: [
    { dayOfWeek: 1, shifts: [{ opensAt: "11:00:00", closesAt: "23:00:00" }] },
    { dayOfWeek: 5, shifts: [{ opensAt: "11:00:00", closesAt: "00:30:00" }] },
    { dayOfWeek: 7, shifts: [] },
  ],
};

const monday = (hh: number, mm: number, ss = 0) => new Date(2024, 0, 1, hh, mm, ss);
const friday = (hh: number, mm: number) => new Date(2024, 0, 5, hh, mm, 0);

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function renderBadge(business: StorefrontBusiness = BUSINESS) {
  return render(<Storefront business={business} />);
}

describe("badge de la portada — se refresca solo al cruzar un borde", () => {
  it("pasa de Abierto a Cerrado al cruzar el minuto de cierre, sin interacción", () => {
    // Monta 22:58:30: el timeout del borde cae a las 23:00:00 y el re-chequeo
    // de respaldo (60 s) recién tocaría 23:00:30 — el que dispara es el borde.
    vi.useFakeTimers();
    vi.setSystemTime(monday(22, 58, 30));
    renderBadge();

    expect(screen.getByText("Abierto")).toHaveClass("is-open");
    expect(screen.getByText("Cierra a las 23:00")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(90_000); // 23:00:00
    });
    expect(screen.getByText("Cerrado")).toHaveClass("is-closed");
    expect(screen.queryByText("Cierra a las 23:00")).toBeNull();
  });

  it("pasa de Cerrado a Abierto al cruzar una apertura", () => {
    vi.useFakeTimers();
    vi.setSystemTime(monday(10, 58));
    renderBadge();

    expect(screen.getByText("Cerrado")).toHaveClass("is-closed");
    expect(screen.getByText("Abre hoy a las 11:00")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(3 * 60_000); // 11:01
    });
    expect(screen.getByText("Abierto")).toHaveClass("is-open");
    expect(screen.getByText("Cierra a las 23:00")).toBeInTheDocument();
  });

  it("un turno que cruza la medianoche sigue abierto después de las 00:00 y cierra en su minuto", () => {
    vi.useFakeTimers();
    vi.setSystemTime(friday(23, 50));
    renderBadge();

    expect(screen.getByText("Abierto")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(25 * 60_000); // sábado 00:15: sigue el derrame
    });
    expect(screen.getByText("Abierto")).toBeInTheDocument();
    expect(screen.getByText("Cierra a las 00:30")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(16 * 60_000); // sábado 00:31
    });
    expect(screen.getByText("Cerrado")).toHaveClass("is-closed");
  });

  it("volver a una pestaña visible tras el borde recalcula (visibilitychange)", () => {
    vi.useFakeTimers();
    vi.setSystemTime(monday(15, 0));
    renderBadge();

    expect(screen.getByText("Abierto")).toBeInTheDocument();

    // La pestaña pasó horas en segundo plano: el reloj ya cruzó el cierre.
    vi.setSystemTime(monday(23, 30));
    act(() => {
      document.dispatchEvent(new Event("visibilitychange"));
    });
    expect(screen.getByText("Cerrado")).toHaveClass("is-closed");
  });

  it("desmontar limpia timers y listeners: no quedan ticks colgados", () => {
    vi.useFakeTimers();
    vi.setSystemTime(monday(15, 0));
    const removeSpy = vi.spyOn(document, "removeEventListener");
    const { unmount } = renderBadge();

    unmount();
    expect(vi.getTimerCount()).toBe(0);
    expect(removeSpy).toHaveBeenCalledWith("visibilitychange", expect.any(Function));
  });

  it("un re-render con los mismos datos no apila listeners", () => {
    vi.useFakeTimers();
    vi.setSystemTime(monday(15, 0));
    const addSpy = vi.spyOn(document, "addEventListener");
    const { rerender } = renderBadge();

    rerender(<Storefront business={{ ...BUSINESS }} />);
    const registrations = addSpy.mock.calls.filter(
      ([type]) => type === "visibilitychange",
    );
    expect(registrations).toHaveLength(1);
  });
});
