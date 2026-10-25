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

function stubPosFetch() {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.startsWith('/api/business/orders')) {
      return { ok: true, status: 200, json: async () => [] }
    }
    if (url === '/api/products') {
      return {
        ok: true,
        status: 200,
        json: async () => [{ id: 5, name: 'Hamburguesa', price: 3200, active: true }],
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
