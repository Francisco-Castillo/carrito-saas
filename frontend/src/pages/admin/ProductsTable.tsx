import { useEffect, useState } from "react";
import { API, BUSINESS_ID, type AdminProduct } from "./api";

/**
 * Puerto 1:1 de loadProducts/toggleProduct/deleteProduct del legacy
 * admin/admin.js: GET /api/products?businessId=1 (sin header de auth, como el
 * legacy), toggle que manda SIEMPRE activo=false (bug del legacy conservado,
 * admin.js:139) y delete sin confirmación (el legacy no confirma, admin.js:144).
 */
export default function ProductsTable() {
  const [products, setProducts] = useState<AdminProduct[] | null>(null);

  async function loadProducts() {
    const res = await fetch(`${API}/products?businessId=${BUSINESS_ID}`);
    const data = (await res.json()) as AdminProduct[];
    setProducts(data);
  }

  useEffect(() => {
    void loadProducts();
  }, []);

  async function toggleProduct(id: number) {
    await fetch(`${API}/products/${id}/activar?activo=false`, { method: "PUT" });
    void loadProducts();
  }

  async function deleteProduct(id: number) {
    await fetch(`${API}/products/${id}`, { method: "DELETE" });
    void loadProducts();
  }

  if (products === null) return null;

  return (
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
                    onClick={() => void toggleProduct(p.id)}
                    aria-label={`Activar o desactivar ${p.name}`}
                  >
                    ON/OFF
                  </button>
                  <button
                    type="button"
                    className="btn-ghost"
                    onClick={() => void deleteProduct(p.id)}
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
  );
}
