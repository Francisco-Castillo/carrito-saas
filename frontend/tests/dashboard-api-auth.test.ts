/**
 * Dashboard API auth contract: the business dashboard endpoints
 * (/dashboard/today, /orders/status-summary, /analytics/*) are JWT-protected
 * since the broad /api/restaurants/** anonymous permit was narrowed, so every
 * request the dashboard page sends must carry the session token as
 * `Authorization: Bearer <token>` (read hot from localStorage, same pattern
 * as src/pages/admin/api.ts). Without the header the backend answers 401
 * (the chain's entry point for anonymous callers) and
 * the page renders nothing but empty state.
 *
 * Phase 2 (organic login): the slug comes from the session (localStorage
 * `restaurantSlug`, written by the login), never from the URL query — these
 * tests run against a clean URL (no ?restaurant=).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const SLUG = 'demo-pizzeria'
const TOKEN = 'header.payload.signature'

function stubLocation(search: string) {
  vi.stubGlobal('location', { href: `/${search}`, search, replace: vi.fn() })
}

function stubRecordingFetch() {
  const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({}) }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

beforeEach(() => {
  // Clean URL: the dashboard must not depend on ?restaurant= anymore.
  stubLocation('')
  localStorage.setItem('token', TOKEN)
  localStorage.setItem('restaurantSlug', SLUG)
})

describe('dashboard api auth header', () => {
  it('every dashboard GET carries Authorization: Bearer <token> from localStorage', async () => {
    const fetchMock = stubRecordingFetch()
    const { api } = await import('../src/pages/dashboard/api')

    await Promise.all([
      api.getDashboardToday(),
      api.getOrderStatusSummary(),
      api.getTopProducts(),
      api.getSalesByHour(),
    ])

    expect(fetchMock).toHaveBeenCalledTimes(4)
    for (const call of fetchMock.mock.calls) {
      const [, init] = call as [unknown, RequestInit]
      const headers = (init?.headers ?? {}) as Record<string, string>
      expect(headers['Authorization']).toBe(`Bearer ${TOKEN}`)
    }
  })

  it('the dashboard requests target the restaurant slug from the session (localStorage), not the URL', async () => {
    const fetchMock = stubRecordingFetch()
    const { api } = await import('../src/pages/dashboard/api')

    await api.getDashboardToday()

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`/api/restaurants/${SLUG}/dashboard/today`)
  })

  it('a missing session slug is reported and does not throw', async () => {
    localStorage.removeItem('restaurantSlug')
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.resetModules()

    await expect(import('../src/pages/dashboard/api')).resolves.toBeDefined()

    expect(consoleError).toHaveBeenCalledWith('Restaurant slug missing in session')
    consoleError.mockRestore()
  })
})
