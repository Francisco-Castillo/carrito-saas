/**
 * Lógica pura de abierto/cerrado de la portada (openStatus.ts).
 *
 * Modelo del backend (commit e9094fa): `hours` es una lista de días, cada día
 * lleva turnos ORDENADOS (`shifts: [{ opensAt, closesAt }]`) y un día con la
 * lista vacía ES un día cerrado (ya no existe `closed`). Un turno con
 * `closesAt` MENOR que `opensAt` cruza la medianoche (viernes 19:00 → 00:30
 * cierra en la madrugada del sábado). Los tiempos llegan como "HH:mm:ss" o
 * "HH:mm"; ambos formatos conviven en fixtures y payloads.
 *
 * Enero 2024 para las fechas: el 1/1 es lunes (dayOfWeek 1), el 4/1 jueves,
 * el 5/1 viernes, el 6/1 sábado y el 7/1 domingo. Fechas locales
 * (new Date(y, m, d, hh, mm)): cero sorpresas de zona horaria.
 */
import { describe, expect, it } from "vitest";
import { getOpenStatus, nextStatusBoundaryAt } from "../src/pages/menu/openStatus";
import type { BusinessHours, DateException } from "../src/pages/menu/types";

const d = (day: number, hh: number, mm: number) => new Date(2024, 0, day, hh, mm, 0);

/** Día con sus turnos como pares [opensAt, closesAt]; sin pares = día cerrado. */
const h = (dayOfWeek: number, ...pairs: Array<[string, string]>): BusinessHours => ({
  dayOfWeek,
  shifts: pairs.map(([opensAt, closesAt]) => ({ opensAt, closesAt })),
});

/** Excepción por fecha con sus turnos como pares; sin pares = feriado cerrado. */
const ex = (date: string, ...pairs: Array<[string, string]>): DateException => ({
  date,
  shifts: pairs.map(([opensAt, closesAt]) => ({ opensAt, closesAt })),
});

/**
 * Excepción YEARLY: `date` es sólo el año de REFERENCIA guardado; la
 * excepción matchea ese MES/DÍA en cualquier año. Con turnos = horario
 * propio anual; sin turnos = feriado cerrado todos los años.
 */
const yex = (date: string, ...pairs: Array<[string, string]>): DateException & { yearly: boolean } => ({
  date,
  yearly: true,
  shifts: pairs.map(([opensAt, closesAt]) => ({ opensAt, closesAt })),
});

describe("getOpenStatus — turnos del mismo día", () => {
  // Viernes con mediodía y noche: dos turnos, el modelo nuevo que motiva esto.
  const viernes = [h(5, ["11:00:00", "15:00:00"], ["19:00:00", "23:00:00"])];

  it("está abierto en el medio del primer turno y nombra SU cierre", () => {
    const s = getOpenStatus(viernes, d(5, 13, 0));
    expect(s.open).toBe(true);
    expect(s.closingLine).toBe("Cierra a las 15:00");
  });

  it("está abierto dentro del segundo turno, no del primero", () => {
    const s = getOpenStatus(viernes, d(5, 21, 0));
    expect(s.open).toBe(true);
    expect(s.closingLine).toBe("Cierra a las 23:00");
  });

  it("en la pausa entre dos turnos está cerrado y nombra la próxima apertura de HOY", () => {
    const s = getOpenStatus(viernes, d(5, 17, 0));
    expect(s.open).toBe(false);
    expect(s.closingLine).toBeNull();
    expect(s.nextOpeningLine).toBe("Abre hoy a las 19:00");
  });

  it("la apertura es inclusiva y el cierre es exclusivo (en los límites exactos)", () => {
    expect(getOpenStatus(viernes, d(5, 11, 0)).open).toBe(true);
    expect(getOpenStatus(viernes, d(5, 15, 0)).open).toBe(false);
    expect(getOpenStatus(viernes, d(5, 19, 0)).open).toBe(true);
    expect(getOpenStatus(viernes, d(5, 23, 0)).open).toBe(false);
  });

  it("el formato de hora llega en 'HH:mm:ss' o 'HH:mm' y ambos funcionan", () => {
    const mixed = [h(1, ["11:00", "15:00:00"], ["19:00:00", "23:00"])];
    expect(getOpenStatus(mixed, d(1, 12, 0)).closingLine).toBe("Cierra a las 15:00");
    expect(getOpenStatus(mixed, d(1, 12, 0)).todayLine).toBe("11:00–15:00 y 19:00–23:00");
  });

  it("un turno con hora inválida o faltante se ignora en vez de romper el día", () => {
    const roto = [
      {
        dayOfWeek: 1,
        shifts: [
          { opensAt: "11:00", closesAt: "tarde" },
          { opensAt: "19:00", closesAt: null as unknown as string },
          { opensAt: "20:00", closesAt: "22:00" },
        ],
      },
    ];
    const s = getOpenStatus(roto, d(1, 21, 0));
    expect(s.open).toBe(true);
    expect(s.closingLine).toBe("Cierra a las 22:00");
  });
});

describe("getOpenStatus — el derrame de AYER cruza la medianoche", () => {
  // Jueves 20:00 → 02:00 (cierra el viernes en la madrugada); viernes noche.
  const juevesYViernes = [h(4, ["20:00:00", "02:00:00"]), h(5, ["19:00:00", "23:00:00"])];

  it("viernes 00:10: el local está abierto POR EL TURNO DEL JUEVES", () => {
    const s = getOpenStatus(juevesYViernes, d(5, 0, 10));
    expect(s.open).toBe(true);
    // Honesto: el cierre es HOY (madrugada del viernes) a las 02:00.
    expect(s.closingLine).toBe("Cierra a las 02:00");
  });

  it("el derrame termina: viernes 02:00 en punto ya está cerrado (cierre exclusivo)", () => {
    expect(getOpenStatus(juevesYViernes, d(5, 2, 0)).open).toBe(false);
    expect(getOpenStatus(juevesYViernes, d(5, 2, 1)).open).toBe(false);
  });

  it("el turno cruzado de HOY abre esta noche y anuncia el cierre de MAÑANA", () => {
    const s = getOpenStatus(juevesYViernes, d(4, 23, 0));
    expect(s.open).toBe(true);
    expect(s.closingLine).toBe("Cierra mañana a las 02:00");
  });

  it("un turno cruzado que ya derramó hoy vuelve a abrir ESTA NOCHE", () => {
    // Viernes y sábado: 23:30 → 00:30. Sábado 01:00: la nocturna ya cerró.
    const horas = [h(5, ["23:30:00", "00:30:00"]), h(6, ["23:30:00", "00:30:00"])];
    const s = getOpenStatus(horas, d(6, 1, 0));
    expect(s.open).toBe(false);
    expect(s.nextOpeningLine).toBe("Abre hoy a las 23:30");
  });

  it("si hoy y ayer se superponen en la madrugada, gana el cierre MÁS TARDÍO", () => {
    // Lunes 22:00 → 01:30 y martes 20:00 → 02:00. Martes 01:00: sigue la
    // nocturna del LUNES (hasta 01:30); el backend rechaza este solape, pero
    // si los datos lo traen, el copy muestra el cierre real.
    const horas = [h(1, ["22:00:00", "01:30:00"]), h(2, ["20:00:00", "02:00:00"])];
    const s = getOpenStatus(horas, d(2, 1, 0));
    expect(s.open).toBe(true);
    expect(s.closingLine).toBe("Cierra a las 01:30");
  });
});

describe("getOpenStatus — días cerrados, faltantes y sin horarios", () => {
  it("un día con la lista de turnos vacía ES un día cerrado", () => {
    const domingoCerrado = [h(7)];
    const s = getOpenStatus(domingoCerrado, d(7, 12, 0));
    expect(s.open).toBe(false);
    expect(s.closingLine).toBeNull();
    expect(s.todayLine).toBe("Cerrado");
  });

  it("un día que falta en la lista se comporta como cerrado", () => {
    const hours = [h(1, ["11:00:00", "23:00:00"])];
    const s = getOpenStatus(hours, d(2, 12, 0));
    expect(s.open).toBe(false);
    expect(s.todayLine).toBe("Cerrado");
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

describe("getOpenStatus — línea de horarios de hoy (todayLine)", () => {
  it("un día de dos turnos se describe entero: '11:00–15:00 y 19:00–23:00'", () => {
    const viernes = [h(5, ["11:00:00", "15:00:00"], ["19:00:00", "23:00:00"])];
    expect(getOpenStatus(viernes, d(5, 12, 0)).todayLine).toBe(
      "11:00–15:00 y 19:00–23:00",
    );
  });

  it("un turno cruzado se muestra tal cual está guardado (11:00–00:30)", () => {
    const viernes = [h(5, ["11:00:00", "00:30:00"])];
    expect(getOpenStatus(viernes, d(5, 12, 0)).todayLine).toBe("11:00–00:30");
  });

  it("día cerrado: 'Cerrado'", () => {
    expect(getOpenStatus([h(7)], d(7, 12, 0)).todayLine).toBe("Cerrado");
  });
});

describe("getOpenStatus — próxima apertura (copiado)", () => {
  const lunesAViernes = [1, 2, 3, 4, 5].map((day) => h(day, ["11:00:00", "23:00:00"]));

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

  it("mira hasta siete días adelante y salta los días cerrados con su horario propio", () => {
    // Martes y miércoles cerrados (no están en la lista): el jueves abre a las 12:00.
    const hours = [h(1, ["11:00:00", "23:00:00"]), h(4, ["12:00:00", "22:00:00"])];
    const s = getOpenStatus(hours, d(2, 12, 0));
    expect(s.nextOpeningLine).toBe("Abre el jueves a las 12:00");
  });

  it("en un día de dos turnos cierra el turno, no el primer turno del día siguiente", () => {
    // Viernes cerrado a la siesta (17:00) pero con noche: la próxima apertura
    // es la de HOY a las 19:00, no la del mediodía ya vencida.
    const viernes = [h(5, ["11:00:00", "15:00:00"], ["19:00:00", "23:00:00"])];
    const s = getOpenStatus(viernes, d(5, 17, 0));
    expect(s.nextOpeningLine).toBe("Abre hoy a las 19:00");
  });

  it("sin ninguna apertura dentro de los siete días siguientes → null", () => {
    const s = getOpenStatus([h(7)], d(1, 12, 0));
    expect(s.nextOpeningLine).toBeNull();
  });
});

describe("getOpenStatus — excepciones por fecha (feriados y horarios especiales)", () => {
  // Enero 2024: el 4/1 es jueves, el 5/1 viernes y el 6/1 sábado.
  it("un feriado de HOY cierra aunque el día de la semana diga lo contrario", () => {
    const hours = [h(5, ["11:00:00", "23:00:00"])];
    const exceptions = [ex("2024-01-05")]; // viernes 5/1: cerrado
    const s = getOpenStatus(hours, d(5, 13, 0), exceptions);
    expect(s.open).toBe(false);
    expect(s.closingLine).toBeNull();
    expect(s.todayLine).toBe("Cerrado");
  });

  it("un feriado con horario propio abre dentro de él, cierra fuera y nombra SU cierre", () => {
    const hours = [h(5, ["11:00:00", "23:00:00"])];
    const exceptions = [ex("2024-01-05", ["11:00:00", "18:00:00"])];

    const abierto = getOpenStatus(hours, d(5, 13, 0), exceptions);
    expect(abierto.open).toBe(true);
    expect(abierto.closingLine).toBe("Cierra a las 18:00");
    expect(abierto.todayLine).toBe("11:00–18:00");

    const cerrado = getOpenStatus(hours, d(5, 19, 0), exceptions);
    expect(cerrado.open).toBe(false);
    // La próxima apertura vuelve a salir de la plantilla (el sábado abre).
    const conSabado = [h(5, ["11:00:00", "23:00:00"]), h(6, ["11:00:00", "23:00:00"])];
    expect(getOpenStatus(conSabado, d(5, 19, 0), exceptions).nextOpeningLine).toBe(
      "Abre mañana a las 11:00",
    );
  });

  it("un feriado que cruza la medianoche mantiene abierto la madrugada siguiente", () => {
    // Noche especial del jueves 4/1: 20:00 → 02:00, aunque el jueves de la
    // plantilla cierre a las 23:00. Viernes 00:30: sigue la noche especial.
    const hours = [h(4, ["11:00:00", "23:00:00"]), h(5, ["11:00:00", "23:00:00"])];
    const exceptions = [ex("2024-01-04", ["20:00:00", "02:00:00"])];
    const s = getOpenStatus(hours, d(5, 0, 30), exceptions);
    expect(s.open).toBe(true);
    expect(s.closingLine).toBe("Cierra a las 02:00");
  });

  it("un AYER que fue feriado NO derrama desde la plantilla del día de la semana", () => {
    // El jueves de la plantilla cruza la medianoche, pero el 4/1 fue feriado:
    // viernes 00:30 es cerrado aunque la plantilla del jueves diga lo contrario.
    const hours = [h(4, ["20:00:00", "02:00:00"]), h(5, ["11:00:00", "23:00:00"])];
    const exceptions = [ex("2024-01-04")];
    const s = getOpenStatus(hours, d(5, 0, 30), exceptions);
    expect(s.open).toBe(false);
    expect(s.closingLine).toBeNull();
  });

  it("la próxima apertura se saltea el feriado y nombra la apertura REAL", () => {
    const lunesAViernes = [1, 2, 3, 4, 5].map((day) => h(day, ["11:00:00", "23:00:00"]));
    const exceptions = [ex("2024-01-03")]; // miércoles 3/1: cerrado
    // Martes 23:30: la nocturna del martes ya cerró (23:00); el miércoles es
    // feriado, así que la apertura real es la del jueves.
    const s = getOpenStatus(lunesAViernes, d(2, 23, 30), exceptions);
    expect(s.open).toBe(false);
    expect(s.nextOpeningLine).toBe("Abre el jueves a las 11:00");
  });

  it("una fecha que NO está en la lista sigue siendo un día normal (regresión)", () => {
    const hours = [h(5, ["11:00:00", "23:00:00"])];
    const exceptions = [ex("2024-01-06", ["12:00:00", "18:00:00"])]; // sólo el sábado
    const s = getOpenStatus(hours, d(5, 13, 0), exceptions);
    expect(s.open).toBe(true);
    expect(s.closingLine).toBe("Cierra a las 23:00");
    expect(s.todayLine).toBe("11:00–23:00");
  });

  it("sin excepciones (lista vacía o ausente) el comportamiento es el de la plantilla semanal", () => {
    const hours = [h(5, ["11:00:00", "15:00:00"], ["19:00:00", "23:00:00"])];
    for (const exceptions of [[], undefined]) {
      const s = getOpenStatus(hours, d(5, 13, 0), exceptions);
      expect(s.open).toBe(true);
      expect(s.closingLine).toBe("Cierra a las 15:00");
      expect(s.todayLine).toBe("11:00–15:00 y 19:00–23:00");
    }
  });

  it("la fecha es la LOCAL: de noche no confunde el día con el UTC", () => {
    // Viernes 5/1 22:00 en Buenos Aires (UTC-3) = sábado 6/1 01:00 UTC:
    // `toISOString().slice(0,10)` diría 2024-01-06 y el feriado se perdería.
    const prevTz = process.env.TZ;
    process.env.TZ = "America/Argentina/Buenos_Aires";
    try {
      const hours = [h(5, ["11:00:00", "23:00:00"]), h(6, ["11:00:00", "23:00:00"])];
      const exceptions = [ex("2024-01-05")]; // viernes 5/1: cerrado
      const s = getOpenStatus(hours, d(5, 22, 0), exceptions);
      expect(s.open).toBe(false);
      expect(s.todayLine).toBe("Cerrado");
    } finally {
      if (prevTz === undefined) delete process.env.TZ;
      else process.env.TZ = prevTz;
    }
  });
});

describe("nextStatusBoundaryAt — excepciones YEARLY (se repiten cada año)", () => {
  // El año GUARDADO de la excepción es 2020: los días de la suite son de
  // enero 2024, así que toda matchea por mes/día en OTRO año.

  it("la yearly con horario propio rige un día de OTRO año (matchea por mes/día)", () => {
    const hours = [h(5, ["11:00:00", "23:00:00"])]; // viernes
    const s = getOpenStatus(hours, d(5, 13, 0), [yex("2020-01-05", ["12:00:00", "18:00:00"])]);
    expect(s.open).toBe(true);
    expect(s.closingLine).toBe("Cierra a las 18:00");
    expect(s.todayLine).toBe("12:00–18:00");
  });

  it("una yearly CERRADA cierra ese día en otro año", () => {
    const hours = [h(5, ["11:00:00", "23:00:00"])];
    const s = getOpenStatus(hours, d(5, 13, 0), [yex("2020-01-05")]);
    expect(s.open).toBe(false);
    expect(s.closingLine).toBeNull();
    expect(s.todayLine).toBe("Cerrado");
  });

  it("una fecha EXACTA le gana a la yearly del mismo mes/día, en cualquier orden de la lista", () => {
    const hours = [h(5, ["11:00:00", "23:00:00"])];
    // La anual de 25/12 (guardada en 2020) es cerrada; la EXACTA de 2024
    // abre al mediodía: la exacta manda aunque la anual aparezca primera.
    const exacta = ex("2024-01-05", ["14:00:00", "20:00:00"]);
    const anual = yex("2020-01-05");
    for (const exceptions of [[anual, exacta], [exacta, anual]]) {
      const s = getOpenStatus(hours, d(5, 15, 0), exceptions);
      expect(s.open).toBe(true);
      expect(s.todayLine).toBe("14:00–20:00");
    }
  });

  it("una one-off SIN el flag guardada en OTRO año NO matchea (flag ausente = false)", () => {
    const hours = [h(5, ["11:00:00", "23:00:00"])];
    const s = getOpenStatus(hours, d(5, 13, 0), [ex("2023-01-05", ["12:00:00", "18:00:00"])]);
    expect(s.open).toBe(true);
    expect(s.todayLine).toBe("11:00–23:00");
  });

  it("la próxima apertura se saltea la yearly cerrada y nombra la apertura REAL", () => {
    const lunesAViernes = [1, 2, 3, 4, 5].map((day) => h(day, ["11:00:00", "23:00:00"]));
    // Martes 23:30: el miércoles 3/1 es yearly cerrado (guardado en 2020);
    // la apertura real es la del jueves.
    const s = getOpenStatus(lunesAViernes, d(2, 23, 30), [yex("2020-01-03")]);
    expect(s.open).toBe(false);
    expect(s.nextOpeningLine).toBe("Abre el jueves a las 11:00");
  });

  it("nextStatusBoundaryAt mueve el borde igual que una one-off: el día yearly cerrado no aporta bordes", () => {
    const hours = [h(3, ["11:00:00", "23:00:00"]), h(4, ["11:00:00", "23:00:00"])];
    // Martes 23:30 (sin horario propio): sin yearly el próximo borde sería la
    // apertura del MIÉRCOLES; con el miércoles yearly cerrado, la del jueves.
    expect(nextStatusBoundaryAt(hours, d(2, 23, 30), [yex("2020-01-03")])).toBe(
      new Date(2024, 0, 4, 11, 0).getTime(),
    );
  });

  it("nextStatusBoundaryAt agenda el borde de la yearly con horario propio, en otro año", () => {
    const hours = [h(5, ["11:00:00", "23:00:00"])];
    expect(nextStatusBoundaryAt(hours, d(5, 12, 0), [yex("2020-01-05", ["11:00:00", "18:00:00"])]))
      .toBe(new Date(2024, 0, 5, 18, 0).getTime());
  });

  it("el match yearly también es por fecha LOCAL: de noche no confunde el día con el UTC", () => {
    // Viernes 5/1 22:00 en Buenos Aires (UTC-3) = sábado 6/1 01:00 UTC: un
    // "MM-DD" sacado de toISOString() sería 01-06 y la yearly de 01-05
    // (guardada en 2019) se perdería.
    const prevTz = process.env.TZ;
    process.env.TZ = "America/Argentina/Buenos_Aires";
    try {
      const hours = [h(5, ["11:00:00", "23:00:00"])];
      const s = getOpenStatus(hours, d(5, 22, 0), [yex("2019-01-05")]);
      expect(s.open).toBe(false);
      expect(s.todayLine).toBe("Cerrado");
    } finally {
      if (prevTz === undefined) delete process.env.TZ;
      else process.env.TZ = prevTz;
    }
  });
});

describe("nextStatusBoundaryAt — excepciones por fecha", () => {
  it("agenda el borde del feriado con horario propio, no el del día de la semana", () => {
    const hours = [h(5, ["11:00:00", "23:00:00"])];
    const exceptions = [ex("2024-01-05", ["11:00:00", "18:00:00"])];
    expect(nextStatusBoundaryAt(hours, d(5, 12, 0), exceptions)).toBe(
      new Date(2024, 0, 5, 18, 0).getTime(),
    );
  });

  it("un feriado cerrado no aporta bordes: el próximo es la apertura del día siguiente", () => {
    const hours = [h(5, ["11:00:00", "23:00:00"]), h(6, ["11:00:00", "23:00:00"])];
    const exceptions = [ex("2024-01-05")];
    expect(nextStatusBoundaryAt(hours, d(5, 12, 0), exceptions)).toBe(
      new Date(2024, 0, 6, 11, 0).getTime(),
    );
  });
});
