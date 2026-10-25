/**
 * Orders console contract (S2/S3 de odd/tasks/transfer-payment-gate.md):
 * the console ACCEPTS orders, it does not cook them — preparing/ready/
 * delivered live in the KDS. A transferencia order starts PENDING_PAYMENT
 * and only "Confirmar y aceptar" (PATCH status=NEW) sends it to the kitchen.
 * A 409 rejection still surfaces the server message as a toast and re-syncs
 * the board with a fresh GET (the KDS contract, fix fb07a32).
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

describe('orders page — payment gate and console actions', () => {
  it('a PENDING_PAYMENT transferencia order confirms payment via PATCH status=NEW', async () => {
    const fetchMock = stubOrdersFetch([
      managementOrder({ orderId: 7, orderNumber: 7, status: 'PENDING_PAYMENT', paymentMethod: 'TRANSFERENCIA' }),
    ])
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<Orders />)
    await user.click(await screen.findByRole('button', { name: 'Confirmar y aceptar' }))

    const patch = fetchMock.mock.calls.find(
      ([url, init]) =>
        String(url) === '/api/orders/7/status?status=NEW' && init?.method === 'PATCH'
    )
    expect(patch).toBeTruthy()
  })

  it('a PENDING_PAYMENT order without transferencia shows the plain "Aceptar" label', async () => {
    vi.stubGlobal(
      'fetch',
      stubOrdersFetch([managementOrder({ orderId: 8, orderNumber: 8, status: 'PENDING_PAYMENT' })])
    )

    render(<Orders />)
    await screen.findByText('#8')
    expect(screen.queryByRole('button', { name: 'Confirmar y aceptar' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Aceptar' })).toBeTruthy()
  })

  it('a NEW order offers no kitchen transitions: preparing/ready/delivered live in the KDS', async () => {
    vi.stubGlobal(
      'fetch',
      stubOrdersFetch([managementOrder({ orderId: 9, orderNumber: 9, status: 'NEW' })])
    )

    render(<Orders />)
    await screen.findByText('#9')
    expect(screen.queryByRole('button', { name: 'Empezar a preparar' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Marcar listo' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Marcar entregado' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Aceptar' })).toBeNull()
    // Cancelar e Imprimir siguen siendo acciones de la consola.
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Ver detalle' })).toBeTruthy()
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
    expect(screen.queryByRole('button', { name: 'Confirmar y aceptar' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Aceptar' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Cancelar' })).toBeNull()
  })

  it('the status filter pills include the payment-gate queue', async () => {
    vi.stubGlobal('fetch', stubOrdersFetch([]))

    render(<Orders />)
    expect(await screen.findByRole('button', { name: /Pago pendiente/ })).toBeTruthy()
  })

  it('a 409 rejection toasts the server message and refreshes the board', async () => {
    const toasts = fireToasts()
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.startsWith('/api/business/orders')) {
        return {
          ok: true,
          status: 200,
          json: async () => [
            managementOrder({ orderId: 7, orderNumber: 7, status: 'PENDING_PAYMENT', paymentMethod: 'TRANSFERENCIA' }),
          ],
        }
      }
      if (init?.method === 'PATCH') {
        return {
          ok: false,
          status: 409,
          json: async () => ({
            status: 409,
            message: 'Transición inválida: PENDING_PAYMENT -> NEW',
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

    await user.click(screen.getByRole('button', { name: 'Confirmar y aceptar' }))

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
