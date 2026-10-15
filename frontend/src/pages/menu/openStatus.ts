import type { BusinessHours } from "./types";

/**
 * Estado de abierto/cerrado del local para un instante dado, con el copy listo
 * para la portada. Lógica PURA: sin reloj propio ni DOM, todo entra por
 * parámetro (horarios + `Date`) para poder testearla.
 *
 * Modelo del backend (BusinessProfileServiceImpl, commit e9094fa): cada día
 * lleva una lista ORDENADA de turnos; un día con la lista vacía ES un día
 * cerrado. Un turno con `closesAt` MENOR que `opensAt` cruza la medianoche
 * (viernes 19:00 → 00:30 = cierra en la madrugada del sábado) y por regla del
 * backend sólo el ÚLTIMO turno del día puede cruzar. Un turno del mismo día
 * cumple `opensAt < closesAt` y abre con `opensAt <= ahora < closesAt`
 * (apertura inclusiva, cierre exclusivo).
 *
 * La madrugada de hoy la cubre el turno de AYER que cruzó la medianoche
 * (`ahora < closesAt` de ayer): a las 00:10 del viernes el local está abierto
 * por el turno del jueves. Los tiempos llegan como "HH:mm:ss" o "HH:mm".
 */
export interface OpenStatus {
  open: boolean;
  /** "Cierra a las 23:00" / "Cierra mañana a las 00:30" cuando está abierto; null si no. */
  closingLine: string | null;
  /** "Abre hoy a las 11:00" / "Abre mañana a las 11:00" / "Abre el lunes a las 11:00"; null si no se puede computar dentro de los siete días siguientes. */
  nextOpeningLine: string | null;
  /** Horarios de hoy: "11:00–15:00 y 19:00–23:00" o "Cerrado"; null cuando no hay horarios. */
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

/** "HH:MM:SS" o "HH:MM" → minutos desde la medianoche. */
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

interface Shift {
  opens: number;
  closes: number;
  /** `closesAt < opensAt`: cierra en la madrugada del día siguiente. */
  crossing: boolean;
}

/**
 * Turnos del día pedido, en minutos. Un día que falta o con la lista vacía es
 * un día cerrado (lista vacía). Un turno con hora faltante o ilegible se
 * ignora: no existe en el modelo del backend y no tiene por qué romper el día.
 */
function shiftsOf(
  hours: BusinessHours[] | null | undefined,
  dayOfWeek: number,
): Shift[] {
  const entry = hours?.find((h) => h.dayOfWeek === dayOfWeek);
  if (!entry) return [];

  const shifts: Shift[] = [];
  for (const shift of entry.shifts ?? []) {
    if (!shift?.opensAt || !shift?.closesAt) continue;
    const opens = toMinutes(shift.opensAt);
    const closes = toMinutes(shift.closesAt);
    if (!Number.isFinite(opens) || !Number.isFinite(closes)) continue;
    shifts.push({ opens, closes, crossing: closes < opens });
  }
  return shifts;
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

  // ¿Abierto ahora? Un turno de HOY cubre [opens, closes) del mismo día, o
  // desde su apertura nocturna hacia MAÑANA si cruza la medianoche. La
  // madrugada la cubre el turno cruzado de AYER. Si los datos (mal cargados)
  // superponen dos candidatos, gana el cierre MÁS TARDÍO: es el cierre real.
  let closingAt: number | null = null;
  let closingTomorrow = false;

  for (const shift of shiftsOf(hours, today)) {
    const inside = shift.crossing
      ? minutesNow >= shift.opens
      : minutesNow >= shift.opens && minutesNow < shift.closes;
    if (inside && (closingAt === null || shift.closes > closingAt)) {
      closingAt = shift.closes;
      closingTomorrow = shift.crossing;
    }
  }
  for (const shift of shiftsOf(hours, yesterday)) {
    if (shift.crossing && minutesNow < shift.closes) {
      // El turno cruzado de AYER sigue cubriendo la madrugada de HOY.
      if (closingAt === null || shift.closes > closingAt) {
        closingAt = shift.closes;
        closingTomorrow = false; // ese cierre ya es de hoy
      }
    }
  }

  const todayShifts = shiftsOf(hours, today);
  const todayLine =
    todayShifts.length > 0
      ? todayShifts
          .map((s) => `${formatMinutes(s.opens)}–${formatMinutes(s.closes)}`)
          .join(" y ")
      : "Cerrado";

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
 * copy "Cerrado · Abre …". Cada día puede tener varios turnos: se anuncia la
 * apertura MÁS TEMPRANA que todavía no llegó (un turno cruzado que ya derramó
 * esta madrugada vuelve a abrir ESTA NOCHE, y en un día de dos turnos la
 * siesta vencida no tapa la noche).
 */
function nextOpeningLine(
  hours: BusinessHours[],
  minutesNow: number,
  today: number,
): string | null {
  for (let offset = 0; offset <= 7; offset++) {
    const dayOfWeek = ((today - 1 + offset) % 7) + 1;
    const shifts = shiftsOf(hours, dayOfWeek);
    if (shifts.length === 0) continue;

    if (offset === 0) {
      const opens = Math.min(
        ...shifts.filter((s) => minutesNow < s.opens).map((s) => s.opens),
      );
      if (Number.isFinite(opens)) return `Abre hoy a las ${formatMinutes(opens)}`;
      continue;
    }
    const opens = Math.min(...shifts.map((s) => s.opens));
    const when = offset === 1 ? "mañana" : `el ${DAY_NAMES[dayOfWeek - 1]}`;
    return `Abre ${when} a las ${formatMinutes(opens)}`;
  }
  return null;
}
