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
  optionNamesFor,
  posLineKey,
  unitPriceWithSelections,
  validatePosOrder,
  validatePosSelection,
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

  it('a SALON order needs no customer name: the table identifies the guest', () => {
    expect(
      validatePosOrder(form({ orderType: 'SALON', tableId: 3, customerName: '', customerPhone: '' }), [
        productLine(1),
      ])
  ).toBeNull()
  })

  it('a SALON payload carries no customer fields at all', () => {
    const payload = buildOrderRequest(
      form({ orderType: 'SALON', tableId: 3, customerName: '', customerPhone: '' }),
      [productLine(1)]
    )

    expect(payload).not.toHaveProperty('customerName')
    expect(payload).not.toHaveProperty('customerPhone')
    expect(payload.tableId).toBe(3)
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

/**
 * Option-group selection in the POS (transfer-payment-gate follow-up): the
 * catalog (GET /api/products → ProductDTO) already carries optionGroups and
 * the backend validates selectedOptionIds per item — the POS ignored both.
 * Pinned here: line identity (same product, different options = separate
 * lines), the ordered payload field, the unit price with surcharges and the
 * Spanish required-group validation.
 */
import type { ProductOptionGroup } from '../src/pages/menu/types'

const BREAD_GROUP: ProductOptionGroup = {
  name: 'Tipo de pan',
  required: true,
  multiple: false,
  options: [
    { id: 11, name: 'Blanco', priceDelta: 0 },
    { id: 12, name: 'Integral', priceDelta: 200 },
  ],
}

const EXTRAS_GROUP: ProductOptionGroup = {
  name: 'Extras',
  required: false,
  multiple: true,
  maxSelections: 2,
  options: [
    { id: 21, name: 'Cheddar', priceDelta: 500 },
    { id: 22, name: 'Bacon', priceDelta: 700 },
    { id: 23, name: 'Huevo', priceDelta: 400 },
  ],
}

const PRODUCT_WITH_GROUPS = {
  id: '5',
  name: 'Hamburguesa',
  price: 3200,
  isCombo: false,
  optionGroups: [BREAD_GROUP, EXTRAS_GROUP],
}

describe('POS option selection — line identity and display', () => {
  it('two lines of the same product with different options have different keys', () => {
    expect(posLineKey('5', [12])).not.toBe(posLineKey('5', [11]))
    expect(posLineKey('5', [])).toBe(posLineKey('5', []))
    expect(posLineKey('5', [12, 21])).toBe(posLineKey('5', [21, 12]))
  })

  it('the unit price adds every selected surcharge', () => {
    expect(unitPriceWithSelections(PRODUCT_WITH_GROUPS, [])).toBe(3200)
    expect(unitPriceWithSelections(PRODUCT_WITH_GROUPS, [12, 21, 22])).toBe(4600)
  })

  it('the option names render in group definition order', () => {
    expect(optionNamesFor(PRODUCT_WITH_GROUPS, [22, 12])).toEqual(['Integral', 'Bacon'])
  })
})

describe('POS option selection — required groups (Spanish copy)', () => {
  it('blocks adding while a required group has no selection', () => {
    expect(validatePosSelection(PRODUCT_WITH_GROUPS, [21])).toBe(
      'Elegí una opción de "Tipo de pan"'
    )
  })

  it('accepts a selection covering every required group', () => {
    expect(validatePosSelection(PRODUCT_WITH_GROUPS, [11, 22])).toBeNull()
  })

  it('a product without required groups always validates', () => {
    const product = { ...PRODUCT_WITH_GROUPS, optionGroups: [EXTRAS_GROUP] }
    expect(validatePosSelection(product, [])).toBeNull()
  })
})

describe('POS payload with option selections', () => {
  it('a line with selections carries selectedOptionIds as stored (already ordered by the line builder)', () => {
    // La línea se crea con orderedSelection (orden de definición de grupos);
    // el payload la transmite intacta.
    const items: PosCartItem[] = [
      { id: '5', name: 'Hamburguesa', price: 4600, isCombo: false, qty: 1, selectedOptionIds: [12, 22] },
    ]
    const payload = buildOrderRequest(form(), items)
    expect(payload.items[0].selectedOptionIds).toEqual([12, 22])
  })

  it('a line without selections sends no selectedOptionIds (same payload as today)', () => {
    const payload = buildOrderRequest(form(), [productLine(1)])
    expect(payload.items[0]).not.toHaveProperty('selectedOptionIds')
  })

  it('the sent price is the unit price with surcharges (catalog-truthful, inert)', () => {
    const items: PosCartItem[] = [
      { id: '5', name: 'Hamburguesa', price: 4600, isCombo: false, qty: 2, selectedOptionIds: [12, 22] },
    ]
    const payload = buildOrderRequest(form(), items)
    expect(payload.items[0].price).toBe(4600)
    expect(payload.items[0].subtotal).toBe(9200)
  })
})
