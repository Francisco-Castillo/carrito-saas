import { useLayoutEffect, useState } from "react";
import { hasUsableSession, enforceSession } from "./dashboard/auth";
import "./Platform.css";

/**
 * Panel del operador de plataforma: alta de clientes (plataforma-altas.md)
 * y acciones por fila — editar campos seguros, baja lógica con
 * confirmación, reactivar y reset de clave del dueño
 * (plataforma-editar-baja.md, S1-S4).
 *
 * El operador NO tiene negocio: la sesión viaja sin slug (el login de
 * plataforma emite token sin businessId), así que el guard es el de token
 * usable (hasUsableSession), NO hasPanelSession (ese exige slug de negocio).
 * El contrato de errores del backend es legible y en español (409 unicidad,
 * 400 validación, 404 cliente): se muestra inline, igual que el resto del
 * panel.
 */

interface PlatformClient {
  businessId: number;
  businessName: string;
  slug: string;
  phone?: string | null;
  ownerUsername?: string | null;
  ownerEmail?: string | null;
  active?: boolean | null;
}

const EMPTY_FORM = {
  businessName: "",
  slug: "",
  phone: "",
  ownerUsername: "",
  ownerEmail: "",
  ownerPassword: "",
};

/** Modal por fila (plataforma-editar-baja.md, S1). null = ninguno abierto. */
type RowModal =
  | { kind: "edit"; client: PlatformClient }
  | { kind: "baja"; client: PlatformClient }
  | { kind: "password"; client: PlatformClient };

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
  const [rowModal, setRowModal] = useState<RowModal | null>(null);
  const [rowError, setRowError] = useState<string | null>(null);
  const [rowBusy, setRowBusy] = useState(false);
  const [newPassword, setNewPassword] = useState("");
  const [editName, setEditName] = useState("");
  const [editPhone, setEditPhone] = useState("");
  const [editEmail, setEditEmail] = useState("");

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

  function openRowModal(modal: RowModal) {
    setRowModal(modal);
    setRowError(null);
    setNewPassword("");
    setEditName(modal.client.businessName);
    setEditPhone(modal.client.phone ?? "");
    setEditEmail(modal.client.ownerEmail ?? "");
  }

  function closeRowModal() {
    setRowModal(null);
    setRowError(null);
    setNewPassword("");
  }

  async function rowAction(url: string, method: string, body?: unknown) {
    setRowBusy(true);
    setRowError(null);
    try {
      const res = await fetch(url, {
        method,
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + localStorage.getItem("token"),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      if (!res.ok) {
        throw new Error(await legibleError(res, "No se pudo completar la acción"));
      }
      closeRowModal();
      await loadClients();
    } catch (err) {
      setRowError(err instanceof Error ? err.message : "Error inesperado");
    } finally {
      setRowBusy(false);
    }
  }

  function saveEdit() {
    if (!rowModal || rowModal.kind !== "edit") return;
    const body: Record<string, unknown> = { name: editName.trim() };
    if (editPhone.trim()) body.phone = editPhone.trim();
    if (editEmail.trim()) body.ownerEmail = editEmail.trim();
    void rowAction(`/api/platform/clients/${rowModal.client.businessId}`, "PUT", body);
  }

  function deactivate() {
    if (!rowModal || rowModal.kind !== "baja") return;
    void rowAction(`/api/platform/clients/${rowModal.client.businessId}`, "DELETE");
  }

  function resetPassword() {
    if (!rowModal || rowModal.kind !== "password") return;
    void rowAction(`/api/platform/clients/${rowModal.client.businessId}/password`, "POST", {
      password: newPassword,
    });
  }

  function reactivate(client: PlatformClient) {
    void rowAction(`/api/platform/clients/${client.businessId}`, "PUT", { active: true });
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
                <th>Estado</th>
                <th>Acciones</th>
              </tr>
            </thead>
            <tbody>
              {clients.map((client) => (
                <tr key={client.businessId} className={client.active === false ? "platform-inactive-row" : ""}>
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
                  <td>
                    {client.active === false ? (
                      <span className="platform-inactive-badge">de baja</span>
                    ) : (
                      "activo"
                    )}
                  </td>
                  <td className="platform-actions">
                    {client.active === false ? (
                      <button type="button" onClick={() => reactivate(client)}>
                        Reactivar
                      </button>
                    ) : (
                      <>
                        <button type="button" onClick={() => openRowModal({ kind: "edit", client })}>
                          Editar
                        </button>
                        <button
                          type="button"
                          onClick={() => openRowModal({ kind: "password", client })}
                          disabled={!client.ownerUsername}
                          title={client.ownerUsername ? undefined : "Sin dueño"}
                        >
                          Nueva clave
                        </button>
                        <button
                          type="button"
                          className="platform-danger"
                          onClick={() => openRowModal({ kind: "baja", client })}
                        >
                          Dar de baja
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {rowModal && (
        <div className="platform-modal-backdrop">
          <div className="platform-modal" role="dialog" aria-modal="true">
            {rowModal.kind === "edit" && (
              <>
                <h3>Editar {rowModal.client.businessName}</h3>
                <label>
                  Nombre
                  <input value={editName} onChange={(e) => setEditName(e.target.value)} />
                </label>
                <label>
                  Teléfono
                  <input value={editPhone} onChange={(e) => setEditPhone(e.target.value)} />
                </label>
                <label>
                  Email del dueño
                  <input value={editEmail} onChange={(e) => setEditEmail(e.target.value)} />
                </label>
                <p className="platform-slug-note">
                  Slug <strong>{rowModal.client.slug}</strong> — no se puede cambiar: es la
                  identidad pública del local (QRs impresos, URLs del menú).
                </p>
                <div className="platform-modal-actions">
                  <button type="button" onClick={saveEdit} disabled={rowBusy}>
                    Guardar
                  </button>
                  <button type="button" onClick={closeRowModal} disabled={rowBusy}>
                    Cancelar
                  </button>
                </div>
              </>
            )}

            {rowModal.kind === "baja" && (
              <>
                <h3>Dar de baja {rowModal.client.businessName}</h3>
                <p>
                  El local deja de recibir pedidos y su carta deja de ser pública. Los datos
                  históricos se conservan y la baja es reversible.
                </p>
                <div className="platform-modal-actions">
                  <button type="button" className="platform-danger" onClick={deactivate} disabled={rowBusy}>
                    Confirmar baja
                  </button>
                  <button type="button" onClick={closeRowModal} disabled={rowBusy}>
                    Cancelar
                  </button>
                </div>
              </>
            )}

            {rowModal.kind === "password" && (
              <>
                <h3>Nueva clave para {rowModal.client.ownerUsername}</h3>
                <label>
                  Contraseña
                  <input
                    type="password"
                    placeholder="Nueva contraseña del dueño"
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                  />
                </label>
                <div className="platform-modal-actions">
                  <button type="button" onClick={resetPassword} disabled={rowBusy}>
                    Confirmar
                  </button>
                  <button type="button" onClick={closeRowModal} disabled={rowBusy}>
                    Cancelar
                  </button>
                </div>
              </>
            )}

            {rowError && (
              <p className="platform-error" role="alert">
                {rowError}
              </p>
            )}
          </div>
        </div>
      )}
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
