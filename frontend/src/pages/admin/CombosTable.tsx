import { useEffect, useState } from "react";
import { API, authHeader } from "./api";
import ComboModal, { type AdminComboFull } from "./ComboModal";
import { formatARS } from "../dashboard/format";

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
 * Lista de combos del panel admin contra los endpoints owner: GET /api/combos
 * (scoped por el negocio del JWT, con items), POST /api/combos para crear y
 * PUT /api/combos/{id} para editar. El backend NO tiene delete de combos, así
 * que la fila no ofrece uno.
 *
 * Unidad 5 del ODD (admin-panel-ui): buscador + crear + editar. El buscador
 * filtra EN CLIENTE sobre la lista ya cargada — la API no tiene parámetro de
 * búsqueda, igual que productos y categorías. Crear y editar comparten
 * ComboModal (mismo patrón que ProductModal, con picker de productos con
 * cantidades) y recargan la lista al guardar con éxito; si el backend
 * responde 400, el modal queda abierto con su mensaje en español.
 */
export default function CombosTable() {
  const [combos, setCombos] = useState<AdminComboFull[] | null>(null);
  const [query, setQuery] = useState("");
  const [modal, setModal] = useState<ComboModalState | null>(null);

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
                      className="btn-ghost"
                      onClick={() => setModal({ mode: "edit", combo: c })}
                      aria-label={`Editar ${c.name}`}
                    >
                      ✏️
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
    </>
  );
}
