/**
 * Puerto 1:1 de las llamadas al backend del legacy kds/kds.js (mismas rutas,
 * mismos headers Authorization Bearer con el token localStorage "token",
 * mismo manejo de errores).
 */

export interface KdsOrderItem {
  quantity: number;
  productName: string;
}

export interface KdsOrder {
  orderId: number;
  orderNumber: number;
  customerName: string;
  customerPhone: string | null;
  orderType: string; // legacy kds.js:184: solo "DELIVERY" cambia el tipo; resto → Retiro
  status: string; // NEW | PREPARING | READY | DELIVERED | CANCELLED
  notes: string | null;
  createdAt: string;
  items: KdsOrderItem[];
}

export interface CancellationReason {
  id: number;
  description: string;
}

/** Señal del 404 del legacy (kds.js:55): "Restaurante no encontrado". */
export class RestaurantNotFoundError extends Error {
  constructor() {
    super("Restaurante no encontrado");
  }
}

const params = new URLSearchParams(window.location.search);
const restaurantSlug = params.get("restaurant");

// kds.js:3-9: sin slug el legacy reemplaza el body con
// "Restaurante no especificado" y corta la ejecución.
export function getRestaurantSlug(): string | null {
  return restaurantSlug;
}

function authHeaders(): Record<string, string> {
  return { Authorization: "Bearer " + (localStorage.getItem("token") ?? "") };
}

/** kds.js:54-78 — loadOrders(): GET /api/business/orders/active. */
export async function loadOrders(): Promise<KdsOrder[]> {
  try {
    const response = await fetch(`/api/business/orders/active`, {
      headers: authHeaders(),
    });

    if (response.status === 404) {
      // kds.js:55-57
      throw new RestaurantNotFoundError();
    }
    if (!response.ok) throw new Error("Error cargando pedidos");

    return (await response.json()) as KdsOrder[];
  } catch (e) {
    if (e instanceof RestaurantNotFoundError) throw e;
    // kds.js:76-77: el legacy solo loguea y sigue con la UI actual.
    console.error("Error cargando pedidos", e);
    throw e;
  }
}

/**
 * kds.js:264-277 — advanceOrder(orderId, status): PATCH del estado.
 * El legacy ignora la respuesta y reproduce el sonido de listo solo al
 * pasar a READY (kds.js:270-271); ese sonido lo maneja el llamador.
 */
export async function advanceOrder(orderId: number, status: string): Promise<boolean> {
  try {
    await fetch(`/api/orders/${orderId}/status?status=${status}`, {
      method: "PATCH",
      headers: authHeaders(),
    });
    return true;
  } catch (e) {
    // kds.js:276
    console.error("Error actualizando estado", e);
    return false;
  }
}

/** kds.js:527-545 — loadCancellationReasons(): GET /api/cancellation-reasons. */
export async function loadCancellationReasons(): Promise<CancellationReason[]> {
  try {
    const res = await fetch("/api/cancellation-reasons", {
      headers: authHeaders(),
    });
    return (await res.json()) as CancellationReason[];
  } catch (e) {
    // kds.js:543-544
    console.error("Error cargando motivos", e);
    return [];
  }
}

/**
 * kds.js:639-646 — PATCH /api/orders/{id}/cancel con { reasonId, note }.
 */
export async function cancelOrder(
  orderId: number,
  reasonId: number,
  note: string
): Promise<void> {
  await fetch(`/api/orders/${orderId}/cancel`, {
    method: "PATCH",
    headers: {
      ...authHeaders(),
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      reasonId: reasonId,
      note: note,
    }),
  });
}

/**
 * kds.js:313-320 — logout(): borra "token" y vuelve al login conservando
 * el restaurante (a diferencia del logout del dashboard, este SÍ conserva
 * el slug del legacy).
 */
export function logoutKds(): void {
  localStorage.removeItem("token");
  if (restaurantSlug) {
    window.location.href = `/login/login.html?restaurant=${restaurantSlug}`;
  } else {
    window.location.href = "/login/login.html";
  }
}
