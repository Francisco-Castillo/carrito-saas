/**
 * Orders console page (T3 of odd/tasks/orders-console.md): S4 wants the FULL
 * day in view — every status, delivered and cancelled included, with the
 * cancellation reason visible on cancelled orders.
 *
 * Layout decision (documented in Orders.tsx and the ODD log): status filter
 * pills over one list, not kanban columns — five statuses with rich detail
 * cards don't fit side-by-side, and the "Todos" default keeps the whole day
 * on screen. The pills carry counts so the full-day view stays scannable.
 */
import { cleanup, render, screen, within } from '@testing-library/react'
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

const FIVE_STATUSES = [
  managementOrder({ orderId: 1, orderNumber: 101, status: 'NEW' }),
  managementOrder({ orderId: 2, orderNumber: 102, status: 'PREPARING' }),
  managementOrder({ orderId: 3, orderNumber: 103, status: 'READY' }),
  managementOrder({
    orderId: 4,
    orderNumber: 104,
    status: 'DELIVERED',
    timeline: { preparingAt: null, readyAt: null, completedAt: '2026-10-20T13:00:00', cancelledAt: null },
  }),
  managementOrder({
    orderId: 5,
    orderNumber: 105,
    status: 'CANCELLED',
    cancellationReason: { id: 7, description: 'Cliente se fue' },
    cancellationNote: 'Se arrepintió del pedido',
    timeline: { preparingAt: null, readyAt: null, completedAt: null, cancelledAt: '2026-10-20T12:30:00' },
  }),
]

function stubOrdersFetch(orders: unknown[]) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.startsWith('/api/business/orders')) {
      return { ok: true, status: 200, json: async () => orders }
    }
    return { ok: false, status: 500, json: async () => ({}) }
  })
}

beforeEach(() => {
  seedPanelSession()
})

afterEach(cleanup)

describe('orders page — full-day, all-status view (S4)', () => {
  it('renders all five status groups with counts in the filter pills', async () => {
    vi.stubGlobal('fetch', stubOrdersFetch(FIVE_STATUSES))

    render(<Orders />)

    const filters = await screen.findByRole('group', { name: 'Filtrar por estado' })
    for (const label of ['Todos', 'Nuevos', 'Preparando', 'Listos', 'Entregados', 'Cancelados']) {
      expect(within(filters).getByText(new RegExp(label))).toBeTruthy()
    }
    // Counts: every status is represented in the day view.
    expect(within(filters).getByText(/5/)).toBeTruthy()
  })

  it('shows delivered and cancelled orders too, cancelled with its reason', async () => {
    vi.stubGlobal('fetch', stubOrdersFetch(FIVE_STATUSES))

    render(<Orders />)

    // Delivered order card is on screen (S4: Entregados incluidos).
    expect(await screen.findByText('#104')).toBeTruthy()
    // Cancelled card shows the backend reason and the note.
    expect(await screen.findByText('#105')).toBeTruthy()
    expect(await screen.findByText(/Cliente se fue/)).toBeTruthy()
    expect(await screen.findByText(/Se arrepintió del pedido/)).toBeTruthy()
  })

  it('the Cancelados filter shows only the cancelled order', async () => {
    vi.stubGlobal('fetch', stubOrdersFetch(FIVE_STATUSES))
    const user = userEvent.setup()

    render(<Orders />)

    const filters = await screen.findByRole('group', { name: 'Filtrar por estado' })
    await user.click(within(filters).getByRole('button', { name: /Cancelados/ }))

    expect(screen.getByText('#105')).toBeTruthy()
    expect(screen.queryByText('#101')).toBeNull()
    expect(screen.queryByText('#104')).toBeNull()
  })
})
