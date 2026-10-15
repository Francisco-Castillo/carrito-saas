/**
 * Puerto 1:1 del legacy dashboard/apiClient.js (mismas rutas — el legacy no
 * mandaba header, confiaba en la cookie/servidor) con un ajuste: los
 * endpoints de negocio exigen JWT, así que cada request lleva
 * Authorization: Bearer + token de localStorage leído en caliente, igual
 * que src/pages/admin/api.ts. Manejo de errores igual que el legacy:
 * cualquier !ok lanza "API error" y la UI conserva sus valores iniciales.
 */

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

const params = new URLSearchParams(window.location.search);
const restaurantSlug = params.get("restaurant");

if (!restaurantSlug) {
  console.error("Restaurant slug missing in URL");
}

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
