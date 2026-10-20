import { formatARS } from "../../format";
import FoodImage from "./FoodImage";
import type { Cart, OrderType, PaymentMethod, PublicTable } from "./types";

interface CartPanelProps {
  cart: Cart;
  total: number;
  customerName: string;
  customerPhone: string;
  orderType: OrderType;
  address: string;
  notes: string;
  paymentMethod: PaymentMethod;
  sending: boolean;
  /**
   * Mesa fija (QR de mesa): cuando viaja, el pedido es de salón por decisión
   * del negocio — el selector de tipo desaparece y se muestra un badge con la
   * mesa ("Fijar Salón" + "Badge con mesa", feature pedido-qr-mesa).
   */
  table?: PublicTable | null;
  onCustomerNameChange: (value: string) => void;
  onCustomerPhoneChange: (value: string) => void;
  onOrderTypeChange: (value: OrderType) => void;
  onAddressChange: (value: string) => void;
  onNotesChange: (value: string) => void;
  onPaymentMethodChange: (value: PaymentMethod) => void;
  /** +/− on a cart line, by cart entry id. */
  onAddItem: (id: string) => void;
  onRemoveItem: (id: string) => void;
  onSubmit: () => void;
  /** Local cerrado: la confirmación se bloquea (la regla real vive en el backend). */
  closed?: boolean;
  /** Próxima apertura computable — se muestra junto al copy de cerrado. */
  closedNotice?: string | null;
}

/**
 * Cart body: lines (thumbnail, name, − n + stepper, line total) plus the order
 * form. Presentation differs by breakpoint (sticky sidebar ≥1024px, sheet
 * below) but the content is shared.
 */
export default function CartPanel({
  cart,
  total,
  customerName,
  customerPhone,
  orderType,
  address,
  notes,
  paymentMethod,
  sending,
  onCustomerNameChange,
  onCustomerPhoneChange,
  onOrderTypeChange,
  onAddressChange,
  onNotesChange,
  onPaymentMethodChange,
  onAddItem,
  onRemoveItem,
  onSubmit,
  closed = false,
  closedNotice = null,
  table = null,
}: CartPanelProps) {
  return (
    <>
      <h2 className="cart-title">Tu pedido</h2>

      <ul className="cart-lines">
        {Object.values(cart).map((item) => (
          <li key={item.product.id} className="cart-line">
            <FoodImage
              src={item.product.imageUrl}
              alt={item.product.name}
              categoryName={item.product.categoryName}
              className="cart-thumb"
            />
            <div className="cart-line-info">
              <span className="cart-line-name">{item.product.name}</span>
              <span className="cart-line-total">
                {formatARS(item.qty * item.product.price)}
              </span>
            </div>
            <div className="qty-stepper">
              <button
                type="button"
                className="qty-btn qty-btn--minus"
                onClick={() => onRemoveItem(item.product.id)}
                aria-label={`Restar ${item.product.name}`}
              >
                −
              </button>
              <span
                className="qty-value"
                aria-live="polite"
                aria-label={`Cantidad de ${item.product.name}`}
              >
                {item.qty}
              </span>
              <button
                type="button"
                className="qty-btn qty-btn--plus"
                onClick={() => onAddItem(item.product.id)}
                aria-label={`Sumar ${item.product.name}`}
              >
                +
              </button>
            </div>
          </li>
        ))}
      </ul>

      <p className="cart-total">Total: {formatARS(total)}</p>

      <div className="order-form">
        {/* Mesa fija (QR de mesa): sin datos de contacto — la cocina identifica
            el pedido por su mesa, y pedirle el celular a alguien que está
            sentado en ella no aporta nada. */}
        {!table && (
          <>
            <input
              className="order-input"
              type="text"
              placeholder="Nombre y Apellido"
              value={customerName}
              onChange={(e) => onCustomerNameChange(e.target.value)}
            />

            {/* Celular obligatorio: la cocina lo muestra como llamada con un tap. */}
            <input
              className="order-input"
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              placeholder="Celular (ej. 11 5555 1234)"
              value={customerPhone}
              onChange={(e) => onCustomerPhoneChange(e.target.value)}
            />
          </>
        )}

        {table ? (
          // Mesa fija (QR de mesa): el pedido ES de salón — el tipo no se
          // pregunta y el cliente ve su mesa. La mesa viaja igual en el
          // payload (tableId), donde el backend la valida.
          <div className="table-badge" role="status" aria-label="Mesa del pedido">
            <span className="table-badge-label">Mesa {table.tableNumber}</span>
            {table.tableName && <span className="table-badge-name"> · {table.tableName}</span>}
          </div>
        ) : (
          <select
            className="order-input order-select"
            value={orderType}
            onChange={(e) => onOrderTypeChange(e.target.value as OrderType)}
            aria-label="Tipo de pedido"
          >
            <option value="RETIRO">Retiro en el local</option>
            <option value="DELIVERY">Delivery</option>
          </select>
        )}

        {orderType === "DELIVERY" && (
          <div className="order-address-reveal">
            <input
              className="order-input"
              type="text"
              placeholder="Dirección (solo para delivery)"
              value={address}
              onChange={(e) => onAddressChange(e.target.value)}
            />
          </div>
        )}

        {/* S4 de pedido-qr-mesa: un pedido de mesa se paga en la mesa — sin
            selector de forma de pago en ese modo. */}
        {!table && (
          <div className="segmented" role="group" aria-label="Forma de pago">
            <button
              type="button"
              className="segment"
              aria-pressed={paymentMethod === "EFECTIVO"}
              onClick={() => onPaymentMethodChange("EFECTIVO")}
            >
              Efectivo
            </button>
            <button
              type="button"
              className="segment"
              aria-pressed={paymentMethod === "TRANSFERENCIA"}
              onClick={() => onPaymentMethodChange("TRANSFERENCIA")}
            >
              Transferencia
            </button>
          </div>
        )}

        <textarea
          className="order-input order-textarea"
          placeholder="Observaciones (ej: sin cebolla, sin mayonesa)"
          value={notes}
          onChange={(e) => onNotesChange(e.target.value)}
        />
      </div>

      {closed && (
        <p className="cart-closed" role="status">
          El local está cerrado
          {closedNotice ? ` · ${closedNotice}` : ""}
        </p>
      )}

      <button type="button" className="cart-submit" onClick={onSubmit} disabled={sending || closed}>
        Hacer el pedido
      </button>
    </>
  );
}
