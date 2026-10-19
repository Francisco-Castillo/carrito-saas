/**
 * Per-table QR attribution (feature pedido-qr-mesa, closes the gap left open
 * by odd/tasks/table-qr-links.md): a table QR opens the menu as
 * /menu/?table=<id>, the page must fetch that table's public identity
 * (GET /api/menu/table/{id}), FIX the order type to SALON (the type selector
 * disappears; the user chose "Fijar Salón"), show a badge naming the table
 * (the user chose "Badge con mesa"), and submit the order to
 * POST /api/orders/menu with orderType "SALON" and the table's id — the
 * attribution the kitchen and the POS already understand.
 *
 * Honest fallback: when ?table= is absent, invalid, or the table endpoint
 * answers 404, the menu falls back to today's behaviour (RETIRO/DELIVERY
 * selector, no badge, no tableId in the payload) — an old QR keeps working.
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CartPanel from '../src/pages/menu/CartPanel'
import type { Cart } from '../src/pages/menu/types'
import Menu from '../src/pages/Menu'

// Semana siempre abierta: createOrder respeta el horario efectivo del local.
const ALWAYS_OPEN_HOURS = [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({
  dayOfWeek,
  shifts: [
    { opensAt: '00:00', closesAt: '12:00' },
    { opensAt: '12:00', closesAt: '00:00' },
  ],
}))

const MENU_PAYLOAD = {
  business: { name: 'Local QR Mesa', whatsappNumber: '541155500011', logoUrl: null, primaryColor: '#7C3AED', hours: ALWAYS_OPEN_HOURS },
  products: [
    {
      id: 101,
      name: 'Milanesa',
      description: 'con papas',
      price: 100,
      categoryId: 1,
      categoryName: 'Platos',
      imageUrl: 'img.jpg',
    },
  ],
  combos: [],
}

const TABLE_PAYLOAD = { id: 5, tableNumber: 5, tableName: 'Salón principal' }

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data })

interface FetchCall {
  url: string
  method: string
  body?: string
}

function makeFetch(opts: { tableFailure?: 'http-404' | 'network' } = {}) {
  const calls: FetchCall[] = []
  const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const u = String(url instanceof Request ? url.url : url)
    const call: FetchCall = { url: u, method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : undefined }
    calls.push(call)

    if (u === '/api/menu') return okJson(MENU_PAYLOAD)
    if (u === '/api/menu/table/5') {
      if (opts.tableFailure === 'http-404') return { ok: false, status: 404, json: async () => ({}) }
      if (opts.tableFailure === 'network') throw new TypeError('Failed to fetch')
      return okJson(TABLE_PAYLOAD)
    }
    if (u === '/api/orders/menu') return okJson({ id: 1 })
    throw new Error('unexpected fetch: ' + u)
  })
  return { calls, fetchMock }
}

function setSearch(search: string) {
  window.history.replaceState(null, '', search === '' ? '/' : `/${search}`)
}

function renderMenu(fetchMock: ReturnType<typeof makeFetch>['fetchMock']) {
  vi.stubGlobal('alert', vi.fn())
  vi.stubGlobal('fetch', fetchMock)
  render(<Menu />)
}

describe('Menu × QR de mesa (?table=<id>)', () => {
  afterEach(() => {
    cleanup()
    setSearch('')
    vi.unstubAllGlobals()
  })

  it('fija SALON con badge de mesa: el selector de tipo desaparece y el pedido lleva orderType SALON + tableId', async () => {
    const user = userEvent.setup()
    const { calls, fetchMock } = makeFetch()
    setSearch('?table=5')
    renderMenu(fetchMock)

    // The page fetches the table's public identity from the new endpoint.
    await waitFor(() => expect(calls.some((call) => call.url === '/api/menu/table/5')).toBe(true))

    await screen.findByText('Milanesa')
    await user.click(screen.getByRole('button', { name: 'Agregar Milanesa' }))
    await user.click(screen.getByRole('button', { name: 'Ver pedido' }))

    // UI decisions BEFORE submitting: the sheet is open now (it closes on
    // success, so post-submit assertions would miss it).
    expect(screen.getByText(/Salón principal/)).toBeInTheDocument()
    expect(screen.getByText(/Mesa 5/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Tipo de pedido')).not.toBeInTheDocument()
    // S4: a table order is paid at the table — no payment method UI at all.
    expect(screen.queryByRole('group', { name: 'Forma de pago' })).not.toBeInTheDocument()
    // Verifier probe 3: no payment buttons may exist either, not just the group.
    expect(screen.queryByRole('button', { name: 'Efectivo' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Transferencia' })).not.toBeInTheDocument()

    await user.type(screen.getByPlaceholderText('Nombre y Apellido'), 'Cliente de Mesa')
    await user.type(screen.getByPlaceholderText('Celular (ej. 11 5555 1234)'), '11 5555 1234')
    await user.click(screen.getByRole('button', { name: 'Hacer el pedido' }))

    await waitFor(() => {
      const orderCall = calls.find((call) => call.url === '/api/orders/menu')
      expect(orderCall).toBeDefined()
      const payload = JSON.parse(orderCall!.body!)
      expect(payload.orderType).toBe('SALON')
      expect(payload.tableId).toBe(5)
      expect(payload.customerName).toBe('Cliente de Mesa')
      // S4: no payment method leaves the browser for a table order.
      expect(payload.paymentMethod).toBeUndefined()
    })
  })

  it('fallback honesto: mesa que no resuelve (404) vuelve al menú normal sin tableId', async () => {
    const user = userEvent.setup()
    const { calls, fetchMock } = makeFetch({ tableFailure: 'http-404' })
    setSearch('?table=5')
    renderMenu(fetchMock)

    await waitFor(() => expect(calls.some((call) => call.url === '/api/menu/table/5')).toBe(true))

    await screen.findByText('Milanesa')
    await user.click(screen.getByRole('button', { name: 'Agregar Milanesa' }))
    await user.click(screen.getByRole('button', { name: 'Ver pedido' }))

    // UI fallback BEFORE submitting: the legacy selector is back, no badge.
    expect(screen.getByLabelText('Tipo de pedido')).toBeInTheDocument()
    expect(screen.queryByText(/Salón principal/)).not.toBeInTheDocument()

    await user.type(screen.getByPlaceholderText('Nombre y Apellido'), 'Cliente de Mesa')
    await user.type(screen.getByPlaceholderText('Celular (ej. 11 5555 1234)'), '11 5555 1234')
    await user.click(screen.getByRole('button', { name: 'Hacer el pedido' }))

    await waitFor(() => {
      const orderCall = calls.find((call) => call.url === '/api/orders/menu')
      expect(orderCall).toBeDefined()
      const payload = JSON.parse(orderCall!.body!)
      // Legacy behaviour, byte for byte: RETIRO, no table field at all.
      expect(payload.orderType).toBe('RETIRO')
      expect(payload.tableId).toBeUndefined()
    })
  })
})

describe('fallback: parámetro ?table= inválido o ausente', () => {
  afterEach(() => {
    cleanup()
    setSearch('')
    vi.unstubAllGlobals()
  })

  // Parámetros que NO deben disparar el fetch de mesa: ausente, vacío, no
  // numérico, cero, negativo o decimal (Number.isInteger + id > 0).
  const INVALID_SEARCHES = ['', '?table=', '?table=abc', '?table=0', '?table=-3', '?table=1.5']

  for (const search of INVALID_SEARCHES) {
    it(`"${search || '(sin query)'}" carga el menú legacy: sin fetch de mesa, selector presente y pedido RETIRO sin tableId`, async () => {
      const user = userEvent.setup()
      const { calls, fetchMock } = makeFetch()
      setSearch(search)
      // stub BEFORE render: the load effect fetches immediately.
      vi.stubGlobal('alert', vi.fn())
      vi.stubGlobal('fetch', fetchMock)
      render(<Menu />)

      await screen.findByText('Milanesa')
      // No table fetch may ever happen for an invalid/missing param.
      expect(calls.some((call) => call.url.startsWith('/api/menu/table/'))).toBe(false)

      await user.click(screen.getByRole('button', { name: 'Agregar Milanesa' }))
      await user.click(screen.getByRole('button', { name: 'Ver pedido' }))

      // UI BEFORE submitting: legacy selector, no badge (the sheet closes on
      // success, so post-submit assertions would miss it).
      expect(screen.getByLabelText('Tipo de pedido')).toBeInTheDocument()
      expect(screen.queryByText(/Salón principal/)).not.toBeInTheDocument()
      // Legacy mode keeps the payment method selector.
      expect(screen.getByRole('group', { name: 'Forma de pago' })).toBeInTheDocument()

      await user.type(screen.getByPlaceholderText('Nombre y Apellido'), 'Cliente Legacy')
      await user.type(screen.getByPlaceholderText('Celular (ej. 11 5555 1234)'), '11 5555 1234')
      await user.click(screen.getByRole('button', { name: 'Hacer el pedido' }))

      await waitFor(() => {
        const orderCall = calls.find((call) => call.url === '/api/orders/menu')
        expect(orderCall).toBeDefined()
        const payload = JSON.parse(orderCall!.body!)
        expect(payload.orderType).toBe('RETIRO')
        expect(payload.tableId).toBeUndefined()
        // Legacy payload keeps its payment method (default EFECTIVO).
        expect(payload.paymentMethod).toBe('EFECTIVO')
      })

      cleanup()
      vi.unstubAllGlobals()
    })
  }

  it('fallo de red en el fetch de la mesa: intenta /api/menu/table/5 y cae al pedido legacy', async () => {
    const user = userEvent.setup()
    const { calls, fetchMock } = makeFetch({ tableFailure: 'network' })
    setSearch('?table=5')
    vi.stubGlobal('alert', vi.fn())
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu />)

    // The table fetch WAS attempted...
    await waitFor(() => expect(calls.some((call) => call.url === '/api/menu/table/5')).toBe(true))

    await screen.findByText('Milanesa')
    await user.click(screen.getByRole('button', { name: 'Agregar Milanesa' }))
    await user.click(screen.getByRole('button', { name: 'Ver pedido' }))

    // ...but the fallback kicked in: legacy selector, no badge.
    expect(screen.getByLabelText('Tipo de pedido')).toBeInTheDocument()
    expect(screen.queryByText(/Salón principal/)).not.toBeInTheDocument()

    await user.type(screen.getByPlaceholderText('Nombre y Apellido'), 'Cliente Red')
    await user.type(screen.getByPlaceholderText('Celular (ej. 11 5555 1234)'), '11 5555 1234')
    await user.click(screen.getByRole('button', { name: 'Hacer el pedido' }))

    await waitFor(() => {
      const orderCall = calls.find((call) => call.url === '/api/orders/menu')
      expect(orderCall).toBeDefined()
      const payload = JSON.parse(orderCall!.body!)
      expect(payload.orderType).toBe('RETIRO')
      expect(payload.tableId).toBeUndefined()
    })
  })
})

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
  orderType: 'SALON' as const,
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

describe('CartPanel × mesa fija (badge)', () => {
  afterEach(cleanup)

  it('muestra el badge de la mesa, oculta el selector de tipo Y la forma de pago cuando la mesa viaja fija', () => {
    render(<CartPanel {...baseProps} table={TABLE_PAYLOAD} />)

    expect(screen.getByText(/Salón principal/)).toBeInTheDocument()
    expect(screen.getByText(/Mesa 5/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Tipo de pedido')).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Forma de pago' })).not.toBeInTheDocument()
  })

  it('sin mesa fija mantiene el selector, la forma de pago y no muestra ningún badge', () => {
    render(<CartPanel {...baseProps} />)

    expect(screen.getByLabelText('Tipo de pedido')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Forma de pago' })).toBeInTheDocument()
    expect(screen.queryByText(/Mesa 5/)).not.toBeInTheDocument()
  })
})
