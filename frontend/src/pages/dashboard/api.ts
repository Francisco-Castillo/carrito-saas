/**
 * Puerto 1:1 del legacy dashboard/apiClient.js (mismas rutas — el legacy no
 * mandaba header, confiaba en la cookie/servidor) con un ajuste: los
 * endpoints de negocio exigen JWT, así que cada request lleva
 * Authorization: Bearer + token de localStorage leído en caliente, igual
 * que src/pages/admin/api.ts. Manejo de errores igual que el legacy:
 * cualquier !ok lanza "API error" y la UI conserva sus valores iniciales.
 *
 * Fase 2 (login orgánico): el slug del restaurante se resuelve desde la
 * sesión (localStorage "restaurantSlug", escrito por el login), no de la
 * URL — el panel funciona con una URL limpia. La lectura en el scope de
 * módulo es solo LECTURA (getSessionRestaurantSlug): sin efectos secundarios
 * y sin navegación — el bundle SPA importa estáticamente todos los módulos
 * de página (incluido el login), así que un redirect en el scope de módulo
 * re-cargaba /login/login.html en bucle infinito. El redirect del panel sin
 * slug vive en la página (hasPanelSession + enforceSession).
 */

import { getSessionRestaurantSlug } from "./auth";

export interface DashboardToday {
  revenue: number;
  avgTicket: number;
  orders: number;
  avgPrepTime: number;
}

export interface OrderStatusSummary {
  NEW?: number;
  PREPARING?: number;
  READY?: number;
}

export interface TopProduct {
  productName: string;
  quantity: number;
}

export interface SalesByHour {
  hour: string;
  revenue: number;
}

// Lectura simple del slug de sesión, SIN efectos secundarios: la página
// (Dashboard gated por hasPanelSession) garantiza que no hay fetch sin slug;
// aquí solo se resuelve el valor para construir la base de la API.
const restaurantSlug = getSessionRestaurantSlug();

const API_BASE = `/api/restaurants/${restaurantSlug}`;

/** Header Authorization: Bearer + token de localStorage en caliente. */
function authHeader(): Record<string, string> {
  return { Authorization: "Bearer " + localStorage.getItem("token") };
}

async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, { headers: authHeader() });

  if (!res.ok) {
    throw new Error("API error");
  }

  return (await res.json()) as T;
}

export const api = {
  getDashboardToday(): Promise<DashboardToday> {
    return apiGet("/dashboard/today");
  },

  getOrderStatusSummary(): Promise<OrderStatusSummary> {
    return apiGet("/orders/status-summary");
  },

  getTopProducts(): Promise<TopProduct[]> {
    return apiGet("/analytics/top-products");
  },

  getSalesByHour(): Promise<SalesByHour[]> {
    return apiGet("/analytics/sales-by-hour");
  },
};
