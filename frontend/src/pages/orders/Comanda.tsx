import { formatARS } from "../../format";
import {
  ORDER_TYPE_LABELS,
  PAYMENT_LABELS,
  type ManagementOrder,
  type ManagementSelectedOption,
} from "./api";
import "./Comanda.css";

interface ComandaProps {
  order: ManagementOrder;
}

/**
 * Print-only ticket (comanda) for a thermal printer (~80mm roll):
 *
 *   COMANDA  #101
 *   Delivery · 12:00
 *   --------------------------------
 *   2× Hamburguesa
 *      Tipo de pan: Integral (+$ 200)
 *      -- sin cebolla
 *   --------------------------------
 *   Cliente / Tel / Dirección / Mesa / Pago
 *   TOTAL  $ 6.400
 *   Obs: Tocar timbre
 *
 * The component renders always but stays hidden on screen (Comanda.css,
 * display:none) and becomes the ONLY visible content inside @media print —
 * window.print() on the orders panel then sends exactly this ticket to the
 * printer the browser has configured (the machine's default or the one the
 * operator picks in the dialog).
 *
 * All copy is kitchen-facing Spanish; money uses the shared formatARS
 * (integer pesos, "$ 3.200").
 */
export default function Comanda({ order }: ComandaProps) {
  return (
    <div className="comanda-print" data-testid="comanda" aria-hidden="true">
      <header className="comanda-head">
        <span className="comanda-title">COMANDA</span>
        <span className="comanda-number">#{order.orderNumber}</span>
      </header>

      <div className="comanda-meta">
        {ORDER_TYPE_LABELS[order.orderType] ?? order.orderType}
        {" · "}
        {formatTime(order.createdAt)}
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

      <div className="comanda-section comanda-customer">
        {order.customerName && <div>Cliente: {order.customerName}</div>}
        {order.customerPhone && <div>Tel: {order.customerPhone}</div>}
        {order.customerAddress && <div>Dirección: {order.customerAddress}</div>}
        {order.table && <div>Mesa: {order.table.name}</div>}
        {order.paymentMethod && (
          <div>Pago: {PAYMENT_LABELS[order.paymentMethod] ?? order.paymentMethod}</div>
        )}
      </div>

      <div className="comanda-total">
        <span>TOTAL</span>
        <span>{formatARS(order.total)}</span>
      </div>

      {order.notes && <div className="comanda-notes">Obs: {order.notes}</div>}
    </div>
  );
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleTimeString("es-ES", { hour: "2-digit", minute: "2-digit" });
}

/** "Grupo: Opción (+$ recargo)" — same layout the detail modal shows. */
function optionLabel(opt: ManagementSelectedOption): string {
  const delta = Number(opt.priceDelta ?? 0);
  return delta > 0
    ? `${opt.groupName}: ${opt.optionName} (+${formatARS(delta)})`
    : `${opt.groupName}: ${opt.optionName}`;
}
