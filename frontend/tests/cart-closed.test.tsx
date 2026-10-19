/**
 * CartPanel × local cerrado (follow-up de la revisión "se puede comprar con
 * el local cerrado"): la regla real vive en el backend (createOrder rechaza
 * con "El local está cerrado"), pero el cliente no puede enviarse a un 400
 * seguro — cuando el badge dice Cerrado, la confirmación se bloquea con el
 * copy y la próxima apertura si se puede computar. Armar el carrito sigue
 * permitido: mirar la carta no requiere el local abierto.
 */
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CartPanel from '../src/pages/menu/CartPanel'
import type { Cart } from '../src/pages/menu/types'

const PRODUCT = {
  id: '101',
  name: 'Milanesa',
  price: 100,
  imageUrl: 'img.jpg',
  categoryId: '1',
  categoryName: 'Platos',
  isCombo: false,
} as never

const CART: Cart = {
  '101': { product: PRODUCT, qty: 2, isCombo: false },
}

const baseProps = {
  cart: CART,
  total: 200,
  customerName: 'Cliente',
  customerPhone: '',
  orderType: 'RETIRO' as const,
  address: '',
  notes: '',
  paymentMethod: 'EFECTIVO' as const,
  sending: false,
  onCustomerNameChange: vi.fn(),
  onCustomerPhoneChange: vi.fn(),
  onOrderTypeChange: vi.fn(),
  onAddressChange: vi.fn(),
  onNotesChange: vi.fn(),
  onPaymentMethodChange: vi.fn(),
  onAddItem: vi.fn(),
  onRemoveItem: vi.fn(),
  onSubmit: vi.fn(),
}

describe('CartPanel con el local cerrado', () => {
  afterEach(cleanup)

  it('bloquea la confirmación y explica por qué cuando el local está cerrado', () => {
    render(<CartPanel {...baseProps} closed />)

    const submit = screen.getByRole('button', { name: 'Hacer el pedido' })
    expect(submit).toBeDisabled()
    expect(screen.getByText(/El local está cerrado/)).toBeInTheDocument()
  })

  it('muestra la próxima apertura cuando se puede computar', () => {
    render(<CartPanel {...baseProps} closed closedNotice="Abre hoy a las 11:00" />)

    expect(screen.getByText(/El local está cerrado/)).toBeInTheDocument()
    expect(screen.getByText(/Abre hoy a las 11:00/)).toBeInTheDocument()
  })

  it('deja la confirmación habilitada cuando el local está abierto', () => {
    render(<CartPanel {...baseProps} />)

    expect(screen.getByRole('button', { name: 'Hacer el pedido' })).toBeEnabled()
    expect(screen.queryByText(/El local está cerrado/)).not.toBeInTheDocument()
  })
})
