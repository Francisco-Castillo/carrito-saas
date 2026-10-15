import type { BusinessHours, BusinessHoursShift, DateException } from "./types";

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
 *
 * Sobre la plantilla semanal se resuelven las EXCEPCIONES POR FECHA
 * (`dateExceptions`): una fecha presente en la lista manda (lista vacía =
 * feriado cerrado); una fecha ausente es un día normal. La resolución —
 * `effectiveShifts` — es la única puerta: hoy, el derrame de ayer, la próxima
 * apertura y los bordes del badge la usan todos.
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

/**
 * Fecha de calendario LOCAL: `2024-01-05` para el 5 de enero, sea cual sea la
 * zona horaria. Cuidado con `toISOString().slice(0, 10)`: esa es la fecha UTC
 * y reporta el día equivocado durante la tarde-noche de toda zona al oeste de
 * UTC (en Buenos Aires, a partir de las 21:00). El día que importa acá es el
 * del reloj de pared del cliente.
 */
function localDateKey(date: Date): string {
  const yyyy = String(date.getFullYear()).padStart(4, "0");
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  const dd = String(date.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

export interface Shift {
  opens: number;
  closes: number;
  /** `closesAt < opensAt`: cierra en la madrugada del día siguiente. */
  crossing: boolean;
}

/**
 * Turnos crudos ("HH:mm:ss"/"HH:mm") → en minutos. Un turno con hora faltante
 * o ilegible se ignora: no existe en el modelo del backend y no tiene por qué
 * romper el día.
 */
function parseShifts(
  raw: BusinessHoursShift[] | null | undefined,
): Shift[] {
  const shifts: Shift[] = [];
  for (const shift of raw ?? []) {
    if (!shift?.opensAt || !shift?.closesAt) continue;
    const opens = toMinutes(shift.opensAt);
    const closes = toMinutes(shift.closesAt);
    if (!Number.isFinite(opens) || !Number.isFinite(closes)) continue;
    shifts.push({ opens, closes, crossing: closes < opens });
  }
  return shifts;
}

/**
 * Plantilla del día de la semana: turnos del día pedido, en minutos. Un día
 * que falta o con la lista vacía es un día cerrado (lista vacía).
 */
function shiftsOf(
  hours: BusinessHours[] | null | undefined,
  dayOfWeek: number,
): Shift[] {
  const entry = hours?.find((h) => h.dayOfWeek === dayOfWeek);
  if (!entry) return [];
  return parseShifts(entry.shifts);
}

/**
 * Turnos EFECTIVOS de una fecha del calendario, en minutos: la resolución que
 * usan hoy, el derrame de ayer y la próxima apertura. Si la fecha tiene una
 * excepción, manda la excepción (lista vacía = feriado cerrado); si NO está
 * en la lista, es un día normal y rige la plantilla de su día de la semana.
 *
 * `date` debe ser una fecha LOCAL (`new Date(año, mes, día)`); la clave de la
 * excepción se arma con `getFullYear`/`getMonth`/`getDate`, nunca con UTC.
 */
export function effectiveShifts(
  hours: BusinessHours[] | null | undefined,
  dateExceptions: DateException[] | null | undefined,
  date: Date,
): Shift[] {
  const key = localDateKey(date);
  const exception = dateExceptions?.find((e) => e?.date === key);
  if (exception) return parseShifts(exception.shifts);
  return shiftsOf(hours, dayOfWeekOf(date));
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
  dateExceptions?: DateException[] | null,
): OpenStatus {
  if (!hours || hours.length === 0) return CLOSED;

  const minutesNow = now.getHours() * 60 + now.getMinutes() + now.getSeconds() / 60;

  // Fechas de calendario LOCALES de hoy y de ayer: las excepciones se resuelven
  // contra el DÍA del reloj de pared, no contra el día de la semana ni UTC.
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  const todayShifts = effectiveShifts(hours, dateExceptions, now);
  const yesterdayShifts = effectiveShifts(hours, dateExceptions, yesterday);

  // ¿Abierto ahora? Un turno de HOY cubre [opens, closes) del mismo día, o
  // desde su apertura nocturna hacia MAÑANA si cruza la medianoche. La
  // madrugada la cubre el turno cruzado de AYER. Si los datos (mal cargados)
  // superponen dos candidatos, gana el cierre MÁS TARDÍO: es el cierre real.
  let closingAt: number | null = null;
  let closingTomorrow = false;

  for (const shift of todayShifts) {
    const inside = shift.crossing
      ? minutesNow >= shift.opens
      : minutesNow >= shift.opens && minutesNow < shift.closes;
    if (inside && (closingAt === null || shift.closes > closingAt)) {
      closingAt = shift.closes;
      closingTomorrow = shift.crossing;
    }
  }
  for (const shift of yesterdayShifts) {
    if (shift.crossing && minutesNow < shift.closes) {
      // El turno cruzado de AYER sigue cubriendo la madrugada de HOY.
      if (closingAt === null || shift.closes > closingAt) {
        closingAt = shift.closes;
        closingTomorrow = false; // ese cierre ya es de hoy
      }
    }
  }

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
    nextOpeningLine: nextOpeningLine(hours, dateExceptions, minutesNow, now),
    todayLine,
  };
}

export function nextStatusBoundaryAt(
  hours: BusinessHours[] | null | undefined,
  now: Date,
  dateExceptions?: DateException[] | null,
): number | null {
  if (!hours || hours.length === 0) return null;

  const nowMs = now.getTime();
  let next: number | null = null;
  const consider = (ts: number) => {
    if (ts > nowMs && (next === null || ts < next)) next = ts;
  };

  // offset -1 cubre el derrame del turno cruzado de AYER (cierra HOY en la
  // madrugada); offset 0..7 cubre los turnos de los próximos siete días, que
  // es todo el ciclo semanal del modelo. Cada fecha se resuelve con sus turnos
  // EFECTIVOS (su excepción si la tiene, si no su día de la semana), así que
  // un feriado en el medio no agenda bordes que no existen. Cada turno aporta
  // DOS bordes: su apertura y su cierre (el cierre del turno cruzado cae en el
  // día SIGUIENTE).
  for (let offset = -1; offset <= 7; offset++) {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset);
    const nextDay = new Date(
      now.getFullYear(),
      now.getMonth(),
      now.getDate() + offset + 1,
    );
    const dayStart = day.getTime();
    for (const shift of effectiveShifts(hours, dateExceptions, day)) {
      consider(dayStart + shift.opens * 60_000);
      consider((shift.crossing ? nextDay.getTime() : dayStart) + shift.closes * 60_000);
    }
  }
  return next;
}

/**
 * Próxima apertura dentro de los siete días siguientes (hoy incluido), para el
 * copy "Cerrado · Abre …". El recorrido consulta cada FECHA (excepción si la
 * tiene, si no su día de la semana), así que un feriado en el medio se saltea
 * y se anuncia la apertura real siguiente. Cada día puede tener varios turnos:
 * se anuncia la apertura MÁS TEMPRANA que todavía no llegó (un turno cruzado
 * que ya derramó esta madrugada vuelve a abrir ESTA NOCHE, y en un día de dos
 * turnos la siesta vencida no tapa la noche).
 */
function nextOpeningLine(
  hours: BusinessHours[],
  dateExceptions: DateException[] | null | undefined,
  minutesNow: number,
  now: Date,
): string | null {
  for (let offset = 0; offset <= 7; offset++) {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset);
    const shifts = effectiveShifts(hours, dateExceptions, day);
    if (shifts.length === 0) continue;

    if (offset === 0) {
      const opens = Math.min(
        ...shifts.filter((s) => minutesNow < s.opens).map((s) => s.opens),
      );
      if (Number.isFinite(opens)) return `Abre hoy a las ${formatMinutes(opens)}`;
      continue;
    }
    const opens = Math.min(...shifts.map((s) => s.opens));
    const when =
      offset === 1 ? "mañana" : `el ${DAY_NAMES[dayOfWeekOf(day) - 1]}`;
    return `Abre ${when} a las ${formatMinutes(opens)}`;
  }
  return null;
}
