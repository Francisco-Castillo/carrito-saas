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

/** Shape del slug en el backend (PlatformClientServiceImpl.SLUG_SHAPE). */
const SLUG_SHAPE = /^[a-z0-9-]+$/;
/** Shape del teléfono en el backend (PHONE_SHAPE): 8-14 dígitos, sin 0 inicial. */
const PHONE_SHAPE = /^[1-9][0-9]{7,13}$/;

/** Normaliza un nombre de local a slug válido: minúsculas, sin acentos,
 * espacios y símbolos a guiones. Así el operador no puede escribir un slug
 * inválido por descuido — la causa más común del 400 en el alta. */
function normalizeSlug(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

/** Mapea el mensaje legible del backend al campo del formulario que hay que
 * corregir; null = error general del alta. El orden importa: "nombre del
 * local" es del negocio, el resto de "nombre" es del usuario dueño. */
function mapAltaErrorField(message: string): keyof typeof EMPTY_FORM | null {
  const m = message.toLowerCase();
  if (m.includes("nombre del local")) return "businessName";
  if (m.includes("slug")) return "slug";
  if (m.includes("teléfono")) return "phone";
  if (m.includes("contraseña")) return "ownerPassword";
  if (m.includes("email")) return "ownerEmail";
  if (m.includes("usuario") || m.includes("nombre")) return "ownerUsername";
  return null;
}

/** Modal por fila (plataforma-editar-baja.md, S1). null = ninguno abierto. */
type RowModal =
  | { kind: "edit"; client: PlatformClient }
  | { kind: "baja"; client: PlatformClient }
  | { kind: "password"; client: PlatformClient };

/** Sección de usuarios de un cliente (plataforma-usuarios.md, S1-S3). */
type UsersSection = { client: PlatformClient };

interface PlatformUser {
  username: string;
  email?: string | null;
  role: string;
  active?: boolean | null;
}

const EMPTY_USER_FORM = {
  username: "",
  email: "",
  password: "",
  role: "KITCHEN",
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
  const [slugTouched, setSlugTouched] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<keyof typeof EMPTY_FORM, string>>>({});
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
  const [usersSection, setUsersSection] = useState<UsersSection | null>(null);
  const [users, setUsers] = useState<PlatformUser[] | null>(null);
  const [usersError, setUsersError] = useState<string | null>(null);
  const [userForm, setUserForm] = useState(EMPTY_USER_FORM);
  const [userError, setUserError] = useState<string | null>(null);
  const [userBusy, setUserBusy] = useState(false);

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

  /** Nombre del local: mientras el operador no haya editado el slug a mano,
   * el slug se regenera en vivo desde el nombre (ya normalizado). */
  function handleBusinessName(value: string) {
    setForm((prev) => ({
      ...prev,
      businessName: value,
      slug: slugTouched ? prev.slug : normalizeSlug(value),
    }));
  }

  /** Validación client-side con los mismos shapes que el backend: los
   * errores aparecen junto al campo y el POST no sale si algo falla. */
  function validateAltaForm(): Partial<Record<keyof typeof EMPTY_FORM, string>> | null {
    const errors: Partial<Record<keyof typeof EMPTY_FORM, string>> = {};
    const slug = form.slug.trim();
    const phone = form.phone.trim();
    if (!slug) {
      errors.slug = "El slug es obligatorio";
    } else if (!SLUG_SHAPE.test(slug)) {
      errors.slug = "El slug sólo admite minúsculas, números y guiones";
    }
    if (phone && !PHONE_SHAPE.test(phone)) {
      errors.phone = "El teléfono sólo admite dígitos: código de área y número, sin 0 ni 15";
    }
    if (form.ownerPassword.length < 8) {
      errors.ownerPassword = "La contraseña debe tener al menos 8 caracteres";
    }
    return Object.keys(errors).length > 0 ? errors : null;
  }

  async function handleAlta(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setAltaError(null);
    setAltaOk(null);

    const errors = validateAltaForm();
    if (errors) {
      setFieldErrors(errors);
      return;
    }
    setFieldErrors({});

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
      setSlugTouched(false);
      setFieldErrors({});
      setAltaOk(`Cliente "${created.businessName}" dado de alta`);
      await loadClients();
    } catch (err) {
      const message = err instanceof Error ? err.message : "Error inesperado";
      // El error del backend va junto al campo que hay que corregir; si no
      // se puede mapear a un campo, queda como error general del alta.
      const field = mapAltaErrorField(message);
      if (field) {
        setFieldErrors({ [field]: message });
      } else {
        setAltaError(message);
      }
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

  // ------------------------------------------------------------------
  // Sección de usuarios del cliente (plataforma-usuarios.md, S1-S3)
  // ------------------------------------------------------------------

  async function loadUsers(client: PlatformClient) {
    setUsersError(null);
    try {
      const res = await fetch(`/api/platform/clients/${client.businessId}/users`, {
        headers: { Authorization: "Bearer " + localStorage.getItem("token") },
      });
      if (!res.ok) {
        throw new Error(await legibleError(res, "No se pudo cargar el listado de usuarios"));
      }
      setUsers((await res.json()) as PlatformUser[]);
    } catch (err) {
      setUsersError(err instanceof Error ? err.message : "Error inesperado");
    }
  }

  function openUsers(client: PlatformClient) {
    setUsersSection({ client });
    setUsers(null);
    setUsersError(null);
    setUserForm(EMPTY_USER_FORM);
    setUserError(null);
    void loadUsers(client);
  }

  function closeUsers() {
    setUsersSection(null);
    setUsers(null);
    setUsersError(null);
    setUserError(null);
  }

  function setUserField(field: keyof typeof EMPTY_USER_FORM, value: string) {
    setUserForm((prev) => ({ ...prev, [field]: value }));
  }

  async function handleUserAlta(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!usersSection) return;
    setUserBusy(true);
    setUserError(null);
    try {
      const res = await fetch(`/api/platform/clients/${usersSection.client.businessId}/users`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + localStorage.getItem("token"),
        },
        body: JSON.stringify({
          username: userForm.username.trim(),
          email: userForm.email.trim(),
          password: userForm.password,
          role: userForm.role,
        }),
      });
      if (!res.ok) {
        throw new Error(await legibleError(res, "No se pudo crear el usuario"));
      }
      setUserForm(EMPTY_USER_FORM);
      await loadUsers(usersSection.client);
    } catch (err) {
      setUserError(err instanceof Error ? err.message : "Error inesperado");
    } finally {
      setUserBusy(false);
    }
  }

  async function deactivateUser(user: PlatformUser) {
    if (!usersSection) return;
    setUserBusy(true);
    setUserError(null);
    try {
      const res = await fetch(
        `/api/platform/clients/${usersSection.client.businessId}/users/${user.username}`,
        {
          method: "DELETE",
          headers: { Authorization: "Bearer " + localStorage.getItem("token") },
        },
      );
      if (!res.ok) {
        throw new Error(await legibleError(res, "No se pudo dar de baja el usuario"));
      }
      await loadUsers(usersSection.client);
    } catch (err) {
      setUserError(err instanceof Error ? err.message : "Error inesperado");
    } finally {
      setUserBusy(false);
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
      <div className="aurora" aria-hidden="true">
        <div className="aurora-blob aurora-blob--indigo" />
        <div className="aurora-blob aurora-blob--violet" />
        <div className="aurora-blob aurora-blob--fuchsia" />
        <div className="aurora-blob aurora-blob--cyan" />
      </div>

      <header className="platform-header">
        <div>
          <h1>Panel de operador</h1>
          <p className="platform-subtitle">Altas, bajas y cuentas de los locales de la plataforma</p>
        </div>
        <button type="button" className="platform-logout" onClick={logout}>
          Cerrar sesión
        </button>
      </header>

      <section className="platform-alta glass-panel fade-up" aria-labelledby="platform-alta-title">
        <h2 id="platform-alta-title">Dar de alta un cliente</h2>
        <h2 id="platform-alta-title">Dar de alta un cliente</h2>
        <form onSubmit={handleAlta} noValidate>
          <fieldset>
            <legend>Local</legend>
            <label className="platform-field">
              <span>Nombre del local</span>
              <input
                placeholder="Nombre del local"
                value={form.businessName}
                onChange={(e) => handleBusinessName(e.target.value)}
                required
              />
              {fieldErrors.businessName && (
                <p className="platform-field-error" role="alert">
                  {fieldErrors.businessName}
                </p>
              )}
            </label>
            <label className="platform-field">
              <span>Slug</span>
              <input
                placeholder="Slug"
                value={form.slug}
                onChange={(e) => {
                  setSlugTouched(true);
                  set("slug", e.target.value);
                }}
                required
              />
              <small className="platform-hint">
                Se completa solo desde el nombre. Es la URL pública del menú (QRs impresos).
              </small>
              {fieldErrors.slug && (
                <p className="platform-field-error" role="alert">
                  {fieldErrors.slug}
                </p>
              )}
            </label>
            <label className="platform-field">
              <span>Teléfono</span>
              <input
                placeholder="Teléfono"
                inputMode="numeric"
                value={form.phone}
                onChange={(e) => set("phone", e.target.value)}
              />
              <small className="platform-hint">Sólo dígitos, sin 0 ni 15 (ej. 1150001111)</small>
              {fieldErrors.phone && (
                <p className="platform-field-error" role="alert">
                  {fieldErrors.phone}
                </p>
              )}
            </label>
          </fieldset>
          <fieldset>
            <legend>Dueño</legend>
            <label className="platform-field">
              <span>Usuario del dueño</span>
              <input
                placeholder="Usuario del dueño"
                value={form.ownerUsername}
                onChange={(e) => set("ownerUsername", e.target.value)}
                required
              />
              {fieldErrors.ownerUsername && (
                <p className="platform-field-error" role="alert">
                  {fieldErrors.ownerUsername}
                </p>
              )}
            </label>
            <label className="platform-field">
              <span>Email del dueño</span>
              <input
                type="email"
                placeholder="Email del dueño"
                value={form.ownerEmail}
                onChange={(e) => set("ownerEmail", e.target.value)}
                required
              />
              {fieldErrors.ownerEmail && (
                <p className="platform-field-error" role="alert">
                  {fieldErrors.ownerEmail}
                </p>
              )}
            </label>
            <label className="platform-field">
              <span>Contraseña del dueño</span>
              <input
                type="password"
                placeholder="Contraseña del dueño"
                value={form.ownerPassword}
                onChange={(e) => set("ownerPassword", e.target.value)}
                required
              />
              {fieldErrors.ownerPassword && (
                <p className="platform-field-error" role="alert">
                  {fieldErrors.ownerPassword}
                </p>
              )}
            </label>
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

          <button type="submit" className="platform-btn platform-btn--primary" disabled={submitting}>
            {submitting ? "Dando de alta…" : "Dar de alta"}
          </button>
        </form>
      </section>

      <section className="platform-listing glass-panel fade-up" aria-labelledby="platform-listing-title">
        <div className="platform-listing-head">
          <h2 id="platform-listing-title">Clientes</h2>
          {clients !== null && (
            <span className="platform-count">
              {clients.length} {clients.length === 1 ? "local" : "locales"}
            </span>
          )}
        </div>
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
                      <span className="platform-badge platform-badge--inactive">de baja</span>
                    ) : (
                      <span className="platform-badge platform-badge--active">activo</span>
                    )}
                  </td>
                  <td className="platform-actions">
                    {client.active === false ? (
                      <button type="button" className="platform-btn" onClick={() => reactivate(client)}>
                        Reactivar
                      </button>
                    ) : (
                      <>
                        <button type="button" className="platform-btn" onClick={() => openUsers(client)}>
                          Usuarios
                        </button>
                        <button type="button" className="platform-btn" onClick={() => openRowModal({ kind: "edit", client })}>
                          Editar
                        </button>
                        <button
                          type="button"
                          className="platform-btn"
                          onClick={() => openRowModal({ kind: "password", client })}
                          disabled={!client.ownerUsername}
                          title={client.ownerUsername ? undefined : "Sin dueño"}
                        >
                          Nueva clave
                        </button>
                        <button
                          type="button"
                          className="platform-btn platform-btn--danger"
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
                  <button type="button" className="platform-btn platform-btn--primary" onClick={saveEdit} disabled={rowBusy}>
                    Guardar
                  </button>
                  <button type="button" className="platform-btn" onClick={closeRowModal} disabled={rowBusy}>
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
                  <button type="button" className="platform-btn platform-btn--danger" onClick={deactivate} disabled={rowBusy}>
                    Confirmar baja
                  </button>
                  <button type="button" className="platform-btn" onClick={closeRowModal} disabled={rowBusy}>
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
                  <button type="button" className="platform-btn platform-btn--primary" onClick={resetPassword} disabled={rowBusy}>
                    Confirmar
                  </button>
                  <button type="button" className="platform-btn" onClick={closeRowModal} disabled={rowBusy}>
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

      {usersSection && (
        <div className="platform-modal-backdrop">
          <div className="platform-modal platform-users-modal" role="dialog" aria-modal="true">
            <h3>Usuarios de {usersSection.client.businessName}</h3>

            {usersError && (
              <p className="platform-error" role="alert">
                {usersError}
              </p>
            )}

            {users === null && !usersError && <p>Cargando…</p>}
            {users !== null && users.length === 0 && <p>El local todavía no tiene usuarios.</p>}
            {users !== null && users.length > 0 && (
              <table className="platform-users-table">
                <thead>
                  <tr>
                    <th>Usuario</th>
                    <th>Email</th>
                    <th>Rol</th>
                    <th>Estado</th>
                    <th>Acciones</th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((user) => (
                    <tr key={user.username}>
                      <td>{user.username}</td>
                      <td>{user.email || "—"}</td>
                      <td>{user.role}</td>
                      <td>
                        {user.active === false ? (
                          <span className="platform-badge platform-badge--inactive">de baja</span>
                        ) : (
                          <span className="platform-badge platform-badge--active">activo</span>
                        )}
                      </td>
                      <td>
                        {user.active !== false && (
                          <button
                            type="button"
                            className="platform-danger"
                            onClick={() => deactivateUser(user)}
                            disabled={userBusy}
                          >
                            Dar de baja
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <form onSubmit={handleUserAlta} className="platform-user-alta">
              <h4>Crear usuario</h4>
              <div className="platform-user-fields">
                <input
                  placeholder="Usuario"
                  value={userForm.username}
                  onChange={(e) => setUserField("username", e.target.value)}
                  required
                />
                <input
                  type="email"
                  placeholder="Email"
                  value={userForm.email}
                  onChange={(e) => setUserField("email", e.target.value)}
                  required
                />
                <input
                  type="password"
                  placeholder="Contraseña"
                  value={userForm.password}
                  onChange={(e) => setUserField("password", e.target.value)}
                  required
                />
                <label>
                  Rol{" "}
                  <select value={userForm.role} onChange={(e) => setUserField("role", e.target.value)}>
                    {/* Sin ADMIN: rol exclusivo de la plataforma
                        (simplificacion-roles.md, S2); el backend lo rechaza. */}
                    <option value="KITCHEN">KITCHEN</option>
                    <option value="OWNER">OWNER</option>
                    <option value="PEDIDOS">PEDIDOS</option>
                  </select>
                </label>
              </div>

              {userError && (
                <p className="platform-error" role="alert">
                  {userError}
                </p>
              )}

              <div className="platform-modal-actions">
                <button type="submit" className="platform-btn platform-btn--primary" disabled={userBusy}>
                  {userBusy ? "Creando…" : "Crear usuario"}
                </button>
                <button type="button" className="platform-btn" onClick={closeUsers} disabled={userBusy}>
                  Cerrar
                </button>
              </div>
            </form>
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
