import { useEffect, useState } from "react";
import { API, authHeader, type AdminCombo } from "./api";

/**
 * Puerto del loadCombos del legacy admin/admin.js: tabla de solo lectura
 * Nombre/Precio. GET /api/combos ahora pide el Bearer token (endpoint owner
 * de cdaa4d0, scoped por el JWT del negocio).
 */
export default function CombosTable() {
  const [combos, setCombos] = useState<AdminCombo[] | null>(null);

  async function loadCombos() {
    const res = await fetch(`${API}/combos`, { headers: authHeader() });
    const data = (await res.json()) as AdminCombo[];
    setCombos(data);
  }

  useEffect(() => {
    void loadCombos();
  }, []);

  if (combos === null) return null;

  return (
    <div className="glass-panel fade-up admin-table-panel">
      <div className="table-scroll">
        <table className="table">
          <thead>
            <tr>
              <th>Nombre</th>
              <th>Precio</th>
            </tr>
          </thead>
          <tbody>
            {combos.map((c) => (
              <tr key={c.id}>
                <td>{c.name}</td>
                <td>${c.price}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
