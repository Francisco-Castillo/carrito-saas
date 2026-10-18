/**
 * POS payload contract (T4 of odd/tasks/orders-console.md): the counter POS
 * creates its order through the EXISTING authenticated menu endpoint
 * (POST /api/orders/menu/{slug}), so the item shape follows the OrderItemDTO
 * the backend already consumes — productId/comboId + quantity, plus the
 * price/subtotal the DTO carries (the backend recomputes them from the
 * catalog, the sent values are catalog-truthful and inert).
 *
 * Pinned here:
 *   - product lines carry `productId`, combo lines carry `comboId`
 *   - every line carries quantity, price and subtotal (price * quantity)
 *   - `tableId` travels ONLY for SALON (T2: SALON requires a table of the
 *     business; RETIRO/DELIVERY never set one)
 *   - `customerAddress` travels only for DELIVERY (same convention as the
 *     public menu flow)
 *   - the Spanish client-side validation blocks the empty-cart, missing
 *     name, missing SALON table and missing DELIVERY address before any
 *     request is sent
 */
import { describe, expect, it } from 'vitest'
import {
  buildOrderRequest,
  validatePosOrder,
  type PosCartItem,
  type PosOrderForm,
} from '../src/pages/orders/pos'

function form(overrides: Partial<PosOrderForm> = {}): PosOrderForm {
  return {
    orderType: 'RETIRO',
    tableId: null,
    customerName: 'Cliente Test',
    customerPhone: '1155551234',
    customerAddress: '',
    paymentMethod: 'EFECTIVO',
    notes: '',
    ...overrides,
  }
}

function productLine(qty = 2): PosCartItem {
  return { id: '5', name: 'Hamburguesa', price: 3200, isCombo: false, qty }
}

function comboLine(qty = 1): PosCartItem {
  return { id: 'combo-3', name: 'Combo familiar', price: 8900, isCombo: true, qty }
}

describe('POS item building for POST /api/orders/menu/{slug}', () => {
  it('a product line carries productId, quantity, price and subtotal', () => {
    const payload = buildOrderRequest(form(), [productLine(2)])

    expect(payload.items).toHaveLength(1)
    expect(payload.items[0]).toEqual({
      productId: 5,
      quantity: 2,
      price: 3200,
      subtotal: 6400,
    })
    expect(payload.items[0]).not.toHaveProperty('comboId')
  })

  it('a combo line carries comboId (stripped of the menu id prefix), quantity, price and subtotal', () => {
    const payload = buildOrderRequest(form(), [comboLine(1)])

    expect(payload.items).toHaveLength(1)
    expect(payload.items[0]).toEqual({
      comboId: 3,
      quantity: 1,
      price: 8900,
      subtotal: 8900,
    })
    expect(payload.items[0]).not.toHaveProperty('productId')
  })

  it('SALON sends tableId alongside the rest of the order data', () => {
    const payload = buildOrderRequest(form({ orderType: 'SALON', tableId: 12 }), [productLine(1)])

    expect(payload.orderType).toBe('SALON')
    expect(payload.tableId).toBe(12)
  })

  it('RETIRO never sends tableId, even if a stale selection is left in the form', () => {
    const payload = buildOrderRequest(form({ orderType: 'RETIRO', tableId: 12 }), [productLine(1)])
    const delivery = buildOrderRequest(form({ orderType: 'DELIVERY', tableId: 12 }), [productLine(1)])

    expect(payload).not.toHaveProperty('tableId')
    expect(delivery).not.toHaveProperty('tableId')
  })

  it('DELIVERY sends customerAddress; RETIRO and SALON do not', () => {
    const delivery = buildOrderRequest(
      form({ orderType: 'DELIVERY', customerAddress: 'Calle 1, 123' }),
      [productLine(1)]
    )

    expect(delivery.customerAddress).toBe('Calle 1, 123')
    expect(buildOrderRequest(form(), [productLine(1)])).not.toHaveProperty('customerAddress')
    expect(
      buildOrderRequest(form({ orderType: 'SALON', tableId: 1 }), [productLine(1)])
    ).not.toHaveProperty('customerAddress')
  })

  it('carries the customer, payment method and notes fields the backend requires', () => {
    const payload = buildOrderRequest(
      form({
        customerName: 'Ana',
        customerPhone: '1155551234',
        paymentMethod: 'TRANSFERENCIA',
        notes: 'sin cebolla',
      }),
      [productLine(1)]
    )

    expect(payload.customerName).toBe('Ana')
    expect(payload.customerPhone).toBe('1155551234')
    expect(payload.paymentMethod).toBe('TRANSFERENCIA')
    expect(payload.notes).toBe('sin cebolla')
  })
})

describe('POS client-side validation (Spanish copy)', () => {
  it('blocks an empty cart', () => {
    expect(validatePosOrder(form(), [])).toBe('Agrega al menos un producto al pedido')
  })

  it('blocks a missing customer name', () => {
    expect(validatePosOrder(form({ customerName: '   ' }), [productLine(1)])).toBe(
      'El nombre del cliente es obligatorio'
    )
  })

  it('blocks a SALON order without a table', () => {
    expect(validatePosOrder(form({ orderType: 'SALON', tableId: null }), [productLine(1)])).toBe(
      'Selecciona una mesa para el pedido de salón'
    )
  })

  it('blocks a DELIVERY order without an address', () => {
    expect(validatePosOrder(form({ orderType: 'DELIVERY' }), [productLine(1)])).toBe(
      'Ingresa la dirección para el delivery'
    )
  })

  it('accepts a complete RETIRO order', () => {
    expect(validatePosOrder(form(), [productLine(1)])).toBeNull()
  })
})
