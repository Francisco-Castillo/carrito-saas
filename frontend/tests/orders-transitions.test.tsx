/**
 * Orders console transition wiring (T3): the action buttons must respect the
 * backend transition graph (NEW→PREPARING|CANCELLED, PREPARING→READY|
 * CANCELLED, READY→DELIVERED) by only ever offering valid PATCH targets, and
 * a 409 rejection must surface the server message as a toast and re-sync the
 * board with a fresh GET (the KDS contract, fix fb07a32, ported to the
 * console).
 */
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Orders from '../src/pages/Orders'
import { KDS_TOAST_EVENT, type KdsToastDetail } from '../src/pages/kds/toast'
import { managementOrder, seedPanelSession } from './helpers/ordersFixtures'

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    activate(): void {}
    deactivate(): void {}
    subscribe(): void {}
    publish(): void {}
  },
}))

function stubOrdersFetch(orders: unknown[]) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.startsWith('/api/business/orders')) {
      return { ok: true, status: 200, json: async () => orders }
    }
    return { ok: false, status: 500, json: async () => ({}) }
  })
}

function fireToasts(): KdsToastDetail[] {
  const seen: KdsToastDetail[] = []
  const listener = (e: Event) => seen.push((e as CustomEvent<KdsToastDetail>).detail)
  window.addEventListener(KDS_TOAST_EVENT, listener)
  return seen
}

beforeEach(() => {
  seedPanelSession()
})

afterEach(cleanup)

describe('orders page — transition actions', () => {
  it('a NEW order PATCHes status=PREPARING when the cook starts it', async () => {
    const fetchMock = stubOrdersFetch([managementOrder({ orderId: 7, orderNumber: 7, status: 'NEW' })])
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<Orders />)
    await user.click(await screen.findByRole('button', { name: 'Empezar a preparar' }))

    const patch = fetchMock.mock.calls.find(
      ([url, init]) =>
        String(url) === '/api/orders/7/status?status=PREPARING' && init?.method === 'PATCH'
    )
    expect(patch).toBeTruthy()
  })

  it('a READY order PATCHes status=DELIVERED when it is handed over', async () => {
    const fetchMock = stubOrdersFetch([managementOrder({ orderId: 9, orderNumber: 9, status: 'READY' })])
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<Orders />)
    await user.click(await screen.findByRole('button', { name: 'Marcar entregado' }))

    const patch = fetchMock.mock.calls.find(
      ([url, init]) =>
        String(url) === '/api/orders/9/status?status=DELIVERED' && init?.method === 'PATCH'
    )
    expect(patch).toBeTruthy()
  })

  it('terminal orders offer no transition buttons (invalid transitions are prevented in the UI)', async () => {
    vi.stubGlobal(
      'fetch',
      stubOrdersFetch([
        managementOrder({ orderId: 11, orderNumber: 11, status: 'DELIVERED' }),
        managementOrder({ orderId: 12, orderNumber: 12, status: 'CANCELLED' }),
      ])
    )

    render(<Orders />)

    await screen.findByText('#11')
    expect(screen.queryByRole('button', { name: 'Empezar a preparar' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Marcar entregado' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Cancelar' })).toBeNull()
  })

  it('a 409 rejection toasts the server message and refreshes the board', async () => {
    const toasts = fireToasts()
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.startsWith('/api/business/orders')) {
        return { ok: true, status: 200, json: async () => [managementOrder({ orderId: 7, orderNumber: 7, status: 'NEW' })] }
      }
      if (init?.method === 'PATCH') {
        return {
          ok: false,
          status: 409,
          json: async () => ({
            status: 409,
            message: 'Transición inválida: DELIVERED -> READY',
            path: '/api/orders/7/status',
          }),
        }
      }
      return { ok: false, status: 500, json: async () => ({}) }
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<Orders />)
    await screen.findByText('#7')
    const boardLoadsBefore = fetchMock.mock.calls.filter(([url]) =>
      String(url).startsWith('/api/business/orders')
    ).length

    await user.click(screen.getByRole('button', { name: 'Empezar a preparar' }))

    // The toast carries the backend message (Spanish)...
    await vi.waitFor(() => expect(toasts).toHaveLength(1))
    expect(toasts[0].type).toBe('warning')
    expect(toasts[0].message).toContain('Transición inválida')
    // ...and the board re-syncs with a fresh GET.
    await vi.waitFor(() => {
      const boardLoadsAfter = fetchMock.mock.calls.filter(([url]) =>
        String(url).startsWith('/api/business/orders')
      ).length
      expect(boardLoadsAfter).toBeGreaterThan(boardLoadsBefore)
    })
  })
})
