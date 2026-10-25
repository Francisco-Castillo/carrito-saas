/**
 * POS flow under the transfer payment gate (odd/tasks/transfer-payment-gate.md,
 * L5): the counter POS creates its order through POST /api/orders/menu/{slug}.
 * Since every TRANSFERENCIA order is born PENDING_PAYMENT, a counter order
 * would demand a second confirmation click even though the operator just
 * verified the transfer in person — so the POS chains the confirmation
 * (PATCH status=NEW) right after creating. If the chained confirmation
 * fails, the order stays in "Pago pendiente" and the console can confirm it.
 */
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Orders from '../src/pages/Orders'
import { managementOrder, seedPanelSession } from './helpers/ordersFixtures'

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    activate(): void {}
    deactivate(): void {}
    subscribe(): void {}
    publish(): void {}
  },
}))

function stubPosFetch(productOverrides: Record<string, unknown> = {}) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.startsWith('/api/business/orders')) {
      return { ok: true, status: 200, json: async () => [] }
    }
    if (url === '/api/products') {
      return {
        ok: true,
        status: 200,
        json: async () => [
          { id: 5, name: 'Hamburguesa', price: 3200, active: true, ...productOverrides },
        ],
      }
    }
    if (url === '/api/combos') {
      return { ok: true, status: 200, json: async () => [] }
    }
    if (url === '/api/tables') {
      return { ok: true, status: 200, json: async () => ({ content: [] }) }
    }
    if (url === '/api/orders/menu/demo-pizzeria' && init?.method === 'POST') {
      return {
        ok: true,
        status: 200,
        json: async () => ({ orderId: 33, orderNumber: 133, status: 'PENDING_PAYMENT' }),
      }
    }
    return { ok: false, status: 500, json: async () => ({}) }
  })
}

beforeEach(() => {
  seedPanelSession()
})

afterEach(cleanup)

async function createPosTransferOrder(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  render(<Orders />)
  await user.click(await screen.findByRole('button', { name: '+ Nuevo pedido' }))

  await user.click(await screen.findByRole('button', { name: /Hamburguesa/ }))
  await user.type(screen.getByPlaceholderText('Nombre y apellido (obligatorio)'), 'Cliente Mostrador')
  await user.click(screen.getByRole('button', { name: 'Transferencia' }))
  await user.click(screen.getByRole('button', { name: 'Crear pedido' }))
}

describe('POS + transfer payment gate', () => {
  it('a counter TRANSFERENCIA order is confirmed right after creating (no second click)', async () => {
    const fetchMock = stubPosFetch()
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    await createPosTransferOrder(user)

    const post = fetchMock.mock.calls.find(
      ([url, init]) => String(url) === '/api/orders/menu/demo-pizzeria' && init?.method === 'POST'
    )
    expect(post).toBeTruthy()
    expect(JSON.parse(String(post![1]!.body)).paymentMethod).toBe('TRANSFERENCIA')

    const patch = fetchMock.mock.calls.find(
      ([url, init]) =>
        String(url) === '/api/orders/33/status?status=NEW' && init?.method === 'PATCH'
    )
    expect(patch, 'the POS must chain the payment confirmation').toBeTruthy()
  })

  it('an EFECTIVO counter order does not chain any confirmation PATCH', async () => {
    const fetchMock = stubPosFetch()
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<Orders />)
    await user.click(await screen.findByRole('button', { name: '+ Nuevo pedido' }))
    await user.click(await screen.findByRole('button', { name: /Hamburguesa/ }))
    await user.type(screen.getByPlaceholderText('Nombre y apellido (obligatorio)'), 'Cliente Mostrador')
    await user.click(screen.getByRole('button', { name: 'Crear pedido' }))

    const patches = fetchMock.mock.calls.filter(
      ([, init]) => init?.method === 'PATCH'
    )
    expect(patches).toHaveLength(0)
  })
})

/**
 * Option-group selection step in the POS: a product WITH optionGroups does
 * not add instantly — it opens the selection step first (required groups are
 * Spanish-validated before "Agregar"), and the created line carries
 * selectedOptionIds. Products without groups keep the one-tap add.
 */
const OPTION_GROUPS = [
  {
    name: 'Tipo de pan',
    required: true,
    multiple: false,
    options: [
      { id: 11, name: 'Blanco', priceDelta: 0 },
      { id: 12, name: 'Integral', priceDelta: 200 },
    ],
  },
  {
    name: 'Extras',
    required: false,
    multiple: true,
    maxSelections: 2,
    options: [{ id: 21, name: 'Cheddar', priceDelta: 500 }],
  },
]

describe('POS option selection step', () => {
  it('a product with optionGroups opens the selection step and sends selectedOptionIds', async () => {
    const fetchMock = stubPosFetch({ optionGroups: OPTION_GROUPS })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<Orders />)
    await user.click(await screen.findByRole('button', { name: '+ Nuevo pedido' }))
    await user.click(await screen.findByRole('button', { name: /Hamburguesa/ }))

    // The selection step opens instead of adding a plain line.
    expect(await screen.findByText('Tipo de pan')).toBeTruthy()
    await user.click(screen.getByRole('radio', { name: /Integral/ }))
    await user.click(screen.getByRole('checkbox', { name: /Cheddar/ }))
    await user.click(screen.getByRole('button', { name: /Agregar al pedido/ }))

    // Back to the cart view: the line shows the chosen options and the form
    // is available again for the rest of the order.
    expect(await screen.findByText('Integral, Cheddar')).toBeTruthy()
    await user.type(screen.getByPlaceholderText('Nombre y apellido (obligatorio)'), 'Cliente Mostrador')
    await user.click(screen.getByRole('button', { name: 'Crear pedido' }))

    const post = fetchMock.mock.calls.find(
      ([url, init]) => String(url) === '/api/orders/menu/demo-pizzeria' && init?.method === 'POST'
    )
    expect(post).toBeTruthy()
    const body = JSON.parse(String(post![1]!.body))
    expect(body.items[0].selectedOptionIds).toEqual([12, 21])
    // Price is the unit price with surcharges (3200 + 200 + 500).
    expect(body.items[0].price).toBe(3900)
  })

  it('a required group without selection blocks the add with the Spanish message', async () => {
    const fetchMock = stubPosFetch({ optionGroups: OPTION_GROUPS })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<Orders />)
    await user.click(await screen.findByRole('button', { name: '+ Nuevo pedido' }))
    await user.click(await screen.findByRole('button', { name: /Hamburguesa/ }))

    await screen.findByText('Tipo de pan')
    await user.click(screen.getByRole('checkbox', { name: /Cheddar/ }))
    await user.click(screen.getByRole('button', { name: /Agregar al pedido/ }))

    // The add is blocked client-side with the Spanish message; the selection
    // step stays open and no POST leaves the panel.
    expect(await screen.findByText('Elegí una opción de "Tipo de pan"')).toBeTruthy()
    expect(
      fetchMock.mock.calls.filter(([url, init]) => String(url) === '/api/orders/menu/demo-pizzeria')
    ).toHaveLength(0)
  })

  it('a product without optionGroups keeps the one-tap add (no selection step)', async () => {
    const fetchMock = stubPosFetch()
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<Orders />)
    await user.click(await screen.findByRole('button', { name: '+ Nuevo pedido' }))
    await user.click(await screen.findByRole('button', { name: /Hamburguesa/ }))

    expect(screen.queryByText('Tipo de pan')).toBeNull()
    await user.type(screen.getByPlaceholderText('Nombre y apellido (obligatorio)'), 'Cliente Mostrador')
    await user.click(screen.getByRole('button', { name: 'Crear pedido' }))

    const post = fetchMock.mock.calls.find(
      ([url, init]) => String(url) === '/api/orders/menu/demo-pizzeria' && init?.method === 'POST'
    )
    expect(post).toBeTruthy()
    expect(JSON.parse(String(post![1]!.body)).items[0]).not.toHaveProperty('selectedOptionIds')
  })
})
