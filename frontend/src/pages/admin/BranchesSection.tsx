import { useEffect, useState } from "react";
import { API, authHeader } from "./api";
import LoadingPanel from "./LoadingPanel";
import { useConfirmDialog } from "./useConfirmDialog";
import ConfirmModal from "./ConfirmModal";

/**
 * Vista "Sucursales" del panel admin, contra el contrato ya implementado en
 * el backend (T1-T7 de odd/tasks/multi-sucursal), todo owner scoped por el
 * JWT:
 *
 *   - LISTA: GET /api/business/branches devuelve TODAS las sucursales
 *     (incluye inactivas, orden por id); cada fila muestra nombre, dirección
 *     y WhatsApp, con badge "inactiva" si active=false.
 *   - ALTA/EDICIÓN: POST /api/business/branches o PUT .../{id} con
 *     {name, address?, phone?}. La validación de formato vive en el backend
 *     (español): acá sólo se custodia el required de nombre, con el MISMO
 *     mensaje que devuelve el API, y los errores del backend se muestran con
 *     el mensaje exacto que viaja.
 *   - BAJA: DELETE .../{id} es baja lógica (active=false) SIEMPRE detrás del
 *     ConfirmModal común del panel. NO hay reactivación en el API: no se
 *     inventa UI que el backend no sostiene.
 *   - "TRABAJAR ACÁ": POST .../select/{id} (sólo sucursales propias y
 *     activas) devuelve {token, branchId, branchName, expiresAt}: el token
 *     de la sesión se REEMPLAZA (localStorage "token", leído en caliente por
 *     authHeader) junto con "expiresAt", preservando el resto de la sesión
 *     (businessId, role, username, restaurantSlug). El JWT fija la sucursal
 *     activa de todo el panel. Un 404 (sucursal recién dada de baja) muestra
 *     el error y refresca la lista.
 *   - QR: GET .../{id}/qr responde image/png; como los endpoints owner
 *     exigen Bearer, se pide con fetch y se baja vía blob → <a download> —
 *     mismo helper que QrTablesSection (replicado: es módulo-privado allí).
 */

interface Branch {
  id: number;
  name: string;
  address?: string | null;
  phone?: string | null;
  active: boolean;
  businessId: number;
}

/** Mensaje exacto del backend para el required de nombre (guard local). */
const NAME_REQUIRED = "El nombre de la sucursal es obligatorio";

/**
 * branchId del JWT de la sesión (claim branchId, fase 2 de multi-sucursal):
 * marca visualmente la sucursal en la que se está trabajando. Sin claim
 * (token viejo o vista negocio) devuelve null.
 */
function currentBranchIdFromToken(): number | null {
  try {
    const token = localStorage.getItem("token") ?? "";
    const payload = token.split(".")[1];
    if (!payload) return null;
    const claims = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/"))) as { branchId?: number };
    return typeof claims.branchId === "number" ? claims.branchId : null;
  } catch {
    return null;
  }
}

/** Descarga binaria autenticada: fetch → blob → anchor → revoke (QrTablesSection). */
async function downloadBinary(url: string, filename: string, onError: (msg: string) => void) {
  try {
    const res = await fetch(url, { headers: authHeader() });
    if (!res.ok) throw new Error("No se pudo descargar el QR");
    const blob = await res.blob();
    const objectUrl = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = objectUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(objectUrl);
  } catch (e) {
    onError((e as Error).message);
  }
}

export default function BranchesSection() {
  const [branches, setBranches] = useState<Branch[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // null = alta; número = editando esa sucursal.
  const [editingId, setEditingId] = useState<number | null>(null);
  const [name, setName] = useState("");
  const [address, setAddress] = useState("");
  const [phone, setPhone] = useState("");
  // Sucursal del JWT actual (se actualiza al elegir "Trabajar acá").
  const [currentBranchId, setCurrentBranchId] = useState<number | null>(() => currentBranchIdFromToken());
  const { confirmState, confirmAsync, settleConfirm } = useConfirmDialog();

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch(`${API}/business/branches`, { headers: authHeader() });
        if (!res.ok) throw new Error("Error cargando las sucursales");
        const data = (await res.json()) as Branch[];
        if (!cancelled) setBranches(data);
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

  async function refreshBranches() {
    const res = await fetch(`${API}/business/branches`, { headers: authHeader() });
    if (!res.ok) throw new Error("Error recargando las sucursales");
    setBranches((await res.json()) as Branch[]);
  }

  function resetForm() {
    setEditingId(null);
    setName("");
    setAddress("");
    setPhone("");
  }

  function startEdit(branch: Branch) {
    setEditingId(branch.id);
    setName(branch.name);
    setAddress(branch.address ?? "");
    setPhone(branch.phone ?? "");
    setError(null);
  }

  async function submitForm() {
    // Guard local SOLO del required (el formato lo valida el backend).
    if (!name.trim()) {
      setError(NAME_REQUIRED);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(
        editingId === null ? `${API}/business/branches` : `${API}/business/branches/${editingId}`,
        {
          method: editingId === null ? "POST" : "PUT",
          headers: { ...authHeader(), "Content-Type": "application/json" },
          // Dirección y WhatsApp opcionales: vacío = no viajar (el backend
          // interpreta la ausencia como campo sin definir).
          body: JSON.stringify({
            name: name.trim(),
            ...(address.trim() ? { address: address.trim() } : {}),
            ...(phone.trim() ? { phone: phone.trim() } : {}),
          }),
        },
      );
      if (!res.ok) {
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        throw new Error(data?.message || "No se pudo guardar la sucursal");
      }
      resetForm();
      await refreshBranches();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function removeBranch(branch: Branch) {
    const ok = await confirmAsync(
      `¿Seguro que deseas eliminar ${branch.name}? La sucursal quedará inactiva y no se puede reactivar.`,
    );
    if (!ok) return;
    setError(null);
    try {
      const res = await fetch(`${API}/business/branches/${branch.id}`, {
        method: "DELETE",
        headers: authHeader(),
      });
      if (!res.ok) {
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        throw new Error(data?.message || `No se pudo eliminar ${branch.name}`);
      }
      await refreshBranches();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function workHere(branch: Branch) {
    setError(null);
    try {
      const res = await fetch(`${API}/business/branches/select/${branch.id}`, {
        method: "POST",
        headers: authHeader(),
      });
      const data = (await res.json().catch(() => null)) as
        | { token?: string; branchId?: number; branchName?: string; expiresAt?: string; message?: string }
        | null;
      if (!res.ok || !data?.token) {
        // 404 típico: la sucursal se dio de baja desde otra pestaña.
        throw new Error(data?.message || "No se pudo cambiar a esa sucursal");
      }
      // REEMPLAZO del token del panel (authHeader lo lee en caliente) + su
      // expiración; el resto de la sesión queda intacto.
      localStorage.setItem("token", data.token);
      if (data.expiresAt) localStorage.setItem("expiresAt", data.expiresAt);
      if (typeof data.branchId === "number") setCurrentBranchId(data.branchId);
      resetForm();
      // La lista se refresca igual: el estado real manda sobre el óptimo.
      await refreshBranches();
    } catch (e) {
      setError((e as Error).message);
      try {
        await refreshBranches();
      } catch {
        /* el error de recarga ya quedó reportado arriba si correspondía */
      }
    }
  }

  async function downloadBranchQr(branch: Branch) {
    await downloadBinary(`${API}/business/branches/${branch.id}/qr`, `qr-${branch.name}.png`, setError);
  }

  if (loading) return <LoadingPanel label="Cargando sucursales…" lines={5} />;

  return (
    <div className="glass-panel fade-up admin-table-panel admin-branches-panel">
      <h3>Sucursales</h3>

      {error && (
        <p className="admin-profile-error" role="alert">
          {error}
        </p>
      )}

      <section aria-label="Alta y edición de sucursales">
        <div className="admin-profile-fields">
          <div className="admin-profile-field">
            <label htmlFor="branch-name">Nombre de la sucursal</label>
            <input
              id="branch-name"
              className="admin-input"
              aria-label="Nombre de la sucursal"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="admin-profile-field">
            <label htmlFor="branch-address">Dirección</label>
            <input
              id="branch-address"
              className="admin-input"
              aria-label="Dirección de la sucursal"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
            />
          </div>
          <div className="admin-profile-field">
            <label htmlFor="branch-phone">WhatsApp de la sucursal</label>
            <input
              id="branch-phone"
              className="admin-input"
              aria-label="WhatsApp de la sucursal"
              placeholder="5491123456789"
              inputMode="tel"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
            />
          </div>
        </div>
        <button
          type="button"
          className="btn-primary admin-profile-save"
          disabled={saving}
          onClick={() => void submitForm()}
        >
          {saving ? "Guardando…" : editingId === null ? "Agregar sucursal" : "Guardar cambios"}
        </button>
        {editingId !== null && (
          <button type="button" className="btn-ghost" onClick={resetForm}>
            Cancelar edición
          </button>
        )}
      </section>

      {branches.length === 0 ? (
        <p className="admin-profile-exceptions-empty">
          Todavía no hay sucursales: cargá la primera con el formulario de arriba.
        </p>
      ) : (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Sucursal</th>
                <th>Dirección</th>
                <th>WhatsApp</th>
                <th>Estado</th>
                <th>Acciones</th>
              </tr>
            </thead>
            <tbody>
              {branches.map((branch) => (
                <tr key={branch.id}>
                  <td>
                    {branch.name}
                    {branch.id === currentBranchId && (
                      <span className="admin-branch-current"> · actual</span>
                    )}
                  </td>
                  <td>{branch.address || "—"}</td>
                  <td>{branch.phone || "—"}</td>
                  <td>{branch.active ? "Activa" : <span className="badge inactive">Inactiva</span>}</td>
                  <td className="admin-qr-actions">
                    {branch.active && (
                      <>
                        <button
                          type="button"
                          className="btn-ghost"
                          aria-label={`Trabajar acá ${branch.name}`}
                          onClick={() => void workHere(branch)}
                        >
                          Trabajar acá
                        </button>
                        <button
                          type="button"
                          className="btn-ghost"
                          aria-label={`Descargar QR ${branch.name}`}
                          onClick={() => void downloadBranchQr(branch)}
                        >
                          QR
                        </button>
                      </>
                    )}
                    <button
                      type="button"
                      className="btn-ghost"
                      aria-label={`Editar ${branch.name}`}
                      onClick={() => startEdit(branch)}
                    >
                      Editar
                    </button>
                    <button
                      type="button"
                      className="btn-ghost admin-qr-remove"
                      aria-label={`Eliminar ${branch.name}`}
                      onClick={() => void removeBranch(branch)}
                    >
                      Eliminar
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {confirmState && (
        <ConfirmModal message={confirmState.message} onConfirm={() => settleConfirm(true)} onCancel={() => settleConfirm(false)} />
      )}
    </div>
  );
}
