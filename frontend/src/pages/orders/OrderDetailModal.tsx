import { formatARS } from "../../format";
import { formatPhone } from "../kds/format";
import {
  ORDER_TYPE_LABELS,
  PAYMENT_LABELS,
  type ManagementOrder,
} from "./api";

interface OrderDetailModalProps {
  order: ManagementOrder;
  onClose: () => void;
}

/**
 * Full detail of one order (T3): items with unit price and subtotal, total,
 * customer, payment method, table, timeline timestamps and — for cancelled
 * orders — the reason and note. Spanish copy, glass modal matching the KDS
 * modal pattern.
 */
export default function OrderDetailModal({ order, onClose }: OrderDetailModalProps) {
  return (
    <div
      className="orders-modal"
      role="dialog"
      aria-modal="true"
      aria-labelledby="orders-detail-title"
      onClick={(e) => {
        // Clic fuera del contenido cierra el modal, como en el KDS.
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="orders-modal-content glass-panel">
        <header className="orders-modal-head">
          <h3 id="orders-detail-title">
            Pedido #{order.orderNumber} · {ORDER_TYPE_LABELS[order.orderType] ?? order.orderType}
          </h3>
          <button
            type="button"
            className="orders-modal-close"
            onClick={onClose}
            aria-label="Cerrar detalle"
          >
            ✕
          </button>
        </header>

        <dl className="orders-detail-grid">
          <div>
            <dt>Cliente</dt>
            <dd>{order.customerName}</dd>
          </div>
          {order.customerPhone && (
            <div>
              <dt>Celular</dt>
              <dd>
                <a href={`tel:${order.customerPhone}`}>{formatPhone(order.customerPhone)}</a>
              </dd>
            </div>
          )}
          {order.customerAddress && (
            <div>
              <dt>Dirección</dt>
              <dd>{order.customerAddress}</dd>
            </div>
          )}
          {order.table && (
            <div>
              <dt>Mesa</dt>
              <dd>{order.table.name}</dd>
            </div>
          )}
          <div>
            <dt>Forma de pago</dt>
            <dd>{order.paymentMethod ? PAYMENT_LABELS[order.paymentMethod] ?? order.paymentMethod : "—"}</dd>
          </div>
          <div>
            <dt>Ingresó</dt>
            <dd>{formatTimestamp(order.createdAt)}</dd>
          </div>
        </dl>

        <table className="orders-detail-items">
          <thead>
            <tr>
              <th scope="col">Ítem</th>
              <th scope="col">Cant.</th>
              <th scope="col">Unitario</th>
              <th scope="col">Subtotal</th>
            </tr>
          </thead>
          <tbody>
            {order.items.map((item, i) => (
              <tr key={i}>
                <td>{item.name}</td>
                <td>{item.quantity}</td>
                <td>{formatARS(item.unitPrice)}</td>
                <td>{formatARS(item.subtotal)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td colSpan={3}>Total</td>
              <td>{formatARS(order.total)}</td>
            </tr>
          </tfoot>
        </table>

        <section className="orders-detail-timeline">
          <h4>Línea de tiempo</h4>
          <ul>
            <li>Ingresó: {formatTimestamp(order.createdAt)}</li>
            <li>En preparación: {formatTimestamp(order.timeline?.preparingAt)}</li>
            <li>Listo: {formatTimestamp(order.timeline?.readyAt)}</li>
            <li>Entregado: {formatTimestamp(order.timeline?.completedAt)}</li>
            <li>Cancelado: {formatTimestamp(order.timeline?.cancelledAt)}</li>
          </ul>
        </section>

        {order.notes && (
          <p className="orders-detail-notes">
            <strong>Observaciones:</strong> {order.notes}
          </p>
        )}

        {order.status === "CANCELLED" && order.cancellationReason && (
          <p className="orders-detail-cancellation">
            <strong>Cancelado:</strong> {order.cancellationReason.description}
            {order.cancellationNote && ` — ${order.cancellationNote}`}
          </p>
        )}
      </div>
    </div>
  );
}

function formatTimestamp(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleTimeString("es-ES", { hour: "2-digit", minute: "2-digit" });
}
