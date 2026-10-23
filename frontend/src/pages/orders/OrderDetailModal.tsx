import { formatARS } from "../../format";
import { formatPhone } from "../kds/format";
import CopyPhoneButton from "./CopyPhoneButton";
import {
  ORDER_TYPE_LABELS,
  PAYMENT_LABELS,
  STATUS_LABELS,
  type ManagementOrder,
  type ManagementSelectedOption,
} from "./api";
import { whatsappUrl } from "./wa";

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
            <dt>Estado</dt>
            <dd>{STATUS_LABELS[order.status] ?? order.status}</dd>
          </div>
          <div>
            <dt>Cliente</dt>
            <dd>{order.customerName}</dd>
          </div>
          {order.customerPhone && (
            <div>
              <dt>Celular</dt>
              <dd className="orders-detail-phone">
                <a
                  href={whatsappUrl(order.customerPhone) ?? `tel:${order.customerPhone}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  title="Abrir chat de WhatsApp con el cliente"
                >
                  {formatPhone(order.customerPhone)}
                </a>
                <CopyPhoneButton phone={order.customerPhone} />
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
                <td>
                  {item.name}
                  {item.notes && <span className="orders-item-note">{item.notes}</span>}
                  {/* Opciones elegidas (snapshot del backend): debajo del
                      producto, "Grupo: Opción" con su recargo — mismo orden
                      en que el dueño definió los grupos. Sin opciones no
                      muestra el bloque. */}
                  {(item.selectedOptions ?? []).length > 0 && (
                    <div className="orders-detail-item-options">
                      {item.selectedOptions!.map((opt, optIdx) => (
                        <span className="orders-detail-item-option" key={optIdx}>
                          {optionLabel(opt)}
                        </span>
                      ))}
                    </div>
                  )}
                </td>
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

/** "Grupo: Opción" — el recargo solo se muestra cuando el cliente lo paga. */
function optionLabel(opt: ManagementSelectedOption): string {
  const delta = Number(opt.priceDelta ?? 0);
  return delta > 0
    ? `${opt.groupName}: ${opt.optionName} (+${formatARS(delta)})`
    : `${opt.groupName}: ${opt.optionName}`;
}
