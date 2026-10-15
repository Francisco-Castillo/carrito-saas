/**
 * Dashboard API auth contract: the business dashboard endpoints
 * (/dashboard/today, /orders/status-summary, /analytics/*) are JWT-protected
 * since the broad /api/restaurants/** anonymous permit was narrowed, so every
 * request the dashboard page sends must carry the session token as
 * `Authorization: Bearer <token>` (read hot from localStorage, same pattern
 * as src/pages/admin/api.ts). Without the header the backend answers 403 and
 * the page renders nothing but empty state.
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
  stubLocation(`?restaurant=${SLUG}`)
  localStorage.setItem('token', TOKEN)
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

  it('the dashboard requests still target the restaurant slug from the URL', async () => {
    const fetchMock = stubRecordingFetch()
    const { api } = await import('../src/pages/dashboard/api')

    await api.getDashboardToday()

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`/api/restaurants/${SLUG}/dashboard/today`)
  })
})
