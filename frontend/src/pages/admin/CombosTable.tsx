import { useEffect, useRef, useState } from "react";
import { API, authHeader } from "./api";
import ComboModal, { type AdminComboFull } from "./ComboModal";
import { showSuccessToast } from "./toast";
import ConfirmModal from "./ConfirmModal";
import { formatARS } from "../../format";

interface ConfirmState {
  message: string;
  resolve: (ok: boolean) => void;
}

/**
 * El catálogo es en español: el buscador tiene que encontrar "César" cuando
 * se escribe "cesar". Mismo helper que ProductsTable y CategoriesTable (NFD
 * separa el diacrítico en un combining mark y el rango U+0300–U+036F lo
 * elimina; lowercase para el caso).
 */
function normalize(text: string): string {
  return text
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

/** Modal abierto: crear (sin combo) o editar (prefill desde la fila). */
type ComboModalState = { mode: "create" } | { mode: "edit"; combo: AdminComboFull };

/**
 * Fila de la tabla: el ComboDTO del backend lleva `active` desde
 * admin-delete-and-toggle (mapeado por IComboMapperImpl), campo que el modal
 * no consume. El tipo extensor vive acá porque ComboModal.tsx no es superficie
 * de edición de esta unidad.
 */
type AdminComboRow = AdminComboFull & { active?: boolean };

/**
 * Lista de combos del panel admin contra los endpoints owner: GET /api/combos
 * (scoped por el negocio del JWT, con items), POST /api/combos para crear,
 * PUT /api/combos/{id} para editar, PUT /api/combos/{id}/activar para el
 * toggle (el ComboDTO lleva active desde admin-delete-and-toggle) y
 * DELETE /api/combos/{id} para eliminar (409 si un pedido lo referencia: el
 * mensaje del backend se muestra en el toast).
 *
 * Unidad 5 del ODD (admin-panel-ui): buscador + crear + editar. El buscador
 * filtra EN CLIENTE sobre la lista ya cargada — la API no tiene parámetro de
 * búsqueda, igual que productos y categorías. Crear y editar comparten
 * ComboModal (mismo patrón que ProductModal, con picker de productos con
 * cantidades) y recargan la lista al guardar con éxito; si el backend
 * responde 400, el modal queda abierto con su mensaje en español.
 */
export default function CombosTable() {
  const [combos, setCombos] = useState<AdminComboRow[] | null>(null);
  const [query, setQuery] = useState("");
  const [modal, setModal] = useState<ComboModalState | null>(null);
  /** Confirmación de delete: mismo patrón que ProductsTable y CategoriesTable. */
  const [confirmState, setConfirmState] = useState<ConfirmState | null>(null);
  const confirmResolveRef = useRef<((ok: boolean) => void) | null>(null);

  async function loadCombos() {
    try {
      const res = await fetch(`${API}/combos`, { headers: authHeader() });
      if (!res.ok) throw new Error("Error cargando combos");
      const data = (await res.json()) as AdminComboFull[];
      setCombos(data);
    } catch (e) {
      console.error("Error cargando combos", e);
    }
  }

  useEffect(() => {
    void loadCombos();
  }, []);

  useEffect(() => {
    // Si el componente se desmonta con una confirmación pendiente, resolverla.
    return () => confirmResolveRef.current?.(false);
  }, []);

  /** showToastModal del legacy: promesa que responde true solo con "Confirmar". */
  function confirmAsync(message: string): Promise<boolean> {
    return new Promise((resolve) => {
      confirmResolveRef.current = resolve;
      setConfirmState({ message, resolve });
    });
  }

  function settleConfirm(ok: boolean) {
    confirmResolveRef.current?.(ok);
    confirmResolveRef.current = null;
    setConfirmState(null);
  }

  async function toggleCombo(c: AdminComboRow) {
    const activar = !c.active;

    try {
      // Mismo shape que el toggle de productos y categorías: PUT .../activar
      // con el estado DESTINO. El backend sólo cambia el flag, sin tocar items.
      const res = await fetch(`${API}/combos/${c.id}/activar?activo=${activar}`, {
        method: "PUT",
        headers: authHeader(),
      });

      if (!res.ok) {
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        throw new Error(data?.message || "Error actualizando el combo");
      }

      // El legacy actualizaba la fila sin recargar toda la tabla (igual que
      // el switch de categorías).
      setCombos((prev) =>
        prev ? prev.map((x) => (x.id === c.id ? { ...x, active: activar } : x)) : prev,
      );
      showSuccessToast(`Combo ${activar ? "activado" : "desactivado"} con éxito`);
    } catch (e) {
      showSuccessToast(`Error: ${(e as Error).message}`);
    }
  }

  async function deleteCombo(c: AdminComboRow) {
    const ok = await confirmAsync(`¿Seguro que deseas eliminar el combo "${c.name}"?`);
    if (!ok) return;

    try {
      const res = await fetch(`${API}/combos/${c.id}`, {
        method: "DELETE",
        headers: authHeader(),
      });

      if (res.status === 409) {
        // El combo está referenciado por pedidos: el backend manda un mensaje
        // legible que nombra al bloqueador.
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        showSuccessToast(`Error: ${data?.message || "El combo está siendo usado por pedidos"}`);
        return;
      }
      if (!res.ok) throw new Error("Error eliminando el combo");

      showSuccessToast("Combo eliminado con éxito");
      void loadCombos();
    } catch (e) {
      showSuccessToast(`Error: ${(e as Error).message}`);
    }
  }

  if (combos === null) return null;

  // Filtro en cliente sobre la lista ya cargada (accent- y case-insensitive).
  const needle = normalize(query.trim());
  const filtered = needle === "" ? combos : combos.filter((c) => normalize(c.name).includes(needle));

  return (
    <>
      <div className="glass-panel fade-up admin-table-panel">
        {/* Misma piel de toolbar que las tablas de productos y categorías
            (clases compartidas en Admin.css): los tres catálogos tienen que
            verse como hermanos. */}
        <div className="products-toolbar">
          <input
            type="search"
            className="admin-input products-toolbar-search"
            placeholder="Buscar combos…"
            aria-label="Buscar combos por nombre"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <button type="button" className="btn-primary" onClick={() => setModal({ mode: "create" })}>
            + Nuevo combo
          </button>
        </div>

        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Nombre</th>
                <th>Precio</th>
                <th>Acciones</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 && (
                <tr>
                  <td colSpan={3} className="admin-empty-state">
                    {combos.length === 0
                      ? 'Todavía no hay combos. Creá el primero con "+ Nuevo combo".'
                      : "No hay combos que coincidan con la búsqueda"}
                  </td>
                </tr>
              )}
              {filtered.map((c) => (
                <tr key={c.id}>
                  <td>{c.name}</td>
                  <td>{formatARS(c.price)}</td>
                  <td className="row-actions">
                    <button
                      type="button"
                      role="switch"
                      aria-checked={!!c.active}
                      aria-label={`${c.active ? "Desactivar" : "Activar"} ${c.name}`}
                      className={`admin-switch${c.active ? " is-on" : ""}`}
                      onClick={() => void toggleCombo(c)}
                    >
                      <span className="admin-switch-knob" aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="btn-ghost"
                      onClick={() => setModal({ mode: "edit", combo: c })}
                      aria-label={`Editar ${c.name}`}
                    >
                      ✏️
                    </button>
                    <button
                      type="button"
                      className="btn-ghost"
                      onClick={() => void deleteCombo(c)}
                      aria-label={`Eliminar ${c.name}`}
                    >
                      🗑
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {modal && (
        <ComboModal
          combo={modal.mode === "edit" ? modal.combo : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            void loadCombos();
          }}
        />
      )}

      {confirmState && (
        <ConfirmModal
          message={confirmState.message}
          onConfirm={() => settleConfirm(true)}
          onCancel={() => settleConfirm(false)}
        />
      )}
    </>
  );
}
