import { useEffect, useMemo, useState } from "react";
import { formatARS } from "../../format";
import {
  createCounterOrder,
  loadPosCatalog,
  loadTables,
  type PosCombo,
  type PosProduct,
  type PosTable,
} from "./api";
import {
  buildOrderRequest,
  validatePosOrder,
  type PosCartItem,
  type PosOrderForm,
  type PosOrderType,
} from "./pos";

interface PosPanelProps {
  onClose: () => void;
  /** Pedido creado con éxito: el padre cierra, toastea y refresca la vista. */
  onCreated: () => void;
}

/**
 * Counter POS (T4 / S2): "es lo que hace el flujo del cliente pero operado
 * por vos". Catálogo con búsqueda de texto (productos + combos activos),
 * carrito con cantidades y total, tipo de pedido (SALON/RETIRO/DELIVERY)
 * con picker de mesa para SALON (que viaja anónimo: la mesa identifica al
 * comensal), datos del cliente (nombre obligatorio salvo SALON),
 * forma de pago y notas. Crea vía POST /api/orders/menu/{slug} (endpoint
 * existente) y los errores de validación del backend se muestran en español.
 */
export default function PosPanel({ onClose, onCreated }: PosPanelProps) {
  const [products, setProducts] = useState<PosProduct[] | null>(null);
  const [combos, setCombos] = useState<PosCombo[] | null>(null);
  const [tables, setTables] = useState<PosTable[]>([]);
  const [query, setQuery] = useState("");
  const [cart, setCart] = useState<PosCartItem[]>([]);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState<PosOrderForm>({
    orderType: "RETIRO",
    tableId: null,
    customerName: "",
    customerPhone: "",
    customerAddress: "",
    paymentMethod: "EFECTIVO",
    notes: "",
  });

  useEffect(() => {
    loadPosCatalog()
      .then(({ products: p, combos: c }) => {
        setProducts(p);
        setCombos(c);
      })
      .catch((e) => {
        console.error("Error cargando el catálogo", e);
        setError("No se pudo cargar el catálogo");
      });
    loadTables()
      .then(setTables)
      .catch((e) => console.error("Error cargando mesas", e));
  }, []);

  // Búsqueda de texto en cliente sobre el catálogo ya cargado: sin
  // diacríticos y sin distinguir mayúsculas, igual que el buscador del admin.
  const results = useMemo(() => {
    const q = query
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .toLowerCase()
      .trim();
    const all: PosCartItem[] = [
      ...(products ?? []).map((p) => ({
        id: String(p.id),
        name: p.name,
        price: p.price,
        isCombo: false,
        qty: 0,
      })),
      ...(combos ?? []).map((c) => ({
        id: `combo-${c.id}`,
        name: c.name,
        price: c.price,
        isCombo: true,
        qty: 0,
      })),
    ];
    if (q === "") return all;
    return all.filter((item) =>
      item.name
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .toLowerCase()
        .includes(q)
    );
  }, [products, combos, query]);

  function addToCart(item: Omit<PosCartItem, "qty">): void {
    setCart((prev) => {
      const existing = prev.find((line) => line.id === item.id);
      if (existing) {
        return prev.map((line) =>
          line.id === item.id ? { ...line, qty: line.qty + 1 } : line
        );
      }
      return [...prev, { ...item, qty: 1 }];
    });
  }

  function changeQty(id: string, delta: number): void {
    setCart((prev) =>
      prev
        .map((line) => (line.id === id ? { ...line, qty: line.qty + delta } : line))
        .filter((line) => line.qty > 0)
    );
  }

  const total = cart.reduce((sum, line) => sum + line.qty * line.price, 0);

  async function submit(): Promise<void> {
    if (sending) return;

    const validationError = validatePosOrder(form, cart);
    if (validationError !== null) {
      setError(validationError);
      return;
    }

    try {
      setSending(true);
      setError(null);
      const result = await createCounterOrder(buildOrderRequest(form, cart));
      if (!result.ok) {
        // El mensaje del backend llega en español: se muestra tal cual.
        setError(result.message);
        return;
      }
      onCreated();
    } finally {
      setSending(false);
    }
  }

  return (
    <div
      className="orders-modal orders-modal--pos"
      role="dialog"
      aria-modal="true"
      aria-labelledby="pos-title"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="orders-modal-content glass-panel orders-pos">
        <header className="orders-modal-head">
          <h3 id="pos-title">Nuevo pedido</h3>
          <button
            type="button"
            className="orders-modal-close"
            onClick={onClose}
            aria-label="Cerrar nuevo pedido"
          >
            ✕
          </button>
        </header>

        <div className="orders-pos-body">
          <section className="orders-pos-catalog" aria-label="Catálogo">
            <input
              type="text"
              className="orders-pos-search"
              placeholder="Buscar producto o combo…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <ul className="orders-pos-results">
              {results.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    className="orders-pos-result"
                    onClick={() =>
                      addToCart({ id: item.id, name: item.name, price: item.price, isCombo: item.isCombo })
                    }
                  >
                    <span>{item.name}</span>
                    {item.isCombo && <span className="orders-pos-tag">Combo</span>}
                    <span className="orders-pos-price">{formatARS(item.price)}</span>
                  </button>
                </li>
              ))}
              {results.length === 0 && (
                <li className="orders-pos-empty">Sin resultados para “{query}”</li>
              )}
            </ul>
          </section>

          <section className="orders-pos-cart" aria-label="Pedido en curso">
            <ul className="orders-pos-lines">
              {cart.map((line) => (
                <li key={line.id} className="orders-pos-line">
                  <span className="orders-pos-line-name">{line.name}</span>
                  <div className="orders-pos-stepper">
                    <button
                      type="button"
                      onClick={() => changeQty(line.id, -1)}
                      aria-label={`Restar ${line.name}`}
                    >
                      −
                    </button>
                    <span aria-live="polite">{line.qty}</span>
                    <button
                      type="button"
                      onClick={() => changeQty(line.id, +1)}
                      aria-label={`Sumar ${line.name}`}
                    >
                      +
                    </button>
                  </div>
                  <span className="orders-pos-price">{formatARS(line.qty * line.price)}</span>
                </li>
              ))}
              {cart.length === 0 && <li className="orders-pos-empty">Carrito vacío</li>}
            </ul>

            <p className="orders-pos-total">Total: {formatARS(total)}</p>

            <div className="orders-pos-form">
              <select
                aria-label="Tipo de pedido"
                value={form.orderType}
                onChange={(e) =>
                  setForm((f) => ({
                    ...f,
                    orderType: e.target.value as PosOrderType,
                    // Cambiar de tipo limpia la mesa elegida: RETIRO/DELIVERY
                    // nunca viajan con tableId (contrato T2).
                    tableId: null,
                    customerAddress:
                      e.target.value === "DELIVERY" ? f.customerAddress : "",
                  }))
                }
              >
                <option value="RETIRO">Retiro en el local</option>
                <option value="SALON">Salón</option>
                <option value="DELIVERY">Delivery</option>
              </select>

              {form.orderType === "SALON" && (
                <select
                  aria-label="Mesa"
                  value={form.tableId ?? ""}
                  onChange={(e) =>
                    setForm((f) => ({
                      ...f,
                      tableId: e.target.value === "" ? null : Number(e.target.value),
                    }))
                  }
                >
                  <option value="">Seleccionar mesa</option>
                  {tables.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </select>
              )}

              {/* SALON no pide datos del cliente: la mesa identifica al comensal. */}
              {form.orderType !== "SALON" && (
                <>
                  <input
                    type="text"
                    placeholder="Nombre y apellido (obligatorio)"
                    value={form.customerName}
                    onChange={(e) => setForm((f) => ({ ...f, customerName: e.target.value }))}
                  />
                  <input
                    type="tel"
                    inputMode="tel"
                    placeholder="Celular (opcional)"
                    value={form.customerPhone}
                    onChange={(e) => setForm((f) => ({ ...f, customerPhone: e.target.value }))}
                  />
                </>
              )}
              {form.orderType === "DELIVERY" && (
                <input
                  type="text"
                  placeholder="Dirección (solo para delivery)"
                  value={form.customerAddress}
                  onChange={(e) => setForm((f) => ({ ...f, customerAddress: e.target.value }))}
                />
              )}

              <div className="orders-pos-payments" role="group" aria-label="Forma de pago">
                <button
                  type="button"
                  aria-pressed={form.paymentMethod === "EFECTIVO"}
                  onClick={() => setForm((f) => ({ ...f, paymentMethod: "EFECTIVO" }))}
                >
                  Efectivo
                </button>
                <button
                  type="button"
                  aria-pressed={form.paymentMethod === "TRANSFERENCIA"}
                  onClick={() => setForm((f) => ({ ...f, paymentMethod: "TRANSFERENCIA" }))}
                >
                  Transferencia
                </button>
              </div>

              <textarea
                placeholder="Observaciones (ej: sin cebolla)"
                value={form.notes}
                onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value }))}
              />
            </div>

            {error && (
              <p className="orders-pos-error" role="alert">
                {error}
              </p>
            )}

            <button
              type="button"
              className="orders-pos-submit"
              disabled={sending}
              onClick={submit}
            >
              {sending ? "Enviando…" : "Crear pedido"}
            </button>
          </section>
        </div>
      </div>
    </div>
  );
}
