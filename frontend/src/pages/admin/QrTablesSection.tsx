import { useEffect, useState } from "react";
import { API, authHeader } from "./api";
import { useConfirmDialog } from "./useConfirmDialog";
import ConfirmModal from "./ConfirmModal";

/**
 * Pestaña "QR y mesas" de la vista Local del panel admin, contra endpoints
 * owner scoped por el JWT:
 *
 *   - QR del MENÚ PÚBLICO: el PNG lo genera el backend en
 *     GET /api/business/qr (codifica la URL pública del menú configurada
 *     en el servidor; el negocio sale del token, nunca de un parámetro del
 *     cliente). Como los endpoints owner exigen Bearer, la imagen se pide
 *     con fetch y se muestra vía object URL — nunca un <img src> directo,
 *     que no puede mandar el header. "Descargar QR del menú" vuelve a
 *     pedirlo y lo baja como menu-qr.png. Un 503 del generador de QR se
 *     muestra como error inline.
 *   - MESAS: GET /api/tables en una sola página grande (los locales reales
 *     quedan muy lejos del límite de 300 del bulk); cada fila muestra
 *     nombre, número y si tiene pedido activo, y ofrece descargar su QR
 *     en PNG (GET /api/tables/{id}/qr) o en PDF con la plantilla elegida
 *     (GET /api/tables/{id}/qr/pdf?template=...). "Descargar QR de todas
 *     las mesas" pide GET /api/tables/qr/pdf con la misma plantilla.
 *   - ALTA POR LOTE: POST /api/tables/bulk con { quantity } (el backend
 *     valida 1..300 y numera las nuevas a continuación de las existentes);
 *     al terminar se refresca la lista.
 *   - BAJA: DELETE /api/tables/{id} SIEMPRE detrás del ConfirmModal común
 *     del panel; un fallo se informa inline y la lista se refresca igual.
 *   - Las descargas binarias comparten un helper: fetch con Bearer → blob →
 *     <a download> sintético → revoke del object URL.
 */

/** Plantillas de PDF que acepta el backend (enum QrTemplate). */
const TEMPLATES = [
  { value: "SINGLE", label: "Individual" },
  { value: "GRID_3X3", label: "Grilla 3x3" },
  { value: "GRID_4X4", label: "Grilla 4x4" },
  { value: "ACRYLIC", label: "Acrílico" },
] as const;

/** Límite de mesas por bulk que valida el backend. */
const MAX_BULK = 300;

interface AdminTable {
  id: number;
  tableNumber: number;
  tableName: string;
  qrToken?: string;
  qrUrl?: string;
  hasActiveOrder?: boolean | null;
  activeOrderId?: number | null;
}

/** Descarga binaria autenticada: fetch → blob → anchor → revoke. */
async function downloadBinary(url: string, filename: string, onError: (msg: string) => void) {
  try {
    const res = await fetch(url, { headers: authHeader() });
    if (!res.ok) throw new Error("No se pudo descargar el archivo");
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

export default function QrTablesSection() {
  const [publicQrUrl, setPublicQrUrl] = useState<string | null>(null);
  const [tables, setTables] = useState<AdminTable[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [template, setTemplate] = useState<string>("SINGLE");
  const [bulkQuantity, setBulkQuantity] = useState("1");
  const { confirmState, confirmAsync, settleConfirm } = useConfirmDialog();

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [qrRes, tablesRes] = await Promise.all([
          fetch(`${API}/business/qr`, { headers: authHeader() }),
          fetch(`${API}/tables?page=0&size=100`, { headers: authHeader() }),
        ]);
        if (!qrRes.ok) throw new Error("Error cargando el QR del menú público");
        if (!tablesRes.ok) throw new Error("Error cargando las mesas");
        const qrBlob = await qrRes.blob();
        const page = (await tablesRes.json()) as { content?: AdminTable[] };
        if (cancelled) return;
        setPublicQrUrl(URL.createObjectURL(qrBlob));
        setTables(page.content ?? []);
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

  async function refreshTables() {
    const res = await fetch(`${API}/tables?page=0&size=100`, { headers: authHeader() });
    if (!res.ok) throw new Error("Error recargando las mesas");
    const page = (await res.json()) as { content?: AdminTable[] };
    setTables(page.content ?? []);
  }

  async function downloadPublicQr() {
    await downloadBinary(`${API}/business/qr`, "menu-qr.png", setError);
  }

  async function downloadTableQr(table: AdminTable) {
    await downloadBinary(`${API}/tables/${table.id}/qr`, `qr-${table.tableName}.png`, setError);
  }

  async function downloadTablePdf(table: AdminTable) {
    await downloadBinary(
      `${API}/tables/${table.id}/qr/pdf?template=${template}`,
      `qr-${table.tableName}.pdf`,
      setError,
    );
  }

  async function downloadAllPdf() {
    await downloadBinary(`${API}/tables/qr/pdf?template=${template}`, "qr-mesas.pdf", setError);
  }

  async function generateBulk() {
    const quantity = Number(bulkQuantity);
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > MAX_BULK) {
      setError(`La cantidad debe ser un número entre 1 y ${MAX_BULK}`);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(`${API}/tables/bulk`, {
        method: "POST",
        headers: { ...authHeader(), "Content-Type": "application/json" },
        body: JSON.stringify({ quantity }),
      });
      if (!res.ok) {
        const data = (await res.json().catch(() => null)) as { message?: string } | null;
        throw new Error(data?.message || "Error generando las mesas");
      }
      await refreshTables();
      setBulkQuantity("1");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function removeTable(table: AdminTable) {
    const ok = await confirmAsync(`¿Seguro que deseas eliminar ${table.tableName}? Su QR dejará de funcionar.`);
    if (!ok) return;
    setError(null);
    try {
      const res = await fetch(`${API}/tables/${table.id}`, { method: "DELETE", headers: authHeader() });
      if (!res.ok) throw new Error(`No se pudo eliminar ${table.tableName}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      // La lista se refresca también tras un fallo: el backend puede haber
      // borrado y fallado después, y el estado real manda sobre el óptimo.
      try {
        await refreshTables();
      } catch {
        /* el error de recarga ya quedó reportado arriba si correspondía */
      }
    }
  }

  if (loading) return null;

  return (
    <div className="glass-panel fade-up admin-table-panel admin-qr-panel">
      {error && (
        <p className="admin-profile-error" role="alert">
          {error}
        </p>
      )}

      <section className="admin-qr-public" aria-label="QR del menú público">
        <h3>QR del menú público</h3>
        <p className="admin-profile-hours-note">
          Imprimilo este QR para que tus clientes abran la carta desde la mesa o el mostrador.
        </p>
        <div className="admin-qr-public-row">
          {publicQrUrl && <img className="admin-qr-image" src={publicQrUrl} alt="QR del menú público" />}
          <button type="button" className="btn-ghost" onClick={() => void downloadPublicQr()}>
            Descargar QR del menú
          </button>
        </div>
      </section>

      <section className="admin-qr-tables" aria-label="Mesas">
        <h3>Mesas</h3>
        <div className="admin-qr-tables-toolbar">
          <label htmlFor="qr-template">Plantilla de PDF</label>
          <select
            id="qr-template"
            className="admin-input"
            value={template}
            onChange={(e) => setTemplate(e.target.value)}
          >
            {TEMPLATES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
          <button type="button" className="btn-ghost" onClick={() => void downloadAllPdf()}>
            Descargar QR de todas las mesas
          </button>
        </div>

        {tables.length === 0 ? (
          <p className="admin-profile-exceptions-empty">
            Todavía no hay mesas: generá las primeras con el formulario de abajo.
          </p>
        ) : (
          <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Mesa</th>
                <th>Estado</th>
                <th>Acciones</th>
              </tr>
            </thead>
            <tbody>
              {tables.map((table) => (
                <tr key={table.id}>
                  <td>{table.tableName}</td>
                  <td>{table.hasActiveOrder ? "Con pedido activo" : "Libre"}</td>
                  <td className="admin-qr-actions">
                    <button
                      type="button"
                      className="btn-ghost"
                      aria-label={`Descargar QR ${table.tableName}`}
                      onClick={() => void downloadTableQr(table)}
                    >
                      QR PNG
                    </button>
                    <button
                      type="button"
                      className="btn-ghost"
                      aria-label={`Descargar QR PDF ${table.tableName}`}
                      onClick={() => void downloadTablePdf(table)}
                    >
                      QR PDF
                    </button>
                    <button
                      type="button"
                      className="btn-ghost admin-qr-remove"
                      aria-label={`Eliminar ${table.tableName}`}
                      onClick={() => void removeTable(table)}
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

        <div className="admin-qr-tables-toolbar admin-qr-bulk">
          <label htmlFor="qr-bulk-quantity">Cantidad de mesas a generar</label>
          <input
            id="qr-bulk-quantity"
            type="number"
            min={1}
            max={MAX_BULK}
            className="admin-input"
            value={bulkQuantity}
            onChange={(e) => setBulkQuantity(e.target.value)}
          />
          <button type="button" className="btn-primary" disabled={saving} onClick={() => void generateBulk()}>
            {saving ? "Generando…" : "Generar mesas"}
          </button>
        </div>
      </section>

      {confirmState && (
        <ConfirmModal message={confirmState.message} onConfirm={() => settleConfirm(true)} onCancel={() => settleConfirm(false)} />
      )}
    </div>
  );
}
