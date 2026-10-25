/**
 * OWN PROBE (point 4): useComandaSettings must fail closed.
 *  - profile fetch 500 / network error / missing flags / non-boolean -> OFF
 *  - Orders: with the profile fetch failing, no Imprimir button and no
 *    automatic print when a NEW order arrives.
 */
import { act, cleanup, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Orders from '../src/pages/Orders'
import { useComandaSettings } from '../src/pages/orders/useComandaSettings'
import { managementOrder, seedPanelSession } from './helpers/ordersFixtures'

const { mockSubscribers } = vi.hoisted(() => ({ mockSubscribers: [] as Array<() => void> }))

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    private onConnect?: () => void
    constructor(config: { onConnect?: () => void } = {}) { this.onConnect = config.onConnect }
    activate(): void { this.onConnect?.() }
    deactivate(): void {}
    subscribe(_path: string, cb: () => void): void { mockSubscribers.push(cb) }
    publish(): void {}
  },
}))

function emitOrdersEvent(): void { mockSubscribers.forEach((cb) => cb()) }

beforeEach(() => {
  seedPanelSession()
  mockSubscribers.length = 0
})
afterEach(cleanup)

describe('probe: useComandaSettings fails closed', () => {
  it('defaults OFF while the fetch is in flight and after a 500', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500, json: async () => ({}) })))
    const { result } = renderHook(() => useComandaSettings(true))
    expect(result.current).toEqual({ printingEnabled: false, autoPrint: false, deliveryCopy: false })
    await waitFor(() => {})
    expect(result.current).toEqual({ printingEnabled: false, autoPrint: false, deliveryCopy: false })
  })

  it('stays OFF when the profile fetch rejects (network error)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('boom') }))
    const { result } = renderHook(() => useComandaSettings(true))
    await waitFor(() => {})
    expect(result.current).toEqual({ printingEnabled: false, autoPrint: false, deliveryCopy: false })
  })

  it('stays OFF when the flags are absent or not exactly boolean true', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => ({}) })))
    const { result, rerender } = renderHook(() => useComandaSettings(true))
    await waitFor(() => {})
    expect(result.current).toEqual({ printingEnabled: false, autoPrint: false, deliveryCopy: false })

    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200,
      json: async () => ({ comandaPrintingEnabled: 'true', comandaAutoPrint: 1 }),
    })))
    rerender()
    await waitFor(() => {})
    expect(result.current).toEqual({ printingEnabled: false, autoPrint: false, deliveryCopy: false })
  })

  it('is OFF while disabled (never fetches, never prints)', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useComandaSettings(false))
    await waitFor(() => {})
    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.current).toEqual({ printingEnabled: false, autoPrint: false, deliveryCopy: false })
  })
})

describe('probe: Orders never prints when the profile fetch fails', () => {
  it('no Imprimir button and no auto print with a 500 profile', async () => {
    let printed = 0
    vi.spyOn(window, 'print').mockImplementation(() => { printed += 1 })

    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.startsWith('/api/business/orders')) {
        return { ok: true, status: 200, json: async () => [managementOrder({ orderId: 1, orderNumber: 101, status: 'NEW' })] }
      }
      if (url.startsWith('/api/business/profile')) {
        return { ok: false, status: 500, json: async () => ({}) }
      }
      return { ok: false, status: 404, json: async () => ({}) }
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<Orders />)
    await screen.findByText('#101')
    expect(screen.queryByRole('button', { name: /Imprimir/ })).toBeNull()

    // A NEW order arriving later must not print either.
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.startsWith('/api/business/orders')) {
        return { ok: true, status: 200, json: async () => [
          managementOrder({ orderId: 1, orderNumber: 101, status: 'NEW' }),
          managementOrder({ orderId: 2, orderNumber: 102, status: 'NEW' }),
        ] }
      }
      if (url.startsWith('/api/business/profile')) {
        return { ok: false, status: 500, json: async () => ({}) }
      }
      return { ok: false, status: 404, json: async () => ({}) }
    })
    act(() => { emitOrdersEvent() })
    await screen.findByText('#102')
    await act(async () => { await new Promise((r) => setTimeout(r, 800)) })
    expect(printed).toBe(0)
    expect(screen.queryByRole('button', { name: /Imprimir/ })).toBeNull()
  })
})
