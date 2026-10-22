/**
 * Shared fixture builder for the orders console tests: a ManagementOrder
 * shaped exactly like the backend OrderManagementDTO (T1+T2 contract,
 * commit 68dc55d).
 */
import type { ManagementOrder } from '../../src/pages/orders/api'

export function managementOrder(overrides: Partial<ManagementOrder> = {}): ManagementOrder {
  return {
    orderId: 1,
    orderNumber: 101,
    orderType: 'RETIRO',
    status: 'NEW',
    customerName: 'Cliente Test',
    customerPhone: '1155551234',
    customerAddress: null,
    paymentMethod: 'EFECTIVO',
    notes: null,
    table: null,
    total: 6400,
    createdAt: '2026-10-20T12:00:00',
    items: [{ name: 'Hamburguesa', quantity: 2, unitPrice: 3200, subtotal: 6400 }],
    timeline: { preparingAt: null, readyAt: null, completedAt: null, cancelledAt: null },
    cancellationReason: null,
    cancellationNote: null,
    ...overrides,
  }
}

/** Session for a panel view: usable token + readable restaurant slug. */
export function seedPanelSession(): void {
  // Token con exp futura: el panel llama checkAuth() (isTokenExpired) al
  // cargar — un token indigerible dispararía logout() y limpiaría la sesión
  // a mitad del render, con la UI en su estado post-logout.
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))
  window.localStorage.setItem('token', `header.${payload}.signature`)
  window.localStorage.setItem('restaurantSlug', 'demo-pizzeria')
}
