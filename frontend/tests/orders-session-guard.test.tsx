/**
 * Orders console session guard (T3/S3): the page must gate exactly like the
 * other panels — hasPanelSession() (usable token AND readable session slug)
 * before rendering or fetching, enforceSession() to the bare /login
 * otherwise. A page without a session renders nothing and never issues
 * requests; a page with a session carries the Bearer token on its calls.
 */
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
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

function stubLocation() {
  const location = { href: '', search: '', replace: vi.fn() }
  vi.stubGlobal('location', location)
  return location
}

afterEach(cleanup)

describe('orders page — session guard', () => {
  it('without a session the page redirects to /login and renders nothing', () => {
    const location = stubLocation()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(<Orders />)

    expect(location.replace).toHaveBeenCalledWith('/login')
    expect(screen.queryByRole('heading', { name: 'Pedidos' })).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('a token without a readable slug is still not a panel session', () => {
    window.localStorage.setItem('token', 'header.payload.signature')
    // No restaurantSlug in the session.
    const location = stubLocation()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(<Orders />)

    expect(location.replace).toHaveBeenCalledWith('/login')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('with a panel session the board loads with the Bearer token and no redirect', async () => {
    seedPanelSession()
    const location = stubLocation()
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).startsWith('/api/business/orders')) {
        return {
          ok: true,
          status: 200,
          json: async () => [managementOrder({ orderId: 3, orderNumber: 3 })],
        }
      }
      return { ok: false, status: 500, json: async () => ({}) }
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<Orders />)

    expect(location.replace).not.toHaveBeenCalled()
    expect(await screen.findByText('#3')).toBeTruthy()
    const boardCall = fetchMock.mock.calls.find(([url]) =>
      String(url).startsWith('/api/business/orders')
    )
    const headers = (boardCall?.[1] as { headers?: Record<string, string> })?.headers ?? {}
    expect(headers['Authorization']).toBe('Bearer header.payload.signature')
  })
})
