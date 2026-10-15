import type { BusinessHours } from "./types";

/**
 * Estado de abierto/cerrado del local para un instante dado, con el copy listo
 * para la portada. Lógica PURA: sin reloj propio ni DOM, todo entra por
 * parámetro (horarios + `Date`) para poder testearla.
 *
 * Convención del backend (BusinessProfileServiceImpl): cuando `closesAt` es
 * MENOR que `opensAt`, el cierre cae en la madrugada del día SIGUIENTE
 * (viernes 11:00 → 00:30 = abre hasta las 00:30 del sábado). Un rango del
 * mismo día cumple `opensAt < closesAt` y abre con `opensAt <= ahora <
 * closesAt` (apertura inclusiva, cierre exclusivo).
 *
 * La madrugada de hoy la cubre el rango de AYER cuando ese rango cruza la
 * medianoche (`ahora < closesAt` de ayer); el rango cruzado de HOY sólo cubre
 * desde su apertura esta noche hacia adelante.
 */
export interface OpenStatus {
  open: boolean;
  /** "Cierra a las 23:00" / "Cierra mañana a las 00:30" cuando está abierto; null si no. */
  closingLine: string | null;
  /** "Abre hoy a las 11:00" / "Abre mañana a las 11:00" / "Abre el lunes a las 11:00"; null si no se puede computar dentro de los siete días siguientes. */
  nextOpeningLine: string | null;
  /** Horarios de hoy: "11:00–00:30" o "cerrado"; null cuando no hay horarios. */
  todayLine: string | null;
}

const DAY_NAMES = [
  "lunes",
  "martes",
  "miércoles",
  "jueves",
  "viernes",
  "sábado",
  "domingo",
];

/** "HH:MM:SS" (o "HH:MM") → minutos desde la medianoche. */
function toMinutes(time: string): number {
  const [hh, mm] = time.split(":");
  const hours = Number(hh);
  const minutes = Number(mm ?? 0);
  if (!Number.isFinite(hours) || !Number.isFinite(minutes)) return Number.NaN;
  return hours * 60 + minutes;
}

function formatMinutes(min: number): string {
  const hh = String(Math.floor(min / 60) % 24).padStart(2, "0");
  const mm = String(Math.floor(min % 60)).padStart(2, "0");
  return `${hh}:${mm}`;
}

/** `Date.getDay()` (0 = domingo) → convención del payload (1 = lunes … 7 = domingo). */
function dayOfWeekOf(date: Date): number {
  return ((date.getDay() + 6) % 7) + 1;
}

interface DayRange {
  opens: number;
  closes: number;
  /** `closesAt < opensAt`: cierra en la madrugada del día siguiente. */
  crossing: boolean;
}

/** Rango del día pedido; null si el día falta, está cerrado o no tiene horas válidas. */
function rangeOf(
  hours: BusinessHours[] | null | undefined,
  dayOfWeek: number,
): DayRange | null {
  const entry = hours?.find((h) => h.dayOfWeek === dayOfWeek);
  if (!entry || entry.closed) return null;
  if (!entry.opensAt || !entry.closesAt) return null;
  const opens = toMinutes(entry.opensAt);
  const closes = toMinutes(entry.closesAt);
  if (!Number.isFinite(opens) || !Number.isFinite(closes)) return null;
  // La igualdad opensAt == closesAt la rechaza el backend por ambigua; acá se
  // lee como rango de cero minutos del mismo día (cerrado todo el día).
  return { opens, closes, crossing: closes < opens };
}

const CLOSED: OpenStatus = {
  open: false,
  closingLine: null,
  nextOpeningLine: null,
  todayLine: null,
};

export function getOpenStatus(
  hours: BusinessHours[] | null | undefined,
  now: Date,
): OpenStatus {
  if (!hours || hours.length === 0) return CLOSED;

  const minutesNow = now.getHours() * 60 + now.getMinutes() + now.getSeconds() / 60;
  const today = dayOfWeekOf(now);
  const yesterday = today === 1 ? 7 : today - 1;

  const todayRange = rangeOf(hours, today);
  const yesterdayRange = rangeOf(hours, yesterday);

  // ¿Abierto ahora? Gana el cierre MÁS TARDÍO de los rangos activos (si la
  // nocturna de ayer y la de hoy se superponen en la madrugada, se muestra
  // hasta cuándo cierra de verdad).
  let closingAt: number | null = null;
  let closingTomorrow = false;

  if (todayRange) {
    if (todayRange.crossing) {
      // Rango cruzado de HOY: sólo cubre desde su apertura nocturna hacia la
      // madrugada de MAÑANA (su cierre ya es de mañana).
      if (minutesNow >= todayRange.opens) {
        closingAt = todayRange.closes;
        closingTomorrow = true;
      }
    } else if (
      minutesNow >= todayRange.opens &&
      minutesNow < todayRange.closes
    ) {
      closingAt = todayRange.closes;
    }
  }
  if (yesterdayRange?.crossing && minutesNow < yesterdayRange.closes) {
    // La nocturna de AYER sigue cubriendo la madrugada de HOY.
    if (closingAt === null || yesterdayRange.closes > closingAt) {
      closingAt = yesterdayRange.closes;
      closingTomorrow = false; // ese cierre ya es de hoy
    }
  }

  const todayLine = todayRange
    ? `${formatMinutes(todayRange.opens)}–${formatMinutes(todayRange.closes)}`
    : "cerrado";

  if (closingAt !== null) {
    return {
      open: true,
      closingLine: `${closingTomorrow ? "Cierra mañana" : "Cierra"} a las ${formatMinutes(closingAt)}`,
      nextOpeningLine: null,
      todayLine,
    };
  }

  return {
    open: false,
    closingLine: null,
    nextOpeningLine: nextOpeningLine(hours, minutesNow, today),
    todayLine,
  };
}

/**
 * Próxima apertura dentro de los siete días siguientes (hoy incluido), para el
 * copy "Cerrado · Abre …". Un rango cruzado que ya derramó esta madrugada
 * vuelve a abrir ESTA NOCHE, así que también cuenta como apertura de hoy.
 */
function nextOpeningLine(
  hours: BusinessHours[],
  minutesNow: number,
  today: number,
): string | null {
  for (let offset = 0; offset <= 7; offset++) {
    const dayOfWeek = ((today - 1 + offset) % 7) + 1;
    const range = rangeOf(hours, dayOfWeek);
    if (!range) continue;

    if (offset === 0) {
      // Todavía puede abrir hoy: su apertura no llegó.
      if (minutesNow < range.opens) {
        return `Abre hoy a las ${formatMinutes(range.opens)}`;
      }
      continue;
    }
    const when = offset === 1 ? "mañana" : `el ${DAY_NAMES[dayOfWeek - 1]}`;
    return `Abre ${when} a las ${formatMinutes(range.opens)}`;
  }
  return null;
}
