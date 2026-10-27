/**
 * Puerto 1:1 de las llamadas al backend del legacy kds/kds.js (mismas rutas,
 * mismos headers Authorization Bearer con el token localStorage "token",
 * mismo manejo de errores).
 *
 * Fase 2 (login orgánico): el slug del restaurante se resuelve desde la
 * sesión (localStorage "restaurantSlug", escrito por el login), no de la
 * URL, y el logout vuelve al login limpio (sin ?restaurant=).
 */

import { getSessionRestaurantSlug } from "../dashboard/auth";
import { showToast } from "./toast";

/** Snapshot crudo de opción del backend (OrderItemDTO.selectedOptions). */
export interface KdsSelectedOption {
  groupName?: string;
  optionId?: number;
  optionName?: string;
  priceDelta?: number;
}

export interface KdsOrderItem {
  quantity: number;
  productName: string;
  /**
   * Opciones elegidas del ítem (odd/tasks/product-options.md S5), como
   * "Grupo: Opción" resueltas por el backend. Vacío = producto sin opciones.
   *
   * Contrato de render: SIEMPRE strings. El payload crudo puede llegar en
   * dos formas (strings por REST /orders/active, snapshot de objetos por el
   * WebSocket) — normalizeSelectedOptions/normalizeKdsOrder lo dejan en
   * strings antes de que llegue a la tarjeta.
   */
  selectedOptions?: string[];
  /** Preferencias del cliente ("sin mayonesa"): cocina las ve en la tarjeta. */
  notes?: string | null;
}

/**
 * Normaliza las opciones de un ítem al formato de cocina "Grupo: Opción"
 * (mismo formato que OrderKitchenMapperImpl del backend). Acepta ambos
 * shapes que emite el backend: strings ya resueltos (REST /orders/active)
 * y el snapshot de objetos {groupName, optionId, optionName, priceDelta}
 * (eventos del WebSocket en OrderDTO). Renderizar el snapshot como child
 * de React rompe el tablero completo ("Objects are not valid as a React
 * child") apenas el eco del cambio de estado vuelve por el WebSocket.
 */
export function normalizeSelectedOptions(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map((opt) => {
    if (typeof opt === "string") return opt;
    const o = (opt ?? {}) as KdsSelectedOption;
    const group = typeof o.groupName === "string" ? o.groupName.trim() : "";
    const name = typeof o.optionName === "string" ? o.optionName.trim() : "";
    return group && name ? `${group}: ${name}` : name || group;
  });
}

/**
 * Copia del pedido con las opciones de cada ítem normalizadas a strings.
 * Punto único de ingestión del KDS: REST (loadOrders) y WebSocket
 * (handleOrderEvent) pasan por acá antes de tocar el estado.
 */
export function normalizeKdsOrder(order: KdsOrder): KdsOrder {
  return {
    ...order,
    items: (order.items ?? []).map((item) => ({
      ...item,
      selectedOptions: normalizeSelectedOptions(
        (item as { selectedOptions?: unknown }).selectedOptions
      ),
    })),
  };
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

/**
 * Señal de sesión muerta (odd/tasks/kds-session-errors.md S2/S3): un 401
 * (token vencido/inválido) o 403 (rol revocado) en CUALQUIER llamada del
 * KDS. El guard de sesión corre una sola vez al montar, así que una sesión
 * que muere en pleno turno sólo se descubre acá. La señal es explícita para
 * que la página la traduzca en "limpiar sesión y volver al login", en vez
 * del catch histórico que se tragaba el error y dejaba columnas vacías
 * ("pantalla muerta").
 */
export class UnauthenticatedError extends Error {
  constructor() {
    super("Sesión no válida");
  }
}

function isSessionRejected(status: number): boolean {
  return status === 401 || status === 403;
}

// kds.js:3-9: sin slug el legacy reemplaza el body con
// "Restaurante no especificado" y corta la ejecución. En el puerto el slug
// viene de la sesión y se lee SIN efectos secundarios (sin navegación en el
// scope de módulo: el bundle SPA importa estáticamente todos los módulos de
// página). El redirect del panel sin slug vive en la página (Kds gated por
// hasPanelSession + enforceSession).
export function getRestaurantSlug(): string | null {
  return getSessionRestaurantSlug();
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
    if (isSessionRejected(response.status)) {
      throw new UnauthenticatedError();
    }
    if (!response.ok) throw new Error("Error cargando pedidos");

    // Opciones del ítem: strings "Grupo: Opción" ya resueltos, se pasan por
    // el normalizador igual que el WebSocket (misma forma de ingesta).
    return (await response.json() as KdsOrder[]).map(normalizeKdsOrder);
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
/**
 * kds.js:264-277 — advanceOrder(orderId, status): PATCH del estado.
 *
 * El backend responde 409 con el detalle cuando la transición es inválida
 * (fix fb07a32: board desactualizado, doble click). El fallo NO se traga:
 * llega a la cocina como toast warning con el mensaje del servidor, y la
 * función reporta false para que el caller re-sincronice la tarjeta.
 */
export async function advanceOrder(orderId: number, status: string): Promise<boolean> {
  try {
    const response = await fetch(`/api/orders/${orderId}/status?status=${status}`, {
      method: "PATCH",
      headers: authHeaders(),
    });
    if (isSessionRejected(response.status)) {
      // Sesión muerta en pleno turno: la señal sube sin toast genérico que
      // la disimule (S3); el caller la traduce en fatal.
      throw new UnauthenticatedError();
    }
    if (!response.ok) {
      let message = "No se pudo actualizar el pedido";
      try {
        const data = (await response.json()) as { message?: string };
        if (data.message && data.message.trim() !== "") message = data.message;
      } catch {
        // cuerpo sin JSON: queda el mensaje genérico
      }
      showToast(message, "warning");
      return false;
    }
    return true;
  } catch (e) {
    if (e instanceof UnauthenticatedError) throw e;
    // kds.js:276
    console.error("Error actualizando estado", e);
    showToast("No se pudo actualizar el pedido", "warning");
    return false;
  }
}

/** kds.js:527-545 — loadCancellationReasons(): GET /api/cancellation-reasons. */
export async function loadCancellationReasons(): Promise<CancellationReason[]> {
  try {
    const res = await fetch("/api/cancellation-reasons", {
      headers: authHeaders(),
    });
    if (isSessionRejected(res.status)) {
      throw new UnauthenticatedError();
    }
    return (await res.json()) as CancellationReason[];
  } catch (e) {
    if (e instanceof UnauthenticatedError) throw e;
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
  const response = await fetch(`/api/orders/${orderId}/cancel`, {
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
  if (isSessionRejected(response.status)) {
    throw new UnauthenticatedError();
  }
}

/**
 * kds.js:313-320 — logout(): borra "token" y vuelve al login. Fase 2: SIN
 * ?restaurant= — el slug vive en la sesión y el login orgánico lo resuelve.
 * La sesión incluye el slug del restaurante: se limpia junto con el token.
 */
export function logoutKds(): void {
  localStorage.removeItem("token");
  localStorage.removeItem("restaurantSlug");
  window.location.href = "/login";
}
