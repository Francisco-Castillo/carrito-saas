/**
 * Puerto 1:1 del legacy dashboard/apiClient.js (mismas rutas, mismo
 * Authorization — el legacy no manda header, confía en la cookie/servidor —
 * y mismo manejo de errores: cualquier !ok lanza "API error").
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

async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);

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
