import FoodImage from "./FoodImage";
import type { Cart, OrderType, PaymentMethod } from "./types";

interface CartPanelProps {
  cart: Cart;
  total: number;
  customerName: string;
  orderType: OrderType;
  address: string;
  notes: string;
  paymentMethod: PaymentMethod;
  sending: boolean;
  onCustomerNameChange: (value: string) => void;
  onOrderTypeChange: (value: OrderType) => void;
  onAddressChange: (value: string) => void;
  onNotesChange: (value: string) => void;
  onPaymentMethodChange: (value: PaymentMethod) => void;
  /** +/− on a cart line, by cart entry id. */
  onAddItem: (id: string) => void;
  onRemoveItem: (id: string) => void;
  onSubmit: () => void;
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
  orderType,
  address,
  notes,
  paymentMethod,
  sending,
  onCustomerNameChange,
  onOrderTypeChange,
  onAddressChange,
  onNotesChange,
  onPaymentMethodChange,
  onAddItem,
  onRemoveItem,
  onSubmit,
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
              <span className="cart-line-total">${item.qty * item.product.price}</span>
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

      <p className="cart-total">Total: ${total}</p>

      <div className="order-form">
        <input
          className="order-input"
          type="text"
          placeholder="Nombre y Apellido"
          value={customerName}
          onChange={(e) => onCustomerNameChange(e.target.value)}
        />

        <select
          className="order-input order-select"
          value={orderType}
          onChange={(e) => onOrderTypeChange(e.target.value as OrderType)}
          aria-label="Tipo de pedido"
        >
          <option value="RETIRO">Retiro en el local</option>
          <option value="DELIVERY">Delivery</option>
        </select>

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

        <textarea
          className="order-input order-textarea"
          placeholder="Observaciones (ej: sin cebolla, sin mayonesa)"
          value={notes}
          onChange={(e) => onNotesChange(e.target.value)}
        />
      </div>

      <button type="button" className="cart-submit" onClick={onSubmit} disabled={sending}>
        Hacer el pedido
      </button>
    </>
  );
}
