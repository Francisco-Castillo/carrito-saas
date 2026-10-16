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
 *
 * Missing-slug contract: a session without a readable slug must NOT keep
 * rendering silently empty panels, but the API modules must stay importable
 * in ANY session state — module scope has no side effects (no redirect, no
 * navigation, no throw). The SPA bundle statically imports every page
 * module, including the login page, so a module-scope redirect from the
 * dashboard/kds api modules would reload /login/login.html forever (95
 * reloads in 3s in the browser). The slug-less session is handled at PAGE
 * level instead: the pages gate on hasPanelSession() and send the session
 * back to the bare login page (/login/login.html, no ?restaurant=) through
 * the existing enforceSession() path, where the organic login writes the
 * slug on the next authentication.
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
})

describe('module-scope side effects (browser regression: /login/login.html reloaded itself forever)', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('importing the dashboard api module with a token but NO slug does not navigate (side-effect-free import)', async () => {
    localStorage.removeItem('restaurantSlug')
    const replace = vi.fn()
    vi.stubGlobal('location', { href: '/login/login.html', search: '', replace })

    await expect(import('../src/pages/dashboard/api')).resolves.toBeDefined()

    // A module import must never call location.replace: the SPA bundle
    // statically imports every page module, so a module-scope redirect from
    // here reloaded the login page in an infinite loop.
    expect(replace).not.toHaveBeenCalled()
  })

  it('importing the kds api module with a token but NO slug does not navigate (side-effect-free import)', async () => {
    localStorage.removeItem('restaurantSlug')
    const replace = vi.fn()
    vi.stubGlobal('location', { href: '/login/login.html', search: '', replace })

    await expect(import('../src/pages/kds/api')).resolves.toBeDefined()

    expect(replace).not.toHaveBeenCalled()
  })

  it('importing the kds api module with a token but NO slug still resolves getRestaurantSlug() to null without navigating', async () => {
    localStorage.removeItem('restaurantSlug')
    const replace = vi.fn()
    vi.stubGlobal('location', { href: '/login/login.html', search: '', replace })

    const { getRestaurantSlug } = await import('../src/pages/kds/api')

    expect(getRestaurantSlug()).toBeNull()
    expect(replace).not.toHaveBeenCalled()
  })
})
