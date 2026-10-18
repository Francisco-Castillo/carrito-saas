/**
 * Counter POS payload building (T4 of odd/tasks/orders-console.md).
 *
 * The POS creates orders through the EXISTING menu endpoint
 * (POST /api/orders/menu/{slug}, slug from the session) — the authenticated
 * variant simply adds the Bearer header; no new backend route is introduced.
 *
 * Item shape conventions are the ones the public menu flow (Menu.tsx) already
 * uses, extended with the price/subtotal fields that OrderItemDTO declares:
 * the backend recomputes both from the catalog inside createOrder, so the
 * values sent here are catalog-truthful and inert — they exist to keep the
 * payload self-describing. `tableId` travels ONLY for SALON (T2: SALON
 * requires a table of the business; RETIRO/DELIVERY never set one) and
 * `customerAddress` only for DELIVERY, same as the public menu flow.
 */

export type PosOrderType = "SALON" | "RETIRO" | "DELIVERY";
export type PosPaymentMethod = "EFECTIVO" | "TRANSFERENCIA";

/** One cart line of the counter POS. */
export interface PosCartItem {
  /** Product id, or the menu combo id with its "combo-" prefix. */
  id: string;
  name: string;
  price: number;
  isCombo: boolean;
  qty: number;
}

export interface PosOrderForm {
  orderType: PosOrderType;
  /** Selected table id — only meaningful for SALON. */
  tableId: number | null;
  customerName: string;
  customerPhone: string;
  customerAddress: string;
  paymentMethod: PosPaymentMethod;
  notes: string;
}

export interface PosOrderItemPayload {
  productId?: number;
  comboId?: number;
  quantity: number;
  price: number;
  subtotal: number;
}

export interface PosOrderRequestPayload {
  customerName: string;
  customerPhone: string;
  orderType: PosOrderType;
  paymentMethod: PosPaymentMethod;
  notes: string;
  items: PosOrderItemPayload[];
  tableId?: number;
  customerAddress?: string;
}

/** "combo-3" → 3; "5" → 5. Same stripping the menu flow applies. */
function numericId(id: string): number {
  return Number(id.replace("combo-", ""));
}

/** Builds the POST body for the menu order endpoint from the POS state. */
export function buildOrderRequest(
  form: PosOrderForm,
  items: PosCartItem[]
): PosOrderRequestPayload {
  const payload: PosOrderRequestPayload = {
    customerName: form.customerName,
    customerPhone: form.customerPhone,
    orderType: form.orderType,
    paymentMethod: form.paymentMethod,
    notes: form.notes,
    items: items.map((item) => ({
      ...(item.isCombo ? { comboId: numericId(item.id) } : { productId: numericId(item.id) }),
      quantity: item.qty,
      price: item.price,
      subtotal: item.price * item.qty,
    })),
  };

  // SALON exige mesa (validación T2 del backend): el id viaja solo ahí.
  if (form.orderType === "SALON" && form.tableId !== null) {
    payload.tableId = form.tableId;
  }

  // Misma convención del menú público: la dirección solo para DELIVERY.
  if (form.orderType === "DELIVERY") {
    payload.customerAddress = form.customerAddress;
  }

  return payload;
}

/**
 * Client-side validation with the same Spanish rules the backend enforces
 * ("El nombre del cliente es obligatorio", SALON sin mesa, delivery sin
 * dirección, carrito vacío). Returns the error message, or null when the
 * order can be sent.
 */
export function validatePosOrder(
  form: PosOrderForm,
  items: PosCartItem[]
): string | null {
  if (items.length === 0) {
    return "Agrega al menos un producto al pedido";
  }
  if (form.customerName.trim() === "") {
    return "El nombre del cliente es obligatorio";
  }
  if (form.orderType === "SALON" && form.tableId === null) {
    return "Selecciona una mesa para el pedido de salón";
  }
  if (form.orderType === "DELIVERY" && form.customerAddress.trim() === "") {
    return "Ingresa la dirección para el delivery";
  }
  return null;
}
