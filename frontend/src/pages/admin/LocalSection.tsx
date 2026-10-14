import { useEffect, useState } from "react";
import { API, authHeader } from "./api";
import { showSuccessToast } from "./toast";

/**
 * Editor del perfil del local (vista "Local" del panel admin) contra los
 * endpoints owner GET/PUT /api/business/profile (scoped por el JWT):
 *
 *   - El PUT REEMPLAZA todo el perfil: un campo en blanco ("") lo limpia y
 *     el array de horarios se reconstruye con los siete días, por lo que
 *     nunca se envía de menos.
 *   - Los <input type="time"> hablan "HH:mm"; el backend habla "HH:mm:ss",
 *     así que se convierte en ambas direcciones.
 *   - Convención de medianoche del backend: closesAt MENOR que opensAt
 *     significa que el cierre cae en la madrugada del día siguiente
 *     (viernes 11:00 → 00:30); se permite tal cual y se avisa en una línea.
 *   - Un 400 del backend trae un `message` legible en español: se muestra
 *     inline en vez de fallar en silencio, y el formulario queda usable.
 */

export interface BusinessHoursEntry {
  dayOfWeek: number;
  opensAt: string | null;
  closesAt: string | null;
  closed: boolean;
}

export interface BusinessProfile {
  name?: string | null;
  logoUrl?: string | null;
  bannerUrl?: string | null;
  address?: string | null;
  instagramUrl?: string | null;
  hours?: BusinessHoursEntry[] | null;
}

const DAYS = ["Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"];

interface DayRow {
  closed: boolean;
  opensAt: string; // "HH:mm" para el <input type="time">
  closesAt: string;
}

function emptyDays(): DayRow[] {
  return DAYS.map(() => ({ closed: true, opensAt: "", closesAt: "" }));
}

/** "HH:mm:ss" (backend) → "HH:mm" (input). */
function toInputTime(t: string | null | undefined): string {
  return typeof t === "string" && t ? t.slice(0, 5) : "";
}

/** "HH:mm" (input) → "HH:mm:ss" (backend); vacío → null. */
function toBackendTime(t: string): string | null {
  return t ? `${t}:00` : null;
}

function dayFromEntry(entry: BusinessHoursEntry | undefined): DayRow {
  const closed = entry?.closed ?? true;
  return {
    closed,
    opensAt: closed ? "" : toInputTime(entry?.opensAt),
    closesAt: closed ? "" : toInputTime(entry?.closesAt),
  };
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
  const [days, setDays] = useState<DayRow[]>(emptyDays);
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

  function setDay(index: number, patch: Partial<DayRow>) {
    setDays((prev) => prev.map((d, i) => (i === index ? { ...d, ...patch } : d)));
  }

  function toggleClosed(index: number) {
    setDays((prev) =>
      prev.map((d, i) =>
        i === index ? { closed: !d.closed, opensAt: "", closesAt: "" } : d,
      ),
    );
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
          // Los siete días SIEMPRE van: el backend borra un día que falte.
          hours: days.map((d, i) => ({
            dayOfWeek: i + 1,
            opensAt: d.closed ? null : toBackendTime(d.opensAt),
            closesAt: d.closed ? null : toBackendTime(d.closesAt),
            closed: d.closed,
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
            <div key={day} className={`admin-profile-day${days[i].closed ? " admin-profile-day--closed" : ""}`}>
              <span className="admin-profile-day-name">{day}</span>
              <input
                type="time"
                className="admin-input"
                aria-label={`Apertura ${day}`}
                value={days[i].opensAt}
                disabled={days[i].closed}
                onChange={(e) => setDay(i, { opensAt: e.target.value })}
              />
              <input
                type="time"
                className="admin-input"
                aria-label={`Cierre ${day}`}
                value={days[i].closesAt}
                disabled={days[i].closed}
                onChange={(e) => setDay(i, { closesAt: e.target.value })}
              />
              <label className="admin-profile-day-closed">
                <input
                  type="checkbox"
                  checked={days[i].closed}
                  onChange={() => toggleClosed(i)}
                />
                Cerrado {day}
              </label>
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
