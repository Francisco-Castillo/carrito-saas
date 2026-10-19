import { formatARS } from "../../format";
import { formatPhone } from "../kds/format";
import {
  ORDER_TYPE_LABELS,
  canTransition,
  type ManagementOrder,
  type OrderStatus,
} from "./api";
import { whatsappUrl } from "./wa";

interface OrderListCardProps {
  order: ManagementOrder;
  onAdvance: (orderId: number, status: OrderStatus) => void;
  onCancel: (orderId: number) => void;
  onDetail: (orderId: number) => void;
  busy: boolean;
}

/**
 * Card of the day list. The action buttons are derived ONLY from
 * ALLOWED_TRANSITIONS, so an invalid transition never renders a button:
 *   NEW       → "Empezar a preparar" + "Cancelar"
 *   PREPARING → "Marcar listo" + "Cancelar"
 *   READY     → "Marcar entregado"
 *   DELIVERED / CANCELLED → no actions (cancelled shows its reason).
 */
export default function OrderListCard({
  order,
  onAdvance,
  onCancel,
  onDetail,
  busy,
}: OrderListCardProps) {
  const statusClass = order.status.toLowerCase();

  return (
    <article className={`orders-card glass-panel orders-card--${statusClass}`}>
      <header className="orders-card-head">
        <span className="orders-card-number">#{order.orderNumber}</span>
        <span className={`orders-badge orders-badge--${statusClass}`}>
          {statusLabel(order.status)}
        </span>
      </header>

      <div className="orders-card-meta">
        <span>{ORDER_TYPE_LABELS[order.orderType] ?? order.orderType}</span>
        {order.table && <span> · {order.table.name}</span>}
        <span> · {formatTime(order.createdAt)}</span>
      </div>

      <p className="orders-card-customer">
        {order.customerName}
        {order.customerPhone && (
          <a
            className="orders-card-phone"
            href={whatsappUrl(order.customerPhone) ?? `tel:${order.customerPhone}`}
            target="_blank"
            rel="noopener noreferrer"
            title="Abrir chat de WhatsApp con el cliente"
          >
            {" "}
            {formatPhone(order.customerPhone)}
          </a>
        )}
      </p>

      <ul className="orders-card-items">
        {order.items.map((item, i) => (
          <li key={i}>
            {item.quantity}× {item.name}
          </li>
        ))}
      </ul>

      {order.notes && <p className="orders-card-notes">“{order.notes}”</p>}

      {order.status === "CANCELLED" && order.cancellationReason && (
        <p className="orders-card-cancellation">
          Cancelado: {order.cancellationReason.description}
          {order.cancellationNote && ` — ${order.cancellationNote}`}
        </p>
      )}

      <footer className="orders-card-foot">
        <span className="orders-card-total">{formatARS(order.total)}</span>
        <div className="orders-card-actions">
          {canTransition(order.status, "PREPARING") && (
            <button
              type="button"
              className="btn-action btn-action--start"
              disabled={busy}
              onClick={() => onAdvance(order.orderId, "PREPARING")}
            >
              Empezar a preparar
            </button>
          )}
          {canTransition(order.status, "READY") && (
            <button
              type="button"
              className="btn-action btn-action--ready"
              disabled={busy}
              onClick={() => onAdvance(order.orderId, "READY")}
            >
              Marcar listo
            </button>
          )}
          {canTransition(order.status, "DELIVERED") && (
            <button
              type="button"
              className="btn-action btn-action--deliver"
              disabled={busy}
              onClick={() => onAdvance(order.orderId, "DELIVERED")}
            >
              Marcar entregado
            </button>
          )}
          {canTransition(order.status, "CANCELLED") && (
            <button
              type="button"
              className="btn-action btn-action--cancel"
              disabled={busy}
              onClick={() => onCancel(order.orderId)}
            >
              Cancelar
            </button>
          )}
          <button
            type="button"
            className="btn-action btn-action--detail"
            onClick={() => onDetail(order.orderId)}
          >
            Ver detalle
          </button>
        </div>
      </footer>
    </article>
  );
}

function statusLabel(status: OrderStatus): string {
  const labels: Record<OrderStatus, string> = {
    NEW: "Nuevo",
    PREPARING: "Preparando",
    READY: "Listo",
    DELIVERED: "Entregado",
    CANCELLED: "Cancelado",
  };
  return labels[status];
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleTimeString("es-ES", { hour: "2-digit", minute: "2-digit" });
}
