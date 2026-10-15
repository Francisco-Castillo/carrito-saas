import { useEffect, useState } from "react";
import { API, authHeader } from "./api";
import { showSuccessToast } from "./toast";
import type { BusinessHours } from "../menu/types";

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
 */

export interface BusinessProfile {
  name?: string | null;
  logoUrl?: string | null;
  bannerUrl?: string | null;
  address?: string | null;
  instagramUrl?: string | null;
  hours?: BusinessHours[] | null;
}

const DAYS = ["Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"];

/** Un turno en el estado del formulario: "HH:mm" para los <input type="time">. */
interface ShiftRow {
  opensAt: string;
  closesAt: string;
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

function dayFromEntry(entry: BusinessHours | undefined): ShiftRow[] {
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
  const [days, setDays] = useState<ShiftRow[][]>(emptyDays);
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
        const byDay = new Map((data.hours ?? []).map((h) => [h.dayOfWeek, h]));
        setDays(DAYS.map((_, i) => dayFromEntry(byDay.get(i + 1))));
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
          // Los siete días SIEMPRE van, cada uno con su lista de turnos
          // (un día cerrado viaja como `[]`): el backend reemplaza todo.
          hours: days.map((shifts, i) => ({
            dayOfWeek: i + 1,
            shifts: shifts.map((s) => ({
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

        <button type="submit" className="btn-primary admin-profile-save" disabled={saving}>
          {saving ? "Guardando…" : "Guardar cambios"}
        </button>
      </form>
    </div>
  );
}
