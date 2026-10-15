import { useEffect, useRef, useState } from "react";
import { API, authHeader, type AdminProduct } from "./api";
import { showSuccessToast } from "./toast";
import ConfirmModal from "./ConfirmModal";
import ProductModal, { type AdminProductFull } from "./ProductModal";
import { formatARS } from "../dashboard/format";

interface ConfirmState {
  message: string;
  resolve: (ok: boolean) => void;
}

/** Modal abierto: crear (sin producto) o editar (prefill desde la fila). */
type ProductModalState = { mode: "create" } | { mode: "edit"; product: AdminProductFull };

/**
 * El catálogo es en español: el buscador tiene que encontrar "César" cuando
 * se escribe "cesar". NFD separa el diacrítico en un combining mark y el
 * rango U+0300–U+036F lo elimina; lowercase para el caso.
 */
function normalize(text: string): string {
  return text
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

/**
 * Lista de productos del panel admin contra los endpoints owner (cdaa4d0):
 * GET /api/products devuelve TODOS los productos del negocio del JWT (activo
 * e inactivo, sin parámetro businessId), el toggle manda el estado DESTINO
 * (activo=!p.active, corrigiendo el bug del legacy admin.js:139) y el delete
 * pide confirmación con el mismo ConfirmModal de CategoriesTable. Un 409 del
 * delete (producto usado por un combo) muestra el mensaje del backend en un
 * toast en vez de fallar en silencio.
 *
 * Unidad 2 del ODD (admin-panel-ui): buscador + crear + editar. El buscador
 * filtra EN CLIENTE sobre la lista ya cargada — la API no tiene parámetro de
 * búsqueda, así que un catálogo grande va a necesitar búsqueda en servidor
 * (GET /api/products?search=...) en vez de seguir cargando todo y filtrando
 * acá. Crear y editar comparten ProductModal (mismo patrón que
 * CreateCategoryModal) y recargan la lista al guardar con éxito; si el
 * backend responde 400, el modal queda abierto con su mensaje en español.
 */
export default function ProductsTable() {
  const [products, setProducts] = useState<AdminProductFull[] | null>(null);
  const [query, setQuery] = useState("");
  const [modal, setModal] = useState<ProductModalState | null>(null);
  const [confirmState, setConfirmState] = useState<ConfirmState | null>(null);
  const confirmResolveRef = useRef<((ok: boolean) => void) | null>(null);

  async function loadProducts() {
    try {
      const res = await fetch(`${API}/products`, { headers: authHeader() });
      if (!res.ok) throw new Error("Error cargando productos");
      const data = (await res.json()) as AdminProductFull[];
      setProducts(data);
    } catch (e) {
      console.error("Error cargando productos", e);
    }
  }

  useEffect(() => {
    void loadProducts();
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

  async function toggleProduct(p: AdminProduct) {
    const activo = !p.active;
    try {
      const res = await fetch(`${API}/products/${p.id}/activar?activo=${activo}`, {
        method: "PUT",
        headers: authHeader(),
      });
      if (!res.ok) throw new Error("Error actualizando el producto");
      void loadProducts();
    } catch (e) {
      showSuccessToast(`Error: ${(e as Error).message}`);
    }
  }

  async function deleteProduct(p: AdminProduct) {
    const ok = await confirmAsync(`¿Seguro que deseas eliminar el producto "${p.name}"?`);
    if (!ok) return;

    try {
      const res = await fetch(`${API}/products/${p.id}`, {
        method: "DELETE",
        headers: authHeader(),
      });

      if (res.status === 409) {
        // El producto pertenece a un combo: el backend manda un mensaje legible.
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        showSuccessToast(`Error: ${data?.message || "El producto está siendo usado por un combo"}`);
        return;
      }
      if (!res.ok) throw new Error("Error eliminando el producto");

      void loadProducts();
    } catch (e) {
      showSuccessToast(`Error: ${(e as Error).message}`);
    }
  }

  if (products === null) return null;

  // Filtro en cliente sobre la lista ya cargada (accent- y case-insensitive).
  const needle = normalize(query.trim());
  const filtered = needle === "" ? products : products.filter((p) => normalize(p.name).includes(needle));

  return (
    <>
      <div className="glass-panel fade-up admin-table-panel">
        <div className="products-toolbar">
          <input
            type="search"
            className="admin-input products-toolbar-search"
            placeholder="Buscar productos…"
            aria-label="Buscar productos por nombre"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <button type="button" className="btn-primary" onClick={() => setModal({ mode: "create" })}>
            + Nuevo producto
          </button>
        </div>

        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Nombre</th>
                <th>Precio</th>
                <th>Stock</th>
                <th>Estado</th>
                <th>Acciones</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 && (
                <tr>
                  <td colSpan={5} className="admin-empty-state">
                    {products.length === 0
                      ? 'Todavía no hay productos. Creá el primero con "+ Nuevo producto".'
                      : "No hay productos que coincidan con la búsqueda"}
                  </td>
                </tr>
              )}
              {filtered.map((p) => (
                <tr key={p.id}>
                  <td>{p.name}</td>
                  <td>{formatARS(p.price)}</td>
                  <td>{p.stock ?? "∞"}</td>
                  <td>
                    <span className={`badge ${p.active ? "active" : "inactive"}`}>
                      {p.active ? "Activo" : "Inactivo"}
                    </span>
                  </td>
                  <td className="row-actions">
                    <button
                      type="button"
                      className="btn-ghost"
                      onClick={() => setModal({ mode: "edit", product: p })}
                      aria-label={`Editar ${p.name}`}
                    >
                      ✏️
                    </button>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={p.active}
                      aria-label={`${p.active ? "Desactivar" : "Activar"} ${p.name}`}
                      className={`admin-switch${p.active ? " is-on" : ""}`}
                      onClick={() => void toggleProduct(p)}
                    >
                      <span className="admin-switch-knob" aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="btn-ghost"
                      onClick={() => void deleteProduct(p)}
                      aria-label={`Eliminar ${p.name}`}
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
        <ProductModal
          product={modal.mode === "edit" ? modal.product : null}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            void loadProducts();
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
