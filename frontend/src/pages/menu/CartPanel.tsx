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
  expanded: boolean;
  onCustomerNameChange: (value: string) => void;
  onOrderTypeChange: (value: OrderType) => void;
  onAddressChange: (value: string) => void;
  onNotesChange: (value: string) => void;
  onPaymentMethodChange: (value: PaymentMethod) => void;
  onToggleExpanded: () => void;
  onSubmit: () => void;
}

export default function CartPanel({
  cart,
  total,
  customerName,
  orderType,
  address,
  notes,
  paymentMethod,
  sending,
  expanded,
  onCustomerNameChange,
  onOrderTypeChange,
  onAddressChange,
  onNotesChange,
  onPaymentMethodChange,
  onToggleExpanded,
  onSubmit,
}: CartPanelProps) {
  const itemCount = Object.keys(cart).length;

  return (
    <aside
      className="cart glass-panel"
      data-expanded={expanded || undefined}
      aria-label="Tu pedido"
    >
      <button
        type="button"
        className="cart-summary"
        onClick={onToggleExpanded}
        aria-expanded={expanded}
      >
        <span className="cart-summary-label">
          Tu pedido
          <span className="cart-summary-count">
            {" "}
            ({itemCount} {itemCount === 1 ? "ítem" : "ítems"})
          </span>
        </span>
        <span className="cart-summary-total">Total: ${total}</span>
        <span className="cart-summary-chevron" aria-hidden="true">
          ▼
        </span>
      </button>

      <div className="cart-body">
        <h2 className="cart-title">Tu pedido</h2>

        <div className="cart-items">
          {Object.values(cart).map((item) => (
            <div key={item.product.id} className="cart-line">
              {item.qty} x {item.product.name}
            </div>
          ))}
        </div>

        <div className="cart-total">
          Total: ${total}
        </div>

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
            <input
              className="order-input"
              type="text"
              placeholder="Dirección (solo para delivery)"
              value={address}
              onChange={(e) => onAddressChange(e.target.value)}
            />
          )}

          <label className="order-label" htmlFor="paymentMethod">
            Forma de pago
          </label>
          <select
            id="paymentMethod"
            className="order-input order-select"
            value={paymentMethod}
            onChange={(e) => onPaymentMethodChange(e.target.value as PaymentMethod)}
          >
            <option value="EFECTIVO">Efectivo</option>
            <option value="TRANSFERENCIA">Transferencia</option>
          </select>

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
      </div>
    </aside>
  );
}
