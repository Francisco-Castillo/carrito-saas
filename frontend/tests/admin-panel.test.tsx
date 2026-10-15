/**
 * Admin panel contracts inherited from the legacy admin/admin.js port:
 *
 *   1. Logout must clear ONLY the session keys the login writes (token,
 *      businessId, role, username, expiresAt) and must keep every other
 *      same-origin key intact — in particular `savedUser`, the remember-me
 *      username stored by the login screen. The redirect is unchanged:
 *      /login/login.html?restaurant=<slug> with the slug preserved when the
 *      URL carries one, and the bare login path when it does not.
 *
 *   2. The "+ Nuevo" button belongs to the categories view: it must be absent
 *      in dashboard, products and combos, and present in categories, where it
 *      still opens the create-category modal.
 *
 *   3. The sidebar brand block shows the BUSINESS name (GET /api/business/profile),
 *      never the vendor brand "SDE" — in a multi-tenant panel the tenant's own
 *      name is the product decision (odd/tasks/admin-panel-ui.md). When the
 *      profile fetch fails the brand falls back to a neutral label.
 *
 *   4. The view title is the Spanish label of the view, never the internal key
 *      uppercased ("PRODUCTS" leaked the implementation into the UI).
 */
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Admin from '../src/pages/Admin'

const SESSION_KEYS = ['token', 'businessId', 'role', 'username', 'expiresAt'] as const

/** JWT-shaped token with a far-future exp so the dashboard's checkAuth() keeps the page up. */
function futureJwt(): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url')
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: 'demo', exp: 4102444800 })}.signature`
}

/**
 * jsdom's Location is unforgeable, so replace it with a recording fake via
 * the global stub (the same seam used by session-guard.test.ts).
 */
function stubLocation(search: string) {
  const location = { href: '', search, replace: vi.fn() }
  vi.stubGlobal('location', location)
  return location
}

/**
 * Fetch stub that fails every /api call with a non-ok response: the dashboard
 * loaders keep their initial (null) state, and products/combos render empty
 * tables — enough for the panel chrome (nav, topbar, logout) under test.
 */
function stubFailingFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok: false, status: 500, json: async () => [] })),
  )
}

/** Fetch stub that answers only the business profile; everything else fails. */
function stubProfileFetch(profile: object) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/business/profile')) {
        return { ok: true, status: 200, json: async () => profile }
      }
      return { ok: false, status: 500, json: async () => [] }
    }),
  )
}

function seedStorage() {
  localStorage.setItem('token', futureJwt())
  localStorage.setItem('businessId', '1')
  localStorage.setItem('role', 'ADMIN')
  localStorage.setItem('username', 'demo')
  localStorage.setItem('expiresAt', '2030-01-01T00:00:00Z')
  localStorage.setItem('savedUser', 'demo')
  localStorage.setItem('theme', 'dark')
}

function renderAdmin(search: string) {
  const location = stubLocation(search)
  stubFailingFetch()
  render(<Admin />)
  return location
}

beforeEach(() => {
  localStorage.clear()
  seedStorage()
})

afterEach(() => {
  cleanup()
})

describe('Sidebar brand: business name, not the vendor brand', () => {
  it('renders the business name from GET /api/business/profile in the brand block', async () => {
    stubProfileFetch({ name: 'Pizzería La Española' })
    stubLocation('')
    render(<Admin />)

    const brand = await screen.findByText('Pizzería La Española')
    expect(brand).toHaveClass('admin-logo')
    expect(screen.queryByText('SDE')).not.toBeInTheDocument()
  })

  it('falls back to a neutral label (never "SDE") when the profile fetch fails', () => {
    stubFailingFetch()
    renderAdmin('')

    expect(screen.queryByText('SDE')).not.toBeInTheDocument()
    expect(screen.getByText('Panel')).toHaveClass('admin-logo')
  })
})

describe('View title: Spanish label instead of the internal key', () => {
  it('shows the Spanish label per view, never the uppercased key', async () => {
    const user = userEvent.setup()
    stubFailingFetch()
    render(<Admin />)

    const title = () => (document.querySelector('#view-title') as HTMLElement | null)?.textContent
    expect(title()).toBe('Dashboard')

    await user.click(screen.getByRole('button', { name: 'Productos' }))
    expect(title()).toBe('Productos')

    await user.click(screen.getByRole('button', { name: 'Categorías' }))
    expect(title()).toBe('Categorías')

    await user.click(screen.getByRole('button', { name: 'Combos' }))
    expect(title()).toBe('Combos')

    await user.click(screen.getByRole('button', { name: 'Local' }))
    expect(title()).toBe('Local')
  })
})

describe('Admin logout (session-only clear, slug-preserving redirect)', () => {
  it('with a restaurant slug: clears exactly the session keys, keeps savedUser and the rest, and lands on /login/login.html?restaurant=<slug>', async () => {
    const user = userEvent.setup()
    const location = renderAdmin('?restaurant=demo-pizzeria')

    await user.click(screen.getByRole('button', { name: /Logout/ }))

    for (const key of SESSION_KEYS) {
      expect(localStorage.getItem(key), `${key} must be removed on logout`).toBeNull()
    }
    expect(localStorage.getItem('savedUser')).toBe('demo')
    expect(localStorage.getItem('theme')).toBe('dark')

    expect(location.href).toBe('/login/login.html?restaurant=demo-pizzeria')
  })

  it('without a restaurant slug: same session-only clear and a bare /login/login.html redirect', async () => {
    const user = userEvent.setup()
    const location = renderAdmin('')

    await user.click(screen.getByRole('button', { name: /Logout/ }))

    for (const key of SESSION_KEYS) {
      expect(localStorage.getItem(key), `${key} must be removed on logout`).toBeNull()
    }
    expect(localStorage.getItem('savedUser')).toBe('demo')
    expect(localStorage.getItem('theme')).toBe('dark')

    expect(location.href).toBe('/login/login.html')
  })
})

describe('" + Nuevo" visibility per admin view', () => {
  it('is absent in dashboard, products and combos, and opens the create-category modal from categories', async () => {
    const user = userEvent.setup()
    renderAdmin('')

    // Initial view: dashboard.
    expect(screen.queryByRole('button', { name: '+ Nuevo' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Productos' }))
    expect(screen.queryByRole('button', { name: '+ Nuevo' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Combos' }))
    expect(screen.queryByRole('button', { name: '+ Nuevo' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Categorías' }))
    expect(screen.getByRole('button', { name: '+ Nuevo' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '+ Nuevo' }))
    expect(await screen.findByRole('dialog', { name: 'Crear nueva categoría' })).toBeInTheDocument()
  })
})
