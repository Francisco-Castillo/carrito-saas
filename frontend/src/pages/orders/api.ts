/**
 * API layer of the orders console (T3/T4), against the T1+T2 backend
 * contract (commit 68dc55d):
 *
 *   - GET  /api/business/orders            → full-day, all-status view
 *                                            (OrderManagementDTO[])
 *   - PATCH /api/orders/{id}/status?status=X → transition (409 on invalid)
 *   - PATCH /api/orders/{id}/cancel          → cancel with reason
 *   - POST /api/orders/menu/{slug}           → existing order creation route,
 *                                              reused by the counter POS with
 *                                              the Bearer header added
 *   - GET  /api/products + /api/combos       → POS catalog (same owner
 *                                              endpoints the admin panel uses)
 *   - GET  /api/tables                       → table picker for SALON
 *
 * Headers follow the shared panel convention: Authorization Bearer with the
 * session token read hot from localStorage (same as admin/api.ts and the KDS).
 */

import { getSessionRestaurantSlug } from "../dashboard/auth";

export type OrderStatus = "PENDING_PAYMENT" | "NEW" | "PREPARING" | "READY" | "DELIVERED" | "CANCELLED";
export type ManagementOrderType = "SALON" | "RETIRO" | "DELIVERY";
export type ManagementPaymentMethod = "EFECTIVO" | "TRANSFERENCIA";

export interface ManagementSelectedOption {
  groupName: string;
  optionName: string;
  priceDelta: number;
}

export interface ManagementOrderItem {
  name: string;
  quantity: number;
  /** Preferencias del cliente ("sin mayonesa"): opcional, las muestra la
   * consola junto al ítem. */
  notes?: string | null;
  unitPrice: number;
  subtotal: number;
  /** Opciones elegidas del ítem (snapshot del backend). Ausente en payloads viejos. */
  selectedOptions?: ManagementSelectedOption[];
}

export interface ManagementOrderTable {
  id: number;
  name: string;
  number?: number | null;
}

export interface ManagementOrderTimeline {
  preparingAt: string | null;
  readyAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
}

export interface ManagementCancellationReason {
  id: number;
  description: string;
}

/** Espejo del backend OrderManagementDTO (commit 68dc55d). */
export interface ManagementOrder {
  orderId: number;
  orderNumber: number;
  orderType: string;
  status: OrderStatus;
  customerName: string;
  customerPhone: string | null;
  customerAddress: string | null;
  paymentMethod: string | null;
  notes: string | null;
  table: ManagementOrderTable | null;
  total: number;
  createdAt: string;
  items: ManagementOrderItem[];
  timeline: ManagementOrderTimeline | null;
  cancellationReason: ManagementCancellationReason | null;
  cancellationNote: string | null;
}

/** Spanish labels of the six statuses (filter pills + cards). */
export const STATUS_LABELS: Record<OrderStatus, string> = {
  PENDING_PAYMENT: "Pago pendiente",
  NEW: "Nuevos",
  PREPARING: "Preparando",
  READY: "Listos",
  DELIVERED: "Entregados",
  CANCELLED: "Cancelados",
};

export const ORDER_TYPE_LABELS: Record<string, string> = {
  SALON: "Salón",
  RETIRO: "Retiro",
  DELIVERY: "Delivery",
};

export const PAYMENT_LABELS: Record<string, string> = {
  EFECTIVO: "Efectivo",
  TRANSFERENCIA: "Transferencia",
};

/**
 * Transition graph of the backend (canTransitionTo): the UI only ever offers
 * these targets, so invalid transitions are prevented before any request.
 *
 * Console actions (S2 de odd/tasks/transfer-payment-gate.md): la consola
 * SOLO acepta/cancela — preparar, listo y entregado son flujo del KDS.
 */
export const ALLOWED_TRANSITIONS: Record<OrderStatus, OrderStatus[]> = {
  PENDING_PAYMENT: ["NEW", "CANCELLED"],
  NEW: ["PREPARING", "CANCELLED"],
  PREPARING: ["READY", "CANCELLED"],
  READY: ["DELIVERED"],
  DELIVERED: [],
  CANCELLED: [],
};

export function canTransition(from: OrderStatus, to: OrderStatus): boolean {
  return ALLOWED_TRANSITIONS[from].includes(to);
}

function authHeaders(): Record<string, string> {
  return { Authorization: "Bearer " + (localStorage.getItem("token") ?? "") };
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), "Content-Type": "application/json" };
}

/** Extrae el `message` en español del cuerpo de error del backend (o null). */
async function serverErrorMessage(response: { json: () => Promise<unknown> }): Promise<string | null> {
  try {
    const data = (await response.json()) as { message?: string };
    return data.message && data.message.trim() !== "" ? data.message : null;
  } catch {
    return null;
  }
}

/**
 * GET /api/business/orders — pedidos del día en TODOS los estados (date
 * opcional en el backend: sin parámetro el servidor usa su fecha local, la
 * misma convención del dashboard).
 */
export async function loadManagementOrders(): Promise<ManagementOrder[]> {
  const response = await fetch("/api/business/orders", { headers: authHeaders() });
  if (!response.ok) {
    throw new Error("Error cargando pedidos");
  }
  return (await response.json()) as ManagementOrder[];
}

/**
 * PATCH /api/orders/{id}/status?status=X. Devuelve { ok, message }: el 409
 * de transición inválida NO se traga — el caller muestra el mensaje del
 * servidor como toast y re-sincroniza la vista (contrato del KDS, fix
 * fb07a32).
 */
export async function advanceOrderStatus(
  orderId: number,
  status: OrderStatus
): Promise<{ ok: boolean; message: string }> {
  try {
    const response = await fetch(`/api/orders/${orderId}/status?status=${status}`, {
      method: "PATCH",
      headers: authHeaders(),
    });
    if (!response.ok) {
      const message = (await serverErrorMessage(response)) ?? "No se pudo actualizar el pedido";
      return { ok: false, message };
    }
    return { ok: true, message: "" };
  } catch (e) {
    console.error("Error actualizando estado", e);
    return { ok: false, message: "No se pudo actualizar el pedido" };
  }
}

/** PATCH /api/orders/{id}/cancel con { reasonId, note } (mismo endpoint del KDS). */
export async function cancelManagementOrder(
  orderId: number,
  reasonId: number,
  note: string
): Promise<void> {
  await fetch(`/api/orders/${orderId}/cancel`, {
    method: "PATCH",
    headers: jsonHeaders(),
    body: JSON.stringify({ reasonId, note }),
  });
}

export interface PosProduct {
  id: number;
  name: string;
  price: number;
  active?: boolean;
}

export interface PosCombo {
  id: number;
  name: string;
  price: number;
  active?: boolean;
}

export interface PosTable {
  id: number;
  name: string;
}

/** Catálogo del POS: productos y combos activos del negocio del JWT. */
export async function loadPosCatalog(): Promise<{ products: PosProduct[]; combos: PosCombo[] }> {
  const [productsRes, combosRes] = await Promise.all([
    fetch("/api/products", { headers: authHeaders() }),
    fetch("/api/combos", { headers: authHeaders() }),
  ]);
  if (!productsRes.ok || !combosRes.ok) {
    throw new Error("Error cargando el catálogo");
  }
  const products = (await productsRes.json()) as PosProduct[];
  const combos = (await combosRes.json()) as PosCombo[];
  return {
    products: products.filter((p) => p.active !== false),
    combos: combos.filter((c) => c.active !== false),
  };
}

/** Mesas del negocio para el picker de SALON (GET /api/tables, paginado). */
export async function loadTables(): Promise<PosTable[]> {
  const response = await fetch("/api/tables?page=0&size=200", { headers: authHeaders() });
  if (!response.ok) {
    throw new Error("Error cargando mesas");
  }
  const page = (await response.json()) as {
    content?: Array<{ id: number; tableName?: string | null; tableNumber?: number | null }>;
  };
  return (page.content ?? [])
    .filter((t) => t.id != null)
    .map((t) => ({ id: t.id, name: t.tableName ?? `Mesa ${t.tableNumber ?? t.id}` }));
}

export interface CreateOrderResult {
  ok: boolean;
  message: string;
  /** Id del pedido creado (OrderDTO.orderId): el POS lo usa para confirmar
   * el pago en el mismo paso cuando el método es TRANSFERENCIA. */
  orderId: number | null;
}

/**
 * POST /api/orders/menu/{slug} con la sesión como slug y el Bearer header.
 * Endpoint EXISTENTE: no se agrega backend. Los errores de validación del
 * backend llegan con su `message` en español para toastearse tal cual.
 */
export async function createCounterOrder(body: unknown): Promise<CreateOrderResult> {
  const slug = getSessionRestaurantSlug();
  if (slug === null) {
    return { ok: false, message: "Restaurante no especificado", orderId: null };
  }
  try {
    const response = await fetch(`/api/orders/menu/${slug}`, {
      method: "POST",
      headers: jsonHeaders(),
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      const message = (await serverErrorMessage(response)) ?? "El local rechazó el pedido";
      return { ok: false, message, orderId: null };
    }
    const created = (await response.json()) as { orderId?: number };
    return { ok: true, message: "", orderId: created.orderId ?? null };
  } catch (e) {
    console.error("Error creando el pedido", e);
    return { ok: false, message: "No se pudo crear el pedido", orderId: null };
  }
}
