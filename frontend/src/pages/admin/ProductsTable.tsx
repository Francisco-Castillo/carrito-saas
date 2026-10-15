import { useEffect, useRef, useState } from "react";
import { API, authHeader, type AdminProduct } from "./api";
import { showSuccessToast } from "./toast";
import ConfirmModal from "./ConfirmModal";

interface ConfirmState {
  message: string;
  resolve: (ok: boolean) => void;
}

/**
 * Lista de productos del panel admin contra los endpoints owner (cdaa4d0):
 * GET /api/products devuelve TODOS los productos del negocio del JWT (activo
 * e inactivo, sin parámetro businessId), el toggle manda el estado DESTINO
 * (activo=!p.active, corrigiendo el bug del legacy admin.js:139) y el delete
 * pide confirmación con el mismo ConfirmModal de CategoriesTable. Un 409 del
 * delete (producto usado por un combo) muestra el mensaje del backend en un
 * toast en vez de fallar en silencio.
 */
export default function ProductsTable() {
  const [products, setProducts] = useState<AdminProduct[] | null>(null);
  const [confirmState, setConfirmState] = useState<ConfirmState | null>(null);
  const confirmResolveRef = useRef<((ok: boolean) => void) | null>(null);

  async function loadProducts() {
    try {
      const res = await fetch(`${API}/products`, { headers: authHeader() });
      if (!res.ok) throw new Error("Error cargando productos");
      const data = (await res.json()) as AdminProduct[];
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

  return (
    <>
      <div className="glass-panel fade-up admin-table-panel">
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Nombre</th>
                <th>Precio</th>
                <th>Stock</th>
                <th>Estado</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {products.map((p) => (
                <tr key={p.id}>
                  <td>{p.name}</td>
                  <td>${p.price}</td>
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
                      onClick={() => void toggleProduct(p)}
                      aria-label={`Activar o desactivar ${p.name}`}
                    >
                      ON/OFF
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
