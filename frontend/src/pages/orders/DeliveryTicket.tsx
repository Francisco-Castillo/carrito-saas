import { formatARS } from "../../format";
import {
  ORDER_TYPE_LABELS,
  type ManagementOrder,
  type ManagementSelectedOption,
} from "./api";
import "./Comanda.css";

interface DeliveryTicketProps {
  order: ManagementOrder;
}

/**
 * Print-only DELIVERY copy (delivery-ticket-copy S3): the mirror of the
 * kitchen comanda. The kitchen keeps the comanda when the order is READY and
 * hands THIS ticket to the driver, so it carries everything the driver needs:
 *
 *   ENTREGA  #101
 *   Delivery · 12:00
 *   --------------------------------
 *   Cliente: Juan Pérez
 *   Tel: 11 5555 1234
 *   Dirección: Calle Falsa 123
 *   --------------------------------
 *   2× Hamburguesa
 *      Tipo de pan: Integral
 *   --------------------------------
 *   TOTAL   $ 6.400
 *   COBRAR $ 6.400   /   PAGADO
 *   Obs: Tocar timbre
 *
 * The payment line is dynamic and rides on the payment gate: a TRANSFERENCIA
 * order reached the kitchen only after the operator confirmed the transfer,
 * so the ticket says PAGADO; an EFECTIVO order is collected on hand-off
 * (COBRAR with the amount). Option surcharges are omitted — the TOTAL already
 * carries them and the customer verifies options, not prices.
 *
 * Same print mechanics as Comanda: hidden on screen, visible inside
 * @media print (Comanda.css scopes .delivery-ticket-print).
 */
export default function DeliveryTicket({ order }: DeliveryTicketProps) {
  const paid = order.paymentMethod === "TRANSFERENCIA";

  return (
    <div className="delivery-ticket-print" data-testid="delivery-ticket" aria-hidden="true">
      <header className="comanda-head">
        <span className="comanda-title">ENTREGA</span>
        <span className="comanda-number">#{order.orderNumber}</span>
      </header>

      <div className="comanda-meta">
        {ORDER_TYPE_LABELS[order.orderType] ?? order.orderType}
        {" · "}
        {formatTime(order.createdAt)}
      </div>

      <div className="comanda-section delivery-customer">
        {order.customerName && <div>Cliente: {order.customerName}</div>}
        {order.customerPhone && <div>Tel: {order.customerPhone}</div>}
        {order.customerAddress && <div>Dirección: {order.customerAddress}</div>}
        {order.table && <div>Mesa: {order.table.name}</div>}
      </div>

      <ul className="comanda-items">
        {order.items.map((item, i) => (
          <li key={i} className="comanda-item">
            <span className="comanda-item-line">
              {item.quantity}× {item.name}
            </span>
            {(item.selectedOptions ?? []).map((opt, optIdx) => (
              <span className="comanda-item-option" key={optIdx}>
                {optionLabel(opt)}
              </span>
            ))}
            {item.notes && (
              <span className="comanda-item-note">-- {item.notes}</span>
            )}
          </li>
        ))}
      </ul>

      <div className="delivery-total">
        <span>TOTAL</span>
        <span>{formatARS(order.total)}</span>
      </div>

      {order.paymentMethod && (
        <div className={`delivery-payment ${paid ? "delivery-payment--paid" : "delivery-payment--collect"}`}>
          {paid ? "PAGADO" : `COBRAR ${formatARS(order.total)}`}
        </div>
      )}

      {order.notes && <div className="comanda-notes">Obs: {order.notes}</div>}
    </div>
  );
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleTimeString("es-ES", { hour: "2-digit", minute: "2-digit" });
}

/** "Grupo: Opción" — the surcharge is money detail the TOTAL already carries. */
function optionLabel(opt: ManagementSelectedOption): string {
  return `${opt.groupName}: ${opt.optionName}`;
}
