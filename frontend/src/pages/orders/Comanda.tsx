import {
  ORDER_TYPE_LABELS,
  type ManagementOrder,
  type ManagementSelectedOption,
} from "./api";
import "./Comanda.css";

interface ComandaProps {
  order: ManagementOrder;
}

/**
 * Print-only ticket (comanda) for a thermal printer (~80mm roll) —
 * KITCHEN-FACING ONLY:
 *
 *   COMANDA  #101
 *   Delivery · 12:00
 *   --------------------------------
 *   2× Hamburguesa
 *      Tipo de pan: Integral
 *      -- sin cebolla
 *   --------------------------------
 *   Cliente: ...
 *   Obs: Tocar timbre
 *
 * Sobra para la cocina (y fuera del ticket): TOTAL y recargos de opciones
 * (dinero = caja), teléfono y dirección (contacto/reparto = consola) y
 * método de pago (caja). El nombre del cliente queda: sirve para llamar el
 * pedido en retiro.
 *
 * The component renders always but stays hidden on screen (Comanda.css,
 * display:none) and becomes the ONLY visible content inside @media print —
 * window.print() on the orders panel then sends exactly this ticket to the
 * printer the browser has configured (the machine's default or the one the
 * operator picks in the dialog).
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
        {order.table && <div>Mesa: {order.table.name}</div>}
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

/** "Grupo: Opción" — los recargos son dinero (caja), no cocina. */
function optionLabel(opt: ManagementSelectedOption): string {
  return `${opt.groupName}: ${opt.optionName}`;
}
