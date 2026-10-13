import { useEffect, useState } from "react";
import { API, type AdminCombo } from "./api";

/**
 * Puerto 1:1 de loadCombos del legacy admin/admin.js: GET /api/combos (sin
 * header de auth, como el legacy) y tabla de solo lectura Nombre/Precio.
 */
export default function CombosTable() {
  const [combos, setCombos] = useState<AdminCombo[] | null>(null);

  async function loadCombos() {
    const res = await fetch(`${API}/combos`);
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
