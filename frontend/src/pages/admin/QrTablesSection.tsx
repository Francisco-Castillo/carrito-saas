import { useEffect, useMemo, useState } from "react";
import { API, authHeader } from "./api";
import LoadingPanel from "./LoadingPanel";
import { useConfirmDialog } from "./useConfirmDialog";
import ConfirmModal from "./ConfirmModal";
import { showSuccessToast } from "./toast";
import { getSessionRestaurantSlug } from "../dashboard/auth";

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
 *   - MESAS: GET /api/tables en una sola página grande lista cada mesa con
 *     el destino de su QR ({slug}?table={id}), si tiene pedido activo y sus
 *     descargas: PNG (GET /api/tables/{id}/qr) y PDF con la plantilla
 *     elegida (GET /api/tables/{id}/qr/pdf?template=...). "Descargar QR de
 *     todas las mesas" pide GET /api/tables/qr/pdf con la misma plantilla.
 *   - ALTA POR LOTE: POST /api/tables/bulk con { quantity } = cuántas mesas
 *     NUEVAS se agregan. El backend las numera con el NÚMERO LIBRE más bajo
 *     (libre = no usado por una mesa viva): si falta la Mesa 2, la próxima
 *     es la 2; si no hay huecos, sigue a continuación de la más alta. La UI
 *     calcula exactamente eso — "Cantidad de mesas nuevas" + los números que
 *     va a crear — para no prometer un número que el backend no va a crear.
 *   - BAJA: DELETE /api/tables/{id} SIEMPRE detrás del ConfirmModal común
 *     del panel; un fallo se informa inline y la lista se refresca igual.
 *     La baja es LÓGICA: la fila sobrevive con status "DISABLED" y GET
 *     /api/tables la sigue devolviendo, pero su QR público ya no funciona
 *     (404 en el endpoint de mesa del menú). La UI la muestra aparte, como
 *     "Eliminada", sin URL de destino ni acciones, y no la cuenta para el
 *     total de mesas ni para la numeración del alta.
 *   - Las descargas binarias comparten un helper: fetch con Bearer → blob →
 *     <a download> sintético → revoke del object URL.
 *
 * La pestaña tiene que EXPLICARSE (odd/tasks/qr-mesas-ux.md, S1-S5): qué
 * diferencia hay entre el QR público y el de cada mesa, qué imprime cada
 * plantilla, a qué URL apunta cada QR y qué mesas va a crear el alta antes
 * de crearlas.
 */

/**
 * Plantillas de PDF que acepta el backend (enum QrTemplate), con la
 * explicación de qué sale impreso. Las cuatro tienen su estrategia en el
 * backend: GRID_4X4 la tenía faltante (500 en PdfTemplateFactory) hasta que
 * se agregó Grid4Template — ver Grid4TemplateTests.
 */
const TEMPLATES = [
  {
    value: "SINGLE",
    label: "Individual (1 por hoja)",
    help: "Un QR por hoja A4, grande y con el nombre de la mesa: para imprimir y pegar en la mesa.",
  },
  {
    value: "GRID_3X3",
    label: "Grilla 3×3 (9 por hoja)",
    help: "Nueve QRs en una sola hoja A4: para recortar y repartir entre las mesas.",
  },
  {
    value: "GRID_4X4",
    label: "Grilla 4×4 (16 por hoja)",
    help: "Dieciséis QRs en una sola hoja A4: el máximo por hoja, para pegar muchos códigos de una sola impresión.",
  },
  {
    value: "ACRYLIC",
    label: "Acrílico (tarjeta grande)",
    help: "Una tarjeta grande por hoja, con el nombre bien visible: para pie de acrílico o portarretrato.",
  },
] as const;

/** Límite de mesas por bulk que valida el backend. */
const MAX_BULK = 300;

/**
 * Tamaño de la página de mesas: el máximo del bulk, para que el total que
 * muestra la pantalla (y la numeración que promete el alta) sea el real y no
 * el de las primeras 100.
 */
const TABLES_PAGE_SIZE = MAX_BULK;

interface AdminTable {
  id: number;
  tableNumber: number;
  tableName: string;
  qrToken?: string;
  qrUrl?: string;
  hasActiveOrder?: boolean | null;
  activeOrderId?: number | null;
  /** Baja lógica: "DISABLED" = eliminada (su QR ya no funciona en público). */
  status?: string | null;
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

/**
 * Qué mesas va a crear el alta por lote, en una frase: es la única forma de
 * que "cantidad" (que en el backend es el total) no sorprenda al owner.
 */
function describeCreated(numbers: number[]): string {
  const count = numbers.length;
  if (count === 0) return "No se va a crear ninguna mesa.";
  const first = numbers[0];
  const last = numbers[count - 1];

  if (count === 1) return `Se va a crear: Mesa ${first}.`;
  // Un rango sólo cuando los números son todos consecutivos y cortos.
  if (last - first + 1 === count && count <= 6) {
    return `Se van a crear: Mesa ${first} a Mesa ${last}.`;
  }
  const head = numbers.slice(0, 3).map((n) => `Mesa ${n}`).join(", ");
  return `Se van a crear ${count} mesas: ${head}… hasta la Mesa ${last}.`;
}

/** Baja lógica: la mesa sigue llegando en GET /api/tables pero ya no existe. */
function isDeletedTable(table: AdminTable): boolean {
  return table.status === "DISABLED";
}

/** Confirmación del alta, con los nombres que devolvió el backend. */
function createdMessage(names: string[]): string {
  const count = names.length;
  if (count === 0) return "Mesas creadas";
  if (count === 1) return `Se creó 1 mesa: ${names[0]}`;
  if (count <= 4) {
    const list = `${names.slice(0, -1).join(", ")} y ${names[count - 1]}`;
    return `Se crearon ${count} mesas: ${list}`;
  }
  return `Se crearon ${count} mesas: ${names.slice(0, 3).join(", ")} y ${count - 3} más`;
}

export default function QrTablesSection() {
  const [publicQrUrl, setPublicQrUrl] = useState<string | null>(null);
  const [tables, setTables] = useState<AdminTable[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [template, setTemplate] = useState<string>("SINGLE");
  // null = todavía no lo tocó: el campo arranca sugiriendo la próxima mesa.
  const [bulkQuantity, setBulkQuantity] = useState<string | null>(null);
  const [previewId, setPreviewId] = useState<number | null>(null);
  const [previewUrls, setPreviewUrls] = useState<Record<number, string>>({});
  const { confirmState, confirmAsync, settleConfirm } = useConfirmDialog();

  const slug = getSessionRestaurantSlug();

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [qrRes, tablesRes] = await Promise.all([
          fetch(`${API}/business/qr`, { headers: authHeader() }),
          fetch(`${API}/tables?page=0&size=${TABLES_PAGE_SIZE}`, { headers: authHeader() }),
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
    const res = await fetch(`${API}/tables?page=0&size=${TABLES_PAGE_SIZE}`, { headers: authHeader() });
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

  /** Vista previa del QR de una mesa: se pide una vez y queda cacheada. */
  async function togglePreview(table: AdminTable) {
    if (previewId === table.id) {
      setPreviewId(null);
      return;
    }
    setPreviewId(table.id);
    if (previewUrls[table.id]) return;
    try {
      const res = await fetch(`${API}/tables/${table.id}/qr`, { headers: authHeader() });
      if (!res.ok) throw new Error(`No se pudo cargar el QR de ${table.tableName}`);
      const blob = await res.blob();
      setPreviewUrls((prev) => ({ ...prev, [table.id]: URL.createObjectURL(blob) }));
    } catch (e) {
      setPreviewId(null);
      setError((e as Error).message);
    }
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
      const created = (await res.json().catch(() => [])) as Array<{ tableName?: string }>;
      await refreshTables();
      setBulkQuantity(null);
      // El backend devuelve las mesas creadas: se nombran para que el owner
      // sepa exactamente qué mesas nuevas tiene (y no las deduzca).
      const names = Array.isArray(created)
        ? created.map((t) => t.tableName).filter((n): n is string => Boolean(n))
        : [];
      showSuccessToast(createdMessage(names));
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

  // Baja lógica: las eliminadas se listan al final, apagadas y sin acciones.
  const liveTables = useMemo(() => tables.filter((t) => !isDeletedTable(t)), [tables]);
  const deletedTables = useMemo(() => tables.filter((t) => isDeletedTable(t)), [tables]);
  // Numeración real: sólo cuentan las mesas vivas (una eliminada libera su número).
  const liveNumbers = useMemo(() => new Set(liveTables.map((t) => t.tableNumber)), [liveTables]);
  const maxTableNumber = liveTables.reduce((max, t) => Math.max(max, t.tableNumber), 0);
  // Cuántas mesas NUEVAS se agregan (el backend numera con el número libre más bajo).
  const quantityValue = bulkQuantity ?? "1";
  const requestedNew = Number(quantityValue);

  /** Números exactos que va a crear el backend para la cantidad pedida: el
   *  número libre más bajo primero (una mesa eliminada libera el suyo) y, si
   *  no hay huecos, a continuación de la mesa viva más alta. */
  const plannedNumbers = useMemo(() => {
    if (!Number.isInteger(requestedNew) || requestedNew < 1 || requestedNew > MAX_BULK) return [];
    const numbers: number[] = [];
    let candidate = 1;
    while (numbers.length < requestedNew) {
      if (!liveNumbers.has(candidate)) numbers.push(candidate);
      candidate += 1;
    }
    return numbers;
  }, [requestedNew, liveNumbers]);

  const canGenerate = plannedNumbers.length > 0 && !saving;

  const activeTemplate = TEMPLATES.find((t) => t.value === template) ?? TEMPLATES[0];

  // Primera carga de mesas/QR: skeleton en vez de la pestaña invisible.
  if (loading) return <LoadingPanel label="Cargando mesas y QR…" lines={5} />;

  return (
    <div className="glass-panel fade-up admin-table-panel admin-qr-panel">
      {error && (
        <p className="admin-profile-error" role="alert">
          {error}
        </p>
      )}

      <section className="admin-qr-intro" aria-label="Cómo funcionan los QR">
        <p>
          <strong>Cada mesa tiene su propio QR.</strong> Cuando el cliente lo escanea, la carta se
          abre con esa mesa identificada y el pedido llega con su número de mesa.
        </p>
        <p>
          El <strong>QR del menú público</strong> no identifica mesa: es el que va en el mostrador,
          en la puerta o para compartir por WhatsApp y redes.
        </p>
      </section>

      <section className="admin-qr-card" aria-label="QR del menú público">
        <h3>QR del menú público</h3>
        <p className="admin-qr-hint">
          Abre la carta sin mesa asignada: sirve para el mostrador, la puerta o para compartirlo por
          redes.
        </p>
        <div className="admin-qr-public-row">
          {publicQrUrl && <img className="admin-qr-image" src={publicQrUrl} alt="QR del menú público" />}
          <div className="admin-qr-public-actions">
            <button type="button" className="btn-ghost" onClick={() => void downloadPublicQr()}>
              Descargar QR del menú
            </button>
            <p className="admin-qr-hint">PNG, listo para imprimir o compartir.</p>
            {slug && (
              <a className="admin-qr-link" href={`/${slug}`} target="_blank" rel="noreferrer">
                Ver la carta
              </a>
            )}
          </div>
        </div>
      </section>

      <section className="admin-qr-card admin-qr-tables" aria-label="Mesas">
        <h3>Mesas</h3>
        {tables.length > 0 ? (
          <p className="admin-qr-hint">
            Tenés {liveTables.length} {liveTables.length === 1 ? "mesa" : "mesas"}
            {liveTables.length > 0 && `, numeradas hasta la Mesa ${maxTableNumber}`}
            {deletedTables.length > 0 &&
              ` · ${deletedTables.length} eliminada${deletedTables.length === 1 ? "" : "s"}`}
            .{liveTables.length > 0 && " Cada fila muestra a dónde lleva el QR de esa mesa."}
          </p>
        ) : (
          <p className="admin-qr-hint">
            Todavía no hay mesas: creá las primeras acá abajo y cada una se lleva su QR.
          </p>
        )}

        {tables.length > 0 && (
          <ul className="admin-qr-list">
            {liveTables.map((table) => {
              const busy = Boolean(table.hasActiveOrder);
              const showPreview = previewId === table.id;
              return (
                <li className="admin-qr-row" key={table.id}>
                  <div className="admin-qr-row-main">
                    <span className="admin-qr-row-head">
                      <span className="admin-qr-table-name">{table.tableName}</span>
                      <span className={`admin-qr-status admin-qr-status--${busy ? "busy" : "free"}`}>
                        {busy ? "Con pedido activo" : "Libre"}
                      </span>
                    </span>
                    {table.qrUrl && <span className="admin-qr-dest">{table.qrUrl}</span>}
                  </div>
                  <div className="admin-qr-actions">
                    <button
                      type="button"
                      className="btn-ghost"
                      aria-label={`Ver QR de ${table.tableName}`}
                      aria-expanded={showPreview}
                      onClick={() => void togglePreview(table)}
                    >
                      {showPreview ? "Ocultar QR" : "Ver QR"}
                    </button>
                    <button
                      type="button"
                      className="btn-ghost"
                      aria-label={`Descargar QR PNG ${table.tableName}`}
                      onClick={() => void downloadTableQr(table)}
                    >
                      PNG
                    </button>
                    <button
                      type="button"
                      className="btn-ghost"
                      aria-label={`Descargar QR PDF ${table.tableName}`}
                      onClick={() => void downloadTablePdf(table)}
                    >
                      PDF
                    </button>
                    <button
                      type="button"
                      className="btn-ghost admin-qr-remove"
                      aria-label={`Eliminar ${table.tableName}`}
                      onClick={() => void removeTable(table)}
                    >
                      Eliminar
                    </button>
                  </div>
                  {showPreview && (
                    <div className="admin-qr-row-preview">
                      {previewUrls[table.id] ? (
                        <img
                          className="admin-qr-image admin-qr-image--preview"
                          src={previewUrls[table.id]}
                          alt={`Vista previa del QR de ${table.tableName}`}
                        />
                      ) : (
                        <p className="admin-qr-hint">Generando vista previa…</p>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
            {/* Baja lógica: la fila sigue llegando del backend pero su QR ya
                no funciona, así que se muestra aparte, sin URL ni acciones. */}
            {deletedTables.map((table) => (
              <li className="admin-qr-row admin-qr-row--deleted" key={table.id}>
                <div className="admin-qr-row-main">
                  <span className="admin-qr-row-head">
                    <span className="admin-qr-table-name">{table.tableName}</span>
                    <span className="admin-qr-status admin-qr-status--deleted">Eliminada</span>
                  </span>
                  <span className="admin-qr-hint">Su QR ya no funciona.</span>
                </div>
              </li>
            ))}
          </ul>
        )}

        <div className="admin-qr-bulk">
          <h4 className="admin-qr-subtitle">Agregar mesas</h4>
          <p className="admin-qr-hint">
            {liveTables.length === 0 ? (
              <>Todavía no hay mesas: las nuevas arrancan en la Mesa 1.</>
            ) : (
              <>Cada mesa nueva toma el primer número libre: si falta la Mesa 2, la próxima es la 2.</>
            )}
          </p>
          <div className="admin-qr-bulk-row">
            <label htmlFor="qr-bulk-quantity">Cantidad de mesas nuevas</label>
            <input
              id="qr-bulk-quantity"
              type="number"
              min={1}
              max={MAX_BULK}
              className="admin-input"
              value={quantityValue}
              onChange={(e) => setBulkQuantity(e.target.value)}
            />
            <button
              type="button"
              className="btn-primary"
              disabled={!canGenerate}
              onClick={() => void generateBulk()}
            >
              {saving ? "Generando…" : "Generar mesas"}
            </button>
          </div>
          <p className="admin-qr-hint admin-qr-plan" aria-live="polite">
            {plannedNumbers.length > 0 ? (
              <>
                {describeCreated(plannedNumbers)} Vas a quedar con{" "}
                {liveTables.length + plannedNumbers.length}{" "}
                {liveTables.length + plannedNumbers.length === 1 ? "mesa" : "mesas"}.
              </>
            ) : (
              <>Elegí cuántas mesas nuevas querés agregar: entre 1 y {MAX_BULK}.</>
            )}
          </p>
        </div>

        {liveTables.length > 0 && (
          <div className="admin-qr-print">
            <h4 className="admin-qr-subtitle">Imprimir los QR</h4>
            <p className="admin-qr-hint">
              La plantilla elegida se usa tanto en el PDF de una mesa como en el PDF con todas.
            </p>
            <div className="admin-qr-print-controls">
              <div className="admin-qr-print-control">
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
                <p className="admin-qr-hint">{activeTemplate.help}</p>
              </div>
              <div className="admin-qr-print-control">
                <span className="admin-qr-hint">Todos los QR de una vez</span>
                <button type="button" className="btn-ghost" onClick={() => void downloadAllPdf()}>
                  Descargar QR de todas las mesas
                </button>
              </div>
            </div>
          </div>
        )}
      </section>

      {confirmState && (
        <ConfirmModal message={confirmState.message} onConfirm={() => settleConfirm(true)} onCancel={() => settleConfirm(false)} />
      )}
    </div>
  );
}
