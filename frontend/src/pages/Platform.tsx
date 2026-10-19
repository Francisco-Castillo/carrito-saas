import { useLayoutEffect, useState } from "react";
import { hasUsableSession, enforceSession } from "./dashboard/auth";
import "./Platform.css";

/**
 * Panel del operador de plataforma (odd/tasks/plataforma-altas.md, S1):
 * alta de clientes — el local (Business) y su dueño (User + membresía
 * OWNER) — y listado de los existentes, contra /api/platform/**.
 *
 * El operador NO tiene negocio: la sesión viaja sin slug (el login de
 * plataforma emite token sin businessId), así que el guard es el de token
 * usable (hasUsableSession), NO hasPanelSession (ese exige slug de negocio).
 * El contrato de errores del backend es legible y en español (409 unicidad,
 * 400 validación): se muestra inline en el formulario, igual que el resto
 * del panel.
 */

interface PlatformClient {
  businessId: number;
  businessName: string;
  slug: string;
  phone?: string | null;
  ownerUsername?: string | null;
  ownerEmail?: string | null;
}

const EMPTY_FORM = {
  businessName: "",
  slug: "",
  phone: "",
  ownerUsername: "",
  ownerEmail: "",
  ownerPassword: "",
};

export default function Platform() {
  useLayoutEffect(() => {
    if (!hasUsableSession()) {
      enforceSession();
    }
  }, []);

  const [clients, setClients] = useState<PlatformClient[] | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [altaError, setAltaError] = useState<string | null>(null);
  const [altaOk, setAltaOk] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useLayoutEffect(() => {
    if (!hasUsableSession()) return;
    void loadClients();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function loadClients() {
    setListError(null);
    try {
      const res = await fetch("/api/platform/clients", {
        headers: { Authorization: "Bearer " + localStorage.getItem("token") },
      });
      if (!res.ok) {
        throw new Error(await legibleError(res, "No se pudo cargar el listado"));
      }
      setClients((await res.json()) as PlatformClient[]);
    } catch (err) {
      setListError(err instanceof Error ? err.message : "Error inesperado");
    }
  }

  function set(field: keyof typeof EMPTY_FORM, value: string) {
    setForm((prev) => ({ ...prev, [field]: value }));
  }

  async function handleAlta(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setAltaError(null);
    setAltaOk(null);

    setSubmitting(true);
    try {
      const res = await fetch("/api/platform/clients", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + localStorage.getItem("token"),
        },
        body: JSON.stringify({
          business: {
            name: form.businessName.trim(),
            slug: form.slug.trim(),
            phone: form.phone.trim() || null,
          },
          owner: {
            username: form.ownerUsername.trim(),
            email: form.ownerEmail.trim(),
            password: form.ownerPassword,
          },
        }),
      });

      if (!res.ok) {
        throw new Error(await legibleError(res, "No se pudo dar de alta el cliente"));
      }

      const created = (await res.json()) as PlatformClient;
      setForm(EMPTY_FORM);
      setAltaOk(`Cliente "${created.businessName}" dado de alta`);
      await loadClients();
    } catch (err) {
      setAltaError(err instanceof Error ? err.message : "Error inesperado");
    } finally {
      setSubmitting(false);
    }
  }

  function logout() {
    for (const key of ["token", "businessId", "role", "username", "expiresAt", "restaurantSlug"]) {
      localStorage.removeItem(key);
    }
    window.location.href = "/login";
  }

  return (
    <div className="platform-page">
      <header className="platform-header">
        <h1>Panel de operador</h1>
        <button type="button" className="platform-logout" onClick={logout}>
          Cerrar sesión
        </button>
      </header>

      <section className="platform-alta" aria-labelledby="platform-alta-title">
        <h2 id="platform-alta-title">Dar de alta un cliente</h2>
        <form onSubmit={handleAlta}>
          <fieldset>
            <legend>Local</legend>
            <input
              placeholder="Nombre del local"
              value={form.businessName}
              onChange={(e) => set("businessName", e.target.value)}
              required
            />
            <input
              placeholder="Slug"
              value={form.slug}
              onChange={(e) => set("slug", e.target.value)}
              required
            />
            <input
              placeholder="Teléfono"
              value={form.phone}
              onChange={(e) => set("phone", e.target.value)}
            />
          </fieldset>
          <fieldset>
            <legend>Dueño</legend>
            <input
              placeholder="Usuario del dueño"
              value={form.ownerUsername}
              onChange={(e) => set("ownerUsername", e.target.value)}
              required
            />
            <input
              type="email"
              placeholder="Email del dueño"
              value={form.ownerEmail}
              onChange={(e) => set("ownerEmail", e.target.value)}
              required
            />
            <input
              type="password"
              placeholder="Contraseña del dueño"
              value={form.ownerPassword}
              onChange={(e) => set("ownerPassword", e.target.value)}
              required
            />
          </fieldset>

          {altaError && (
            <p className="platform-error" role="alert">
              {altaError}
            </p>
          )}
          {altaOk && (
            <p className="platform-ok" role="status">
              {altaOk}
            </p>
          )}

          <button type="submit" disabled={submitting}>
            {submitting ? "Dando de alta…" : "Dar de alta"}
          </button>
        </form>
      </section>

      <section className="platform-listing" aria-labelledby="platform-listing-title">
        <h2 id="platform-listing-title">Clientes</h2>
        {listError && (
          <p className="platform-error" role="alert">
            {listError}
          </p>
        )}
        {clients === null && !listError && <p>Cargando…</p>}
        {clients !== null && clients.length === 0 && <p>Todavía no hay clientes.</p>}
        {clients !== null && clients.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Local</th>
                <th>Slug</th>
                <th>Teléfono</th>
                <th>Dueño</th>
                <th>Email del dueño</th>
              </tr>
            </thead>
            <tbody>
              {clients.map((client) => (
                <tr key={client.businessId}>
                  <td>{client.businessName}</td>
                  <td>{client.slug}</td>
                  <td>{client.phone || "—"}</td>
                  {client.ownerUsername ? (
                    <>
                      <td>{client.ownerUsername}</td>
                      <td>{client.ownerEmail}</td>
                    </>
                  ) : (
                    <td colSpan={2} className="platform-no-owner">
                      sin dueño
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

/** Mensaje de error legible del backend (ErrorResponse.message en español). */
async function legibleError(res: Response, fallback: string): Promise<string> {
  try {
    const data = (await res.json()) as { message?: string };
    return data.message || fallback;
  } catch {
    return fallback;
  }
}
