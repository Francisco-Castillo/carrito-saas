import { useEffect, useLayoutEffect, useMemo, useState } from "react";
import "./Orders.css";
import { enforceSession, getSessionRestaurantSlug, hasPanelSession } from "./dashboard/auth";
import { attachAudioUnlockListeners } from "./kds/audio";
import { showToast } from "./kds/toast";
import KdsToastHost from "./kds/KdsToastHost";
import CancelOrderModal from "./kds/CancelOrderModal";
import OrderListCard from "./orders/OrderListCard";
import OrderDetailModal from "./orders/OrderDetailModal";
import PosPanel from "./orders/PosPanel";
import { useOrdersFeed } from "./orders/useOrdersFeed";
import {
  advanceOrderStatus,
  STATUS_LABELS,
  type ManagementOrder,
  type OrderStatus,
} from "./orders/api";

/**
 * Consola de gestión de pedidos del mostrador (T3 de orders-console, S3+S4):
 * todos los estados del día — entregados y cancelados incluidos, los
 * cancelados con su motivo — con avance de estado respetando las
 * transiciones del backend, cancelación con motivo y detalle completo.
 *
 * Decisión de agrupación (S4): FILTROS por estado sobre una sola lista, no
 * columnas de kanban — son cinco estados con tarjetas ricas (detalle,
 * timeline, motivo), no entran lado a lado, y el filtro "Todos" (por
 * defecto) mantiene la visión completa del día a un vistazo con contadores
 * por estado. El KDS queda como la vista de cocina únicamente.
 *
 * Guard de sesión idéntico al resto de los paneles: hasPanelSession antes
 * del primer paint y enforceSession al login limpio si no hay sesión usable
 * (token + slug de restaurante).
 */

type StatusFilter = "ALL" | OrderStatus;

const FILTERS: StatusFilter[] = ["ALL", "NEW", "PREPARING", "READY", "DELIVERED", "CANCELLED"];

function titleCaseSlug(slug: string): string {
  return slug
    .replace(/-/g, " ")
    .replace(/\b\w/g, (l) => l.toUpperCase());
}

export default function Orders() {
  const [sessionUsable] = useState(hasPanelSession);

  useLayoutEffect(() => {
    if (!sessionUsable) {
      enforceSession();
    }
  }, [sessionUsable]);

  const slug = useMemo(() => getSessionRestaurantSlug(), []);

  // Un browser no permite autoplay antes de un gesto: el primer click/touch/
  // tecla desbloquea el audio para que el ding del pedido nuevo suene siempre.
  useEffect(() => {
    attachAudioUnlockListeners();
  }, []);

  const { orders, live, refresh } = useOrdersFeed(
    slug,
    sessionUsable && Boolean(slug)
  );

  const [filter, setFilter] = useState<StatusFilter>("ALL");

  // Reloj de la barra (mismo ritmo de 1s que el KDS): la píldora "En vivo" es
  // estado de conexión, no reloj — la hora acá era redundante con este.
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const [cancelOrderId, setCancelOrderId] = useState<number | null>(null);
  const [detailOrderId, setDetailOrderId] = useState<number | null>(null);
  const [posOpen, setPosOpen] = useState(false);
  /** Un PATCH en vuelo: deshabilita los botones para evitar dobles clicks. */
  const [busyOrderId, setBusyOrderId] = useState<number | null>(null);

  const counts = useMemo(() => {
    const map = new Map<StatusFilter, number>([["ALL", orders.length]]);
    for (const order of orders) {
      map.set(order.status, (map.get(order.status) ?? 0) + 1);
    }
    return map;
  }, [orders]);

  const visible = useMemo(
    () => (filter === "ALL" ? orders : orders.filter((o) => o.status === filter)),
    [orders, filter]
  );

  async function handleAdvance(orderId: number, status: OrderStatus): Promise<void> {
    setBusyOrderId(orderId);
    try {
      const result = await advanceOrderStatus(orderId, status);
      if (!result.ok) {
        // 409 de transición inválida (u otro fallo): toast con el mensaje del
        // servidor y re-sincronización con el backend.
        showToast(result.message, "warning");
        await refresh();
        return;
      }
      if (status === "READY") showToast("Pedido listo", "success");
      if (status === "DELIVERED") showToast("Pedido entregado", "success");
      if (status === "PREPARING") showToast("Pedido en preparación", "success");
    } finally {
      setBusyOrderId(null);
    }
  }

  if (!sessionUsable) {
    // Redirect en curso: no renderizamos la página sin sesión.
    return null;
  }

  if (!slug) {
    return (
      <div className="orders-screen">
        <div className="orders-fatal glass-panel">
          <h1>Restaurante no especificado</h1>
        </div>
      </div>
    );
  }

  const detailOrder: ManagementOrder | null =
    detailOrderId !== null
      ? orders.find((o) => o.orderId === detailOrderId) ?? null
      : null;

  return (
    <div className="orders-screen">
      <div className="aurora" aria-hidden="true">
        <div className="aurora-blob aurora-blob--indigo" />
        <div className="aurora-blob aurora-blob--violet" />
        <div className="aurora-blob aurora-blob--fuchsia" />
        <div className="aurora-blob aurora-blob--cyan" />
      </div>

      <header className="orders-topbar glass-panel">
        <div className="orders-title">
          <h1>Pedidos</h1>
          <span className="orders-restaurant">{titleCaseSlug(slug)}</span>
        </div>

        <div className="orders-topbar-right">
          {/* Estado de conexión, no reloj: la hora real vive en .orders-clock. */}
          <div
            className={`refresh-indicator${live ? " live" : ""}`}
            title={live ? "Conectado en vivo" : "Reconectando..."}
          >
            <span className="refresh-dot" aria-hidden="true" />
            <span className="refresh-label">
              {live ? "En vivo" : "Reconectando…"}
            </span>
          </div>

          {/* Reloj del KDS (kds.css .clock): hora + fecha en tabular-nums. */}
          <div className="orders-clock">
            <div className="orders-clock-time">{now.toLocaleTimeString()}</div>
            <div className="orders-clock-date">
              {`${String(now.getDate()).padStart(2, "0")}/${String(
                now.getMonth() + 1,
              ).padStart(2, "0")}/${now.getFullYear()}`}
            </div>
          </div>

          <button
            type="button"
            className="orders-new"
            onClick={() => setPosOpen(true)}
          >
            + Nuevo pedido
          </button>
        </div>
      </header>

      <nav
        className="orders-filters"
        aria-label="Filtrar por estado"
        role="group"
      >
        {FILTERS.map((f) => (
          <button
            key={f}
            type="button"
            className={`orders-filter${filter === f ? " is-active" : ""}`}
            aria-pressed={filter === f}
            onClick={() => setFilter(f)}
          >
            {f === "ALL" ? "Todos" : STATUS_LABELS[f]}
            <span className="orders-filter-count">{counts.get(f) ?? 0}</span>
          </button>
        ))}
      </nav>

      <main className="orders-list">
        {visible.map((order) => (
          <OrderListCard
            key={order.orderId}
            order={order}
            onAdvance={(id, status) => void handleAdvance(id, status)}
            onCancel={setCancelOrderId}
            onDetail={setDetailOrderId}
            busy={busyOrderId === order.orderId}
          />
        ))}
        {visible.length === 0 && (
          <p className="orders-empty glass-panel">No hay pedidos para este estado</p>
        )}
      </main>

      {detailOrder && (
        <OrderDetailModal
          order={detailOrder}
          onClose={() => setDetailOrderId(null)}
        />
      )}

      {cancelOrderId !== null && (
        <CancelOrderModal
          orderId={cancelOrderId}
          onClose={() => setCancelOrderId(null)}
          onCancelled={() => {
            setCancelOrderId(null);
            void refresh();
          }}
        />
      )}

      {posOpen && (
        <PosPanel
          onClose={() => setPosOpen(false)}
          onCreated={() => {
            setPosOpen(false);
            showToast("Pedido creado", "success");
            void refresh();
          }}
        />
      )}

      <KdsToastHost />

      {/* Mismos ids y rutas que el KDS: las páginas nunca se montan juntas. */}
      <audio id="newOrderSound" src="/ding.mp3" preload="auto" />
      <audio id="readySound" src="/ready.mp3" preload="auto" />
    </div>
  );
}
