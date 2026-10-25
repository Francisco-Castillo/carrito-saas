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
  /** Unit price: product price + the surcharges of the selected options. */
  price: number;
  isCombo: boolean;
  qty: number;
  /** Opciones elegidas del producto, ordenadas por definición de grupo
   * (mismo contrato que el menú público, S4 de product-options.md). Ausente
   * en combos y en líneas sin opciones. */
  selectedOptionIds?: number[];
  /** Nombres de las opciones elegidas, para mostrar la línea en el carrito. */
  optionNames?: string[];
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
  /** Opciones elegidas (S4 de product-options.md): ids ordenados por grupo.
   * El backend valida obligatorios/min/max y recalcula el precio. */
  selectedOptionIds?: number[];
}

export interface PosOrderRequestPayload {
  /** Ausentes en SALON: el pedido de mesa es anónimo (la mesa identifica). */
  customerName?: string;
  customerPhone?: string;
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

/**
 * Identidad de una línea del carrito: mismo producto con opciones distintas
 * son líneas separadas ("Hamburguesa con cheddar" ≠ "Hamburguesa con bacon").
 * Los ids van ordenados para que el orden de selección no cree líneas nuevas.
 */
export function posLineKey(id: string, optionIds: number[] | undefined): string {
  const sorted = [...(optionIds ?? [])].sort((a, b) => a - b).join("-");
  return sorted === "" ? id : `${id}#${sorted}`;
}

/** Precio unitario del producto con los recargos de las opciones elegidas. */
export function unitPriceWithSelections(
  product: { price: number; optionGroups?: { options: { id: number; priceDelta: number }[] }[] | null },
  selected: number[]
): number {
  let total = product.price;
  for (const group of product.optionGroups ?? []) {
    for (const option of group.options) {
      if (selected.includes(option.id)) total += option.priceDelta;
    }
  }
  return total;
}

/** Nombres de las opciones elegidas, en orden de definición de grupos. */
export function optionNamesFor(
  product: { optionGroups?: { options: { id: number; name: string }[] }[] | null },
  selected: number[]
): string[] {
  return (product.optionGroups ?? [])
    .flatMap((g) => g.options)
    .filter((o) => selected.includes(o.id))
    .map((o) => o.name);
}

/**
 * Selección ordenada por definición de grupos (el backend valida por grupo:
 * mismo contrato que el sheet del menú público). Filtra ids desconocidos.
 */
export function orderedSelection(
  product: { optionGroups?: { options: { id: number }[] }[] | null },
  selected: number[]
): number[] {
  return (product.optionGroups ?? [])
    .flatMap((g) => g.options.map((o) => o.id))
    .filter((id) => selected.includes(id));
}

/**
 * Validación client-side de la selección de un producto con grupos: un grupo
 * obligatorio sin elección bloquea el alta (mismo mensaje-en-español del
 * resto del POS). El min/max de los grupos múltiples lo impide el toggle.
 */
export function validatePosSelection(
  product: { optionGroups?: { name: string; required: boolean; options: { id: number }[] }[] | null },
  selected: number[]
): string | null {
  for (const group of product.optionGroups ?? []) {
    if (group.required && !group.options.some((o) => selected.includes(o.id))) {
      return `Elegí una opción de "${group.name}"`;
    }
  }
  return null;
}

/** Builds the POST body for the menu order endpoint from the POS state. */
export function buildOrderRequest(
  form: PosOrderForm,
  items: PosCartItem[]
): PosOrderRequestPayload {
  // SALON viaja sin datos del cliente: la mesa identifica al comensal y el
  // backend guarda el pedido anónimo (customerName/phone null).
  const isSalon = form.orderType === "SALON";
  const payload: PosOrderRequestPayload = {
    ...(isSalon
      ? {}
      : { customerName: form.customerName, customerPhone: form.customerPhone }),
    orderType: form.orderType,
    paymentMethod: form.paymentMethod,
    notes: form.notes,
    items: items.map((item) => ({
      ...(item.isCombo ? { comboId: numericId(item.id) } : { productId: numericId(item.id) }),
      quantity: item.qty,
      price: item.price,
      subtotal: item.price * item.qty,
      // selectedOptionIds ya viaja ordenado por grupo (posLineKey/ordered);
      // sin selecciones no viaja: mismo payload de siempre.
      ...((item.selectedOptionIds?.length ?? 0) > 0 ? { selectedOptionIds: item.selectedOptionIds } : {}),
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
  // SALON no pide nombre: la mesa identifica al comensal.
  if (form.orderType !== "SALON" && form.customerName.trim() === "") {
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
