import { useEffect, useState } from "react";
import { API, authHeader } from "./api";
import { showSuccessToast } from "./toast";
import type { BusinessHours, DateException } from "../menu/types";

/**
 * Editor del perfil del local (vista "Local" del panel admin) contra los
 * endpoints owner GET/PUT /api/business/profile (scoped por el JWT):
 *
 *   - El PUT REEMPLAZA todo el perfil: un campo en blanco ("") lo limpia y
 *     el array de horarios se reconstruye con los SIETE días, cada uno con
 *     su lista de turnos (incluyendo `[]` en los cerrados), por lo que
 *     nunca se envía de menos.
 *   - El modelo es de TURNOS ordenados por día (el backend los reordena y
 *     valida): un día sin turnos ES un día cerrado; no existe un flag
 *     `closed` que pueda contradecir a los datos.
 *   - Los <input type="time"> hablan "HH:mm"; el backend habla "HH:mm:ss",
 *     así que se convierte en ambas direcciones.
 *   - Convención de medianoche del backend: closesAt MENOR que opensAt
 *     significa que el cierre cae en la madrugada del día siguiente
 *     (viernes 11:00 → 00:30); se permite tal cual y se avisa en una línea.
 *   - Un 400 del backend trae un `message` legible en español (solapes, un
 *     turno que cruza medianoche sin ser el último, derrame contra la
 *     mañana siguiente): se muestra inline en vez de fallar en silencio, y
 *     el formulario queda usable.
 *   - Las FECHAS ESPECIALES (excepciones por fecha, feriados y días con
 *     horario propio) comparten el PUT: `dateExceptions` viaja SIEMPRE,
 *     incluso vacía (el backend la exige; una lista vacía significa "no hay
 *     fechas especiales"). Una fecha presente con `shifts: []` ES un
 *     feriado cerrado; una fecha AUSENTE es un día normal. El reemplazo es
 *     total, igual que el resto del perfil.
 *   - El flag YEARLY ("Se repite cada año") viaja en cada fila: `true` = la
 *     excepción rige ese mes/día en TODOS los años (Navidad 25/12); `false` =
 *     sólo esa fecha exacta. En la lista se distingue por su rótulo —
 *     "25/12 (cada año)" contra "20/10/2026 (lunes)" del one-off — y por el
 *     borde acentuado de su fila. Un payload viejo sin el flag carga como
 *     `false` y se reenvía igual.
 *   - El guard local de duplicados replica al backend: dos one-off con la
 *     misma fecha chocan ("Ya hay una excepción para esa fecha") y dos
 *     ANUALES con el mismo día y mes también, aunque los años difieran
 *     ("Ya hay una excepción anual para ese día y mes"). Una anual y una
 *     one-off que compartan día/mes están permitidas: la exacta gana su año.
 *   - Una fecha "YYYY-MM-DD" NUNCA se construye con `new Date(string)`: ese
 *     parse es UTC y en una zona de offset negativo cae en el día anterior
 *     (el rótulo diría "jueves" para un viernes). Se divide en partes y se
 *     arma la fecha local, tanto para el rótulo como para el día de semana.
 *   - Los límites conocidos no viajan en silencio: una fecha duplicada o
 *     superar las 100 fechas se rechaza en el formulario con el mismo
 *     mensaje del backend, y un 400 real se muestra sin descartar el trabajo.
 */

export interface BusinessProfile {
  name?: string | null;
  logoUrl?: string | null;
  bannerUrl?: string | null;
  address?: string | null;
  instagramUrl?: string | null;
  primaryColor?: string | null;
  hours?: BusinessHours[] | null;
  dateExceptions?: DateException[] | null;
}

const DAYS = ["Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"];

/** Nombres de día en el orden de `Date#getDay` (0 = domingo). */
const WEEKDAYS = ["Domingo", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado"];

/** Límite del backend:fechas especiales por perfil. */
const MAX_DATE_EXCEPTIONS = 100;

/** Un turno en el estado del formulario: "HH:mm" para los <input type="time">. */
interface ShiftRow {
  opensAt: string;
  closesAt: string;
}

/** Una fecha especial en el estado del formulario. */
interface ExceptionRow {
  date: string;
  /** `true`: rige ese mes/día en todos los años; `false`: sólo esa fecha. */
  yearly: boolean;
  shifts: ShiftRow[];
}

/**
 * "YYYY-MM-DD" → Date LOCAL construida por partes. NUNCA `new Date(string)`:
 * ese parse es UTC y en una zona de offset negativo cae en la tarde del día
 * anterior (el rótulo diría "jueves" para un viernes).
 */
function parseLocalDate(date: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!m) return null;
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
}

/** "2026-12-25" → "25/12 (viernes)": fecha y día de semana por partes locales. */
function oneOffLabel(date: string): string {
  const dt = parseLocalDate(date);
  if (!dt) return date;
  return `${dt.getDate()}/${dt.getMonth() + 1} (${WEEKDAYS[dt.getDay()].toLowerCase()})`;
}

/**
 * Rótulo de la fila: una ANUAL es "25/12 (cada año)" — un día de semana sería
 * mentira para los demás años — y una one-off lleva su día de semana. Ambas
 * por partes locales, nunca con un parse UTC de la fecha.
 */
function exceptionLabel(exc: ExceptionRow): string {
  if (exc.yearly) {
    const dt = parseLocalDate(exc.date);
    if (dt) return `${dt.getDate()}/${dt.getMonth() + 1} (cada año)`;
  }
  return oneOffLabel(exc.date);
}

/** Ordenadas por fecha: así viajan y así se muestran. */
function sortedExceptionRows(rows: ExceptionRow[]): ExceptionRow[] {
  return [...rows].sort((a, b) => a.date.localeCompare(b.date));
}

/** Turnos ordenados por apertura (igual que los reordena el backend). */
function sortedShiftRows(shifts: ShiftRow[]): ShiftRow[] {
  return [...shifts].sort((a, b) => (a.opensAt || "99:99").localeCompare(b.opensAt || "99:99"));
}

function emptyDays(): ShiftRow[][] {
  return DAYS.map(() => []);
}

/** "HH:mm:ss" (backend) → "HH:mm" (input). */
function toInputTime(t: string | null | undefined): string {
  return typeof t === "string" && t ? t.slice(0, 5) : "";
}

/** "HH:mm" (input) → "HH:mm:ss" (backend); vacío → null. */
function toBackendTime(t: string): string | null {
  return t ? `${t}:00` : null;
}

function dayFromEntry(entry: { shifts?: BusinessHours["shifts"] } | undefined): ShiftRow[] {
  return (entry?.shifts ?? []).map((s) => ({
    opensAt: toInputTime(s.opensAt),
    closesAt: toInputTime(s.closesAt),
  }));
}

/** Miniatura de lo que apunta la URL: el owner pega URLs, ver la imagen es su única devolución. */
function UrlPreview({ url, alt }: { url: string; alt: string }) {
  const [failed, setFailed] = useState(false);
  const trimmed = url.trim();

  useEffect(() => {
    setFailed(false);
  }, [trimmed]);

  if (!trimmed || failed) {
    return (
      <span className="admin-profile-preview admin-profile-preview--empty" aria-label={alt}>
        Sin imagen
      </span>
    );
  }
  return (
    <img
      className="admin-profile-preview"
      src={trimmed}
      alt={alt}
      onError={() => setFailed(true)}
    />
  );
}

export default function LocalSection() {
  const [name, setName] = useState("");
  const [logoUrl, setLogoUrl] = useState("");
  const [bannerUrl, setBannerUrl] = useState("");
  const [address, setAddress] = useState("");
  const [instagramUrl, setInstagramUrl] = useState("");
  const [primaryColor, setPrimaryColor] = useState("");
  const [days, setDays] = useState<ShiftRow[][]>(emptyDays);
  const [exceptions, setExceptions] = useState<ExceptionRow[]>([]);
  const [newExceptionDate, setNewExceptionDate] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch(`${API}/business/profile`, { headers: authHeader() });
        if (!res.ok) throw new Error("Error cargando el perfil del local");
        const data = (await res.json()) as BusinessProfile;
        if (cancelled) return;
        setName(data.name ?? "");
        setLogoUrl(data.logoUrl ?? "");
        setBannerUrl(data.bannerUrl ?? "");
        setAddress(data.address ?? "");
        setInstagramUrl(data.instagramUrl ?? "");
        setPrimaryColor(data.primaryColor ?? "");
        const byDay = new Map((data.hours ?? []).map((h) => [h.dayOfWeek, h]));
        setDays(DAYS.map((_, i) => dayFromEntry(byDay.get(i + 1))));
        setExceptions(
          sortedExceptionRows(
            // `yearly` es opcional en el payload (filas viejas y one-off no
            // la traen): ausente carga como `false`, y así viaja de vuelta.
            (data.dateExceptions ?? []).map((e) => ({
              date: e.date,
              yearly: e.yearly === true,
              shifts: dayFromEntry(e),
            })),
          ),
        );
      } catch (e) {
        if (!cancelled) setError((e as Error).message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  function setShift(index: number, shiftIndex: number, patch: Partial<ShiftRow>) {
    setDays((prev) =>
      prev.map((day, i) =>
        i === index ? day.map((s, j) => (j === shiftIndex ? { ...s, ...patch } : s)) : day,
      ),
    );
  }

  function addShift(index: number) {
    setDays((prev) => prev.map((day, i) => (i === index ? [...day, { opensAt: "", closesAt: "" }] : day)));
  }

  function removeShift(index: number, shiftIndex: number) {
    setDays((prev) => prev.map((day, i) => (i === index ? day.filter((_, j) => j !== shiftIndex) : day)));
  }

  function setExceptionShift(date: string, shiftIndex: number, patch: Partial<ShiftRow>) {
    setExceptions((prev) =>
      prev.map((exc) =>
        exc.date === date
          ? { ...exc, shifts: exc.shifts.map((s, j) => (j === shiftIndex ? { ...s, ...patch } : s)) }
          : exc,
      ),
    );
  }

  function addExceptionShift(date: string) {
    setExceptions((prev) =>
      prev.map((exc) => (exc.date === date ? { ...exc, shifts: [...exc.shifts, { opensAt: "", closesAt: "" }] } : exc)),
    );
  }

  function removeExceptionShift(date: string, shiftIndex: number) {
    setExceptions((prev) =>
      prev.map((exc) => (exc.date === date ? { ...exc, shifts: exc.shifts.filter((_, j) => j !== shiftIndex) } : exc)),
    );
  }

  function removeException(date: string) {
    setExceptions((prev) => prev.filter((exc) => exc.date !== date));
  }

  function setExceptionYearly(date: string, yearly: boolean) {
    if (yearly) {
      // Mismo guard que el backend (mismo mensaje): dos anuales con el mismo
      // día y mes chocan aunque los años difieran; una anual y una one-off
      // que compartan día/mes están permitidas (la exacta gana su año).
      const monthDay = date.slice(5); // "YYYY-MM-DD" → "MM-DD"
      if (
        exceptions.some(
          (exc) => exc.yearly && exc.date !== date && exc.date.slice(5) === monthDay,
        )
      ) {
        setError("Ya hay una excepción anual para ese día y mes");
        return;
      }
    }
    setError(null);
    setExceptions((prev) =>
      prev.map((exc) => (exc.date === date ? { ...exc, yearly } : exc)),
    );
  }

  function addException() {
    if (!newExceptionDate) return;
    // Duplicados exactos, como el backend: la comparación anual por día/mes
    // corre al marcar "cada año" (setExceptionYearly), porque acá la fila
    // nueva nace one-off y una anual + one-off del mismo día/mes es válida.
    if (exceptions.some((exc) => exc.date === newExceptionDate)) {
      setError("Ya hay una excepción para esa fecha");
      return;
    }
    if (exceptions.length >= MAX_DATE_EXCEPTIONS) {
      setError("No se pueden cargar más de 100 fechas especiales");
      return;
    }
    setError(null);
    setExceptions((prev) =>
      sortedExceptionRows([...prev, { date: newExceptionDate, yearly: false, shifts: [] }]),
    );
    setNewExceptionDate("");
  }

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(`${API}/business/profile`, {
        method: "PUT",
        headers: { ...authHeader(), "Content-Type": "application/json" },
        body: JSON.stringify({
          name,
          logoUrl,
          bannerUrl,
          address,
          instagramUrl,
          // Color de marca tal cual: "" limpia (el backend interpreta la
          // cadena vacía como borrado) y un valor presente reemplaza. Enviar
          // null significaría "no tocar" y el color viejo seguiría activo.
          primaryColor,
          // Los siete días SIEMPRE van, cada uno con su lista de turnos
          // (un día cerrado viaja como `[]`): el backend reemplaza todo.
          hours: days.map((shifts, i) => ({
            dayOfWeek: i + 1,
            shifts: shifts.map((s) => ({
              opensAt: toBackendTime(s.opensAt),
              closesAt: toBackendTime(s.closesAt),
            })),
          })),
          // `dateExceptions` SIEMPRE viaja, incluso vacía: el backend la
          // exige (400 si falta) y una lista vacía significa "no hay
          // fechas especiales". Cada fila lleva `yearly` SIEMPRE (el flag
          // anual del backend) y los turnos van ordenados por apertura,
          // convertidos a "HH:mm:ss".
          dateExceptions: exceptions.map((exc) => ({
            date: exc.date,
            yearly: exc.yearly,
            shifts: sortedShiftRows(exc.shifts).map((s) => ({
              opensAt: toBackendTime(s.opensAt),
              closesAt: toBackendTime(s.closesAt),
            })),
          })),
        }),
      });
      if (!res.ok) {
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        throw new Error(data?.message || "Error guardando el perfil del local");
      }
      showSuccessToast("Perfil actualizado");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  if (loading) return null;

  const sortedExceptions = sortedExceptionRows(exceptions);

  return (
    <div className="glass-panel fade-up admin-table-panel admin-profile-panel">
      <form
        className="admin-profile-form"
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        {error && (
          <p className="admin-profile-error" role="alert">
            {error}
          </p>
        )}

        <div className="admin-profile-fields">
          <div className="admin-profile-field admin-profile-field--wide">
            <label htmlFor="profile-name">Nombre del local</label>
            <input
              id="profile-name"
              className="admin-input"
              aria-label="Nombre del local"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="admin-profile-field">
            <label htmlFor="profile-logo">URL del logo</label>
            <input
              id="profile-logo"
              className="admin-input"
              aria-label="URL del logo"
              value={logoUrl}
              onChange={(e) => setLogoUrl(e.target.value)}
            />
            <UrlPreview url={logoUrl} alt="Vista previa del logo" />
          </div>

          <div className="admin-profile-field">
            <label htmlFor="profile-banner">URL del banner</label>
            <input
              id="profile-banner"
              className="admin-input"
              aria-label="URL del banner"
              value={bannerUrl}
              onChange={(e) => setBannerUrl(e.target.value)}
            />
            <UrlPreview url={bannerUrl} alt="Vista previa del banner" />
          </div>

          <div className="admin-profile-field">
            <label htmlFor="profile-address">Dirección</label>
            <input
              id="profile-address"
              className="admin-input"
              aria-label="Dirección"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
            />
          </div>

          <div className="admin-profile-field">
            <label htmlFor="profile-instagram">URL de Instagram</label>
            <input
              id="profile-instagram"
              className="admin-input"
              aria-label="URL de Instagram"
              value={instagramUrl}
              onChange={(e) => setInstagramUrl(e.target.value)}
            />
          </div>

          <div className="admin-profile-field">
            <label htmlFor="profile-primary-color">Color de la carta (#RRGGBB)</label>
            <input
              id="profile-primary-color"
              className="admin-input"
              aria-label="Color de la carta (#RRGGBB)"
              placeholder="#7C3AED"
              value={primaryColor}
              onChange={(e) => setPrimaryColor(e.target.value)}
            />
          </div>
        </div>

        <div className="admin-profile-hours">
          <h3>Horario de atención</h3>
          <p className="admin-profile-hours-note">
            Si la hora de cierre es anterior a la de apertura, el local cierra después de medianoche.
          </p>

          {DAYS.map((day, i) => (
            <div
              key={day}
              className={`admin-profile-day${days[i].length === 0 ? " admin-profile-day--closed" : ""}`}
            >
              <div className="admin-profile-day-head">
                <span className="admin-profile-day-name">{day}</span>
                {days[i].length === 0 && <span className="admin-profile-day-closed">Cerrado {day}</span>}
                <button
                  type="button"
                  className="btn-ghost admin-profile-day-add"
                  aria-label={`Agregar turno ${day}`}
                  onClick={() => addShift(i)}
                >
                  + Agregar turno
                </button>
              </div>
              {days[i].map((shift, j) => (
                <div key={j} className="admin-profile-shift">
                  <input
                    type="time"
                    className="admin-input"
                    aria-label={`Apertura ${day} turno ${j + 1}`}
                    value={shift.opensAt}
                    onChange={(e) => setShift(i, j, { opensAt: e.target.value })}
                  />
                  <input
                    type="time"
                    className="admin-input"
                    aria-label={`Cierre ${day} turno ${j + 1}`}
                    value={shift.closesAt}
                    onChange={(e) => setShift(i, j, { closesAt: e.target.value })}
                  />
                  <button
                    type="button"
                    className="btn-ghost admin-profile-shift-remove"
                    aria-label={`Quitar turno ${day} ${j + 1}`}
                    onClick={() => removeShift(i, j)}
                  >
                    Quitar
                  </button>
                </div>
              ))}
            </div>
          ))}
        </div>

        <div className="admin-profile-hours admin-profile-exceptions">
          <h3>Fechas especiales</h3>
          <p className="admin-profile-hours-note">
            Un feriado se agrega sin turnos (cerrado); con turnos, rige ese horario sólo ese día.
          </p>

          {sortedExceptions.length === 0 ? (
            <p className="admin-profile-exceptions-empty">
              Sin fechas especiales: los días se rigen por el horario semanal.
            </p>
          ) : (
            sortedExceptions.map((exc) => {
              const label = exceptionLabel(exc);
              return (
                <div
                  key={exc.date}
                  className={`admin-profile-day${
                    exc.shifts.length === 0 ? " admin-profile-day--closed" : ""
                  }${exc.yearly ? " admin-profile-day--yearly" : ""}`}
                >
                  <div className="admin-profile-day-head">
                    <span className="admin-profile-day-name">{label}</span>
                    {exc.shifts.length === 0 && (
                      <span className="admin-profile-day-closed">Cerrado {label}</span>
                    )}
                    <label className="admin-profile-exception-yearly">
                      <input
                        type="checkbox"
                        className="admin-profile-exception-yearly-check"
                        aria-label={`Se repite cada año ${label}`}
                        checked={exc.yearly}
                        onChange={(e) => setExceptionYearly(exc.date, e.target.checked)}
                      />
                      Se repite cada año
                    </label>
                    <button
                      type="button"
                      className="btn-ghost admin-profile-day-add"
                      aria-label={`Agregar turno ${label}`}
                      onClick={() => addExceptionShift(exc.date)}
                    >
                      + Agregar turno
                    </button>
                    <button
                      type="button"
                      className="btn-ghost admin-profile-exception-remove"
                      aria-label={`Quitar fecha ${label}`}
                      onClick={() => removeException(exc.date)}
                    >
                      Quitar fecha
                    </button>
                  </div>
                  {exc.shifts.map((shift, j) => (
                    <div key={j} className="admin-profile-shift">
                      <input
                        type="time"
                        className="admin-input"
                        aria-label={`Apertura ${label} turno ${j + 1}`}
                        value={shift.opensAt}
                        onChange={(e) => setExceptionShift(exc.date, j, { opensAt: e.target.value })}
                      />
                      <input
                        type="time"
                        className="admin-input"
                        aria-label={`Cierre ${label} turno ${j + 1}`}
                        value={shift.closesAt}
                        onChange={(e) => setExceptionShift(exc.date, j, { closesAt: e.target.value })}
                      />
                      <button
                        type="button"
                        className="btn-ghost admin-profile-shift-remove"
                        aria-label={`Quitar turno ${label} ${j + 1}`}
                        onClick={() => removeExceptionShift(exc.date, j)}
                      >
                        Quitar
                      </button>
                    </div>
                  ))}
                </div>
              );
            })
          )}

          <div className="admin-profile-exception-add">
            <input
              type="date"
              className="admin-input"
              aria-label="Nueva fecha especial"
              value={newExceptionDate}
              onChange={(e) => setNewExceptionDate(e.target.value)}
            />
            <button type="button" className="btn-ghost" aria-label="Agregar fecha" onClick={addException}>
              + Agregar fecha
            </button>
          </div>
        </div>

        <button type="submit" className="btn-primary admin-profile-save" disabled={saving}>
          {saving ? "Guardando…" : "Guardar cambios"}
        </button>
      </form>
    </div>
  );
}
