/**
 * Lógica pura de abierto/cerrado de la portada (openStatus.ts).
 *
 * Convención del backend (documentada en BusinessProfileServiceImpl): cuando
 * `closesAt` es MENOR que `opensAt`, el cierre cae en la madrugada del día
 * SIGUIENTE (viernes 11:00 → 00:30 = abre hasta las 00:30 del sábado).
 * `dayOfWeek` es 1 = lunes … 7 = domingo.
 *
 * Enero 2024 para las fechas: el 1/1 es lunes (dayOfWeek 1), el 5/1 viernes,
 * el 6/1 sábado y el 7/1 domingo. Fechas locales (new Date(y, m, d, hh, mm)):
 * cero sorpresas de zona horaria.
 */
import { describe, expect, it } from "vitest";
import { getOpenStatus } from "../src/pages/menu/openStatus";
import type { BusinessHours } from "../src/pages/menu/types";

const d = (day: number, hh: number, mm: number) => new Date(2024, 0, day, hh, mm, 0);

const h = (
  dayOfWeek: number,
  opensAt: string | null,
  closesAt: string | null,
  closed = false,
): BusinessHours => ({ dayOfWeek, opensAt, closesAt, closed });

describe("getOpenStatus — rango del mismo día (opensAt < closesAt)", () => {
  const mondayOnly = [h(1, "11:00:00", "23:00:00")];

  it("está abierto dentro del rango y muestra el cierre de hoy", () => {
    const s = getOpenStatus(mondayOnly, d(1, 15, 0));
    expect(s.open).toBe(true);
    expect(s.closingLine).toBe("Cierra a las 23:00");
  });

  it("está cerrado antes de abrir y después de cerrar", () => {
    expect(getOpenStatus(mondayOnly, d(1, 10, 59)).open).toBe(false);
    expect(getOpenStatus(mondayOnly, d(1, 23, 30)).open).toBe(false);
  });

  it("la apertura es inclusiva y el cierre es exclusivo (en los límites exactos)", () => {
    expect(getOpenStatus(mondayOnly, d(1, 11, 0)).open).toBe(true);
    expect(getOpenStatus(mondayOnly, d(1, 23, 0)).open).toBe(false);
  });
});

describe("getOpenStatus — cierre después de medianoche (closesAt < opensAt)", () => {
  // Solo el viernes: 23:30 → 00:30 (cierra el sábado en la madrugada).
  const fridayNight = [h(5, "23:30:00", "00:30:00")];

  it("lado nocturno: el rango que abrió hoy sigue abierto tras la medianoche", () => {
    const s = getOpenStatus(fridayNight, d(5, 23, 45));
    expect(s.open).toBe(true);
    // Honesto: ese cierre cae MAÑANA (madrugada del sábado).
    expect(s.closingLine).toBe("Cierra mañana a las 00:30");
  });

  it("lado madrugada: lo que derramó AYER mantiene hoy abierto", () => {
    const s = getOpenStatus(fridayNight, d(6, 0, 15));
    expect(s.open).toBe(true);
    // Honesto: el cierre es HOY a las 00:30, no mañana.
    expect(s.closingLine).toBe("Cierra a las 00:30");
  });

  it("el cierre también es exclusivo en la madrugada", () => {
    expect(getOpenStatus(fridayNight, d(6, 0, 30)).open).toBe(false);
    expect(getOpenStatus(fridayNight, d(6, 0, 31)).open).toBe(false);
  });

  it("ejemplo del backend (viernes 11:00 → 00:30) en ambos lados de la noche", () => {
    const hours = [h(5, "11:00:00", "00:30:00")];
    expect(getOpenStatus(hours, d(5, 12, 0)).open).toBe(true);
    expect(getOpenStatus(hours, d(6, 0, 15)).open).toBe(true);
    // Sábado 01:00: la nocturna del viernes ya cerró.
    expect(getOpenStatus(hours, d(6, 1, 0)).open).toBe(false);
    // Y a las 10:00 del sábado tampoco está abierto: el rango cruzado de
    // HOY todavía no abre (abre esta noche).
    expect(getOpenStatus(hours, d(6, 10, 0)).open).toBe(false);
  });

  it("la madrugada de hoy la cubre el rango de AYER, no el cruzado de hoy", () => {
    // Lunes 22:00 → 01:30 y martes 20:00 → 02:00. Martes 01:00: todavía está
    // abierta la nocturna del LUNES (hasta 01:30), no la del martes.
    const hours = [h(1, "22:00:00", "01:30:00"), h(2, "20:00:00", "02:00:00")];
    const s = getOpenStatus(hours, d(2, 1, 0));
    expect(s.open).toBe(true);
    expect(s.closingLine).toBe("Cierra a las 01:30");
  });
});

describe("getOpenStatus — día cerrado, día faltante y sin horarios", () => {
  it("un día marcado closed nunca abre", () => {
    const s = getOpenStatus([h(7, null, null, true)], d(7, 12, 0));
    expect(s.open).toBe(false);
    expect(s.closingLine).toBeNull();
    expect(s.todayLine).toBe("cerrado");
  });

  it("un día que falta en la lista se comporta como cerrado", () => {
    const hours = [h(1, "11:00:00", "23:00:00")];
    const s = getOpenStatus(hours, d(2, 12, 0));
    expect(s.open).toBe(false);
    expect(s.todayLine).toBe("cerrado");
  });

  it("sin horarios (lista vacía, ausente o null): todo cerrado y sin copia", () => {
    for (const hours of [[], undefined, null]) {
      const s = getOpenStatus(hours as BusinessHours[] | null | undefined, d(1, 12, 0));
      expect(s.open).toBe(false);
      expect(s.closingLine).toBeNull();
      expect(s.nextOpeningLine).toBeNull();
      expect(s.todayLine).toBeNull();
    }
  });
});

describe("getOpenStatus — línea de horarios de hoy", () => {
  it("muestra el rango tal cual está guardado, incluso el cierre cruzado", () => {
    const hours = [h(5, "11:00:00", "00:30:00")];
    expect(getOpenStatus(hours, d(5, 12, 0)).todayLine).toBe("11:00–00:30");
    expect(getOpenStatus(hours, d(5, 12, 0)).todayLine).not.toBe("11:00–23:00");
  });
});

describe("getOpenStatus — próxima apertura (copiado)", () => {
  const lunesAViernes = [1, 2, 3, 4, 5].map((day) => h(day, "11:00:00", "23:00:00"));

  it("más tarde hoy", () => {
    const s = getOpenStatus(lunesAViernes, d(1, 9, 0));
    expect(s.open).toBe(false);
    expect(s.nextOpeningLine).toBe("Abre hoy a las 11:00");
  });

  it("mañana (domingo a la noche antes del lunes)", () => {
    const s = getOpenStatus(lunesAViernes, d(7, 20, 0));
    expect(s.nextOpeningLine).toBe("Abre mañana a las 11:00");
  });

  it("día posterior por su nombre (sábado a la noche → el lunes)", () => {
    const s = getOpenStatus(lunesAViernes, d(6, 20, 0));
    expect(s.nextOpeningLine).toBe("Abre el lunes a las 11:00");
  });

  it("salta los días cerrados intermedios con su horario propio", () => {
    // Martes y miércoles cerrados (no están en la lista): el jueves abre a las 12:00.
    const hours = [h(1, "11:00:00", "23:00:00"), h(4, "12:00:00", "22:00:00")];
    const s = getOpenStatus(hours, d(2, 12, 0));
    expect(s.nextOpeningLine).toBe("Abre el jueves a las 12:00");
  });

  it("un rango cruzado que ya derramó hoy vuelve a abrir ESTA NOCHE", () => {
    // Viernes y sábado: 23:30 → 00:30. Sábado 01:00: la nocturna ya cerró.
    const hours = [h(5, "23:30:00", "00:30:00"), h(6, "23:30:00", "00:30:00")];
    const s = getOpenStatus(hours, d(6, 1, 0));
    expect(s.nextOpeningLine).toBe("Abre hoy a las 23:30");
  });

  it("sin ninguna apertura dentro de los siete días siguientes → null", () => {
    const s = getOpenStatus([h(7, null, null, true)], d(1, 12, 0));
    expect(s.nextOpeningLine).toBeNull();
  });
});
