/**
 * Login redirect chain, ported from api/src/test/js/page-guard.test.mjs
 * (which executed the real legacy login/login.js in a vm sandbox).
 *
 * After login, every role must land on its own clean SPA path. Since the
 * panel URLs stopped leaking the static file names (clean URLs task), the
 * redirect carries no file suffix and no ?restaurant= query: the panels
 * resolve the slug from the session, not the URL.
 *
 *   OWNER   -> /dashboard
 *   KITCHEN -> /kds
 *   ADMIN   -> /admin
 */
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import Login from '../src/pages/Login'

const RESTAURANT_SLUG = 'demo-pizzeria'

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data })

function stubLocation(search: string) {
  const location = { href: '', search, replace: vi.fn() }
  vi.stubGlobal('location', location)
  return location
}

async function submitLogin(role: string, restaurantSlug = RESTAURANT_SLUG) {
  const fetchMock = vi.fn(async (url: string | URL | Request) => {
    const u = String(url instanceof Request ? url.url : url)
    if (u.startsWith('/api/restaurants/slug/')) {
      return okJson({ name: 'Demo Pizzeria' })
    }
    if (u === '/api/auth/login') {
      return okJson({
        token: 'header.payload.signature',
        businessId: 1,
        role,
        username: 'demo',
        expiresAt: '2030-01-01T00:00:00Z',
        restaurantSlug,
      })
    }
    throw new Error('unexpected fetch: ' + u)
  })
  vi.stubGlobal('fetch', fetchMock)

  const location = stubLocation(`?restaurant=${RESTAURANT_SLUG}`)
  render(<Login />)

  const user = userEvent.setup()
  await user.type(screen.getByPlaceholderText('Usuario'), 'demo')
  await user.type(screen.getByPlaceholderText('Contraseña'), 'secret')
  await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }))

  return { location }
}

describe('login redirect chain (ported from api/src/test/js/page-guard.test.mjs)', () => {
  it('OWNER lands on /dashboard', async () => {
    const { location } = await submitLogin('OWNER')
    await waitFor(() => expect(location.href).toBe('/dashboard'))
  })

  it('KITCHEN lands on /kds', async () => {
    const { location } = await submitLogin('KITCHEN')
    await waitFor(() => expect(location.href).toBe('/kds'))
  })

  it('ADMIN lands on /admin', async () => {
    const { location } = await submitLogin('ADMIN')
    await waitFor(() => expect(location.href).toBe('/admin'))
  })

  it('the restaurant slug from the login response is stored verbatim in the session', async () => {
    // The old contract pinned the slug verbatim in the redirect URL; the URL
    // no longer carries it. The session now does, so the "verbatim" rule
    // (no re-encoding, no trimming) moves there.
    const { location } = await submitLogin('OWNER', 'demo pizzeria norte')
    await waitFor(() => expect(location.href).toBe('/dashboard'))
    expect(localStorage.getItem('restaurantSlug')).toBe('demo pizzeria norte')
  })
})
