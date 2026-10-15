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

/** Viernes 5/1 con horario especial 11:00–18:00; la plantilla del viernes cierra a las 17:00. */
const HOLIDAY_BUSINESS: StorefrontBusiness = {
  id: 990000002,
  name: "Foodtruck Demo",
  slug: "demo-e2e",
  hours: [{ dayOfWeek: 5, shifts: [{ opensAt: "11:00:00", closesAt: "17:00:00" }] }],
  dateExceptions: [
    { date: "2024-01-05", shifts: [{ opensAt: "11:00:00", closesAt: "18:00:00" }] },
  ],
};

/** Viernes 5/1 que abre MÁS TARDE que su plantilla: la excepción abre a las 14:00. */
const LATE_OPEN_HOLIDAY: StorefrontBusiness = {
  id: 990000003,
  name: "Foodtruck Demo",
  slug: "demo-e2e",
  hours: [{ dayOfWeek: 5, shifts: [{ opensAt: "11:00:00", closesAt: "23:00:00" }] }],
  dateExceptions: [
    { date: "2024-01-05", shifts: [{ opensAt: "14:00:00", closesAt: "20:00:00" }] },
  ],
};

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

  it("un feriado con horario propio dispara el refresco en SU cierre, no en el de la plantilla", () => {
    vi.useFakeTimers();
    vi.setSystemTime(friday(16, 58));
    renderBadge(HOLIDAY_BUSINESS);

    expect(screen.getByText("Abierto")).toBeInTheDocument();
    expect(screen.getByText("Cierra a las 18:00")).toBeInTheDocument();

    // 17:00:00 — el cierre de la PLANTILLA venció: el badge NO puede voltear
    // acá, aunque el timeout mal agendado diga lo contrario.
    act(() => {
      vi.advanceTimersByTime(2 * 60_000);
    });
    expect(screen.getByText("Abierto")).toBeInTheDocument();

    // 18:00:00 — el cierre de la EXCEPCIÓN: acá sí se refresca.
    act(() => {
      vi.advanceTimersByTime(60 * 60_000);
    });
    expect(screen.getByText("Cerrado")).toHaveClass("is-closed");
  });

  it("un feriado que abre más tarde agenda el refresco en SU apertura, no en la de la plantilla", () => {
    vi.useFakeTimers();
    vi.setSystemTime(friday(13, 58, 0));
    renderBadge(LATE_OPEN_HOLIDAY);

    expect(screen.getByText("Cerrado")).toHaveClass("is-closed");
    expect(screen.getByText("Abre hoy a las 14:00")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(2 * 60_000); // 14:00:00
    });
    expect(screen.getByText("Abierto")).toBeInTheDocument();
    expect(screen.getByText("Cierra a las 20:00")).toBeInTheDocument();
  });

  it("un feriado cerrado de hoy muestra Cerrado aunque la plantilla diga lo contrario", () => {
    vi.useFakeTimers();
    vi.setSystemTime(friday(13, 0));
    renderBadge({
      id: 990000004,
      name: "Foodtruck Demo",
      slug: "demo-e2e",
      hours: [{ dayOfWeek: 5, shifts: [{ opensAt: "11:00:00", closesAt: "23:00:00" }] }],
      dateExceptions: [{ date: "2024-01-05", shifts: [] }],
    });

    expect(screen.getByText("Cerrado")).toHaveClass("is-closed");
    expect(screen.getByText("Hoy: Cerrado")).toBeInTheDocument();
  });
});
