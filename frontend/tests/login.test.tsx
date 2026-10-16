/**
 * Organic login contract (Phase 1): the login page must accept credentials
 * WITHOUT a `?restaurant=<slug>` in the URL. The backend (commit a5609e9)
 * resolves the business from the user's membership when `restaurantSlug` is
 * omitted from the POST body.
 *
 * Pinned behavior:
 *   - Submit works without a slug: the POST body carries NO restaurantSlug.
 *   - Submit with a slug keeps sending restaurantSlug (backward compatible).
 *   - The submit button is enabled without a slug.
 *   - Without a slug the restaurant chip shows a neutral label ("Ingreso
 *     general") and no slug lookup is fetched.
 *   - The redirect target keeps using data.restaurantSlug from the response
 *     even when the URL had no slug.
 *   - Phase 2: a successful login stores the response slug in localStorage
 *     under `restaurantSlug` (next to token, businessId, role, username,
 *     expiresAt) so dashboard/kds/admin resolve it from the session; without
 *     a response slug and without a URL slug no stale value is kept.
 */
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import Login from '../src/pages/Login'

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data })

function stubLocation(search: string) {
  const location = { href: '', search, replace: vi.fn() }
  vi.stubGlobal('location', location)
  return location
}

/** Renders Login and submits credentials; returns the fetch mock and the
 * stubbed location for assertions. */
async function submitLogin(options: {
  search: string
  loginResponse?: Record<string, unknown>
}) {
  const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const u = String(url instanceof Request ? url.url : url)
    if (u.startsWith('/api/restaurants/slug/')) {
      return okJson({ name: 'Demo Pizzeria' })
    }
    if (u === '/api/auth/login') {
      return okJson(
        options.loginResponse ?? {
          token: 'header.payload.signature',
          businessId: 1,
          role: 'OWNER',
          username: 'demo',
          expiresAt: '2030-01-01T00:00:00Z',
          restaurantSlug: 'demo-pizzeria',
        }
      )
    }
    throw new Error('unexpected fetch: ' + u)
  })
  vi.stubGlobal('fetch', fetchMock)

  const location = stubLocation(options.search)
  render(<Login />)

  const user = userEvent.setup()
  await user.type(screen.getByPlaceholderText('Usuario'), 'demo')
  await user.type(screen.getByPlaceholderText('Contraseña'), 'secret')
  await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }))

  return { fetchMock, location }
}

describe('organic login (login without ?restaurant=)', () => {
  it('login without a slug succeeds and the POST body has no restaurantSlug', async () => {
    const { fetchMock, location } = await submitLogin({ search: '' })

    await waitFor(() => expect(location.href).toBe('/dashboard/index.html?restaurant=demo-pizzeria'))

    const loginCall = fetchMock.mock.calls.find(([u]) => String(u) === '/api/auth/login')
    expect(loginCall).toBeDefined()
    const body = JSON.parse(String(loginCall![1]?.body))
    expect(body).toEqual({ username: 'demo', password: 'secret' })
    expect('restaurantSlug' in body).toBe(false)
  })

  it('login with a slug keeps posting restaurantSlug in the body', async () => {
    const { fetchMock, location } = await submitLogin({ search: '?restaurant=demo-pizzeria' })

    await waitFor(() => expect(location.href).toBe('/dashboard/index.html?restaurant=demo-pizzeria'))

    const loginCall = fetchMock.mock.calls.find(([u]) => String(u) === '/api/auth/login')
    expect(loginCall).toBeDefined()
    const body = JSON.parse(String(loginCall![1]?.body))
    expect(body.restaurantSlug).toBe('demo-pizzeria')
  })

  it('the submit button is enabled without a slug', () => {
    stubLocation('')
    render(<Login />)
    expect(screen.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled()
  })

  it('without a slug the chip shows the neutral "Ingreso general" label and no slug lookup fires', async () => {
    const { fetchMock } = await submitLogin({ search: '' })

    expect(screen.getByText('Ingreso general')).toBeInTheDocument()
    // Give any (wrong) slug fetch a chance to happen before asserting.
    await waitFor(() => {
      expect(fetchMock.mock.calls.every(([u]) => !String(u).startsWith('/api/restaurants/slug/'))).toBe(true)
    })
  })

  it('the redirect target uses data.restaurantSlug from the response even without a slug in the URL', async () => {
    const { location } = await submitLogin({
      search: '',
      loginResponse: {
        token: 'header.payload.signature',
        businessId: 7,
        role: 'KITCHEN',
        username: 'demo',
        expiresAt: '2030-01-01T00:00:00Z',
        restaurantSlug: 'otra-sucursal',
      },
    })

    await waitFor(() => expect(location.href).toBe('/kds/index.html?restaurant=otra-sucursal'))
  })

  it('a successful login stores the response slug in localStorage under "restaurantSlug" (Phase 2)', async () => {
    const { location } = await submitLogin({ search: '' })

    await waitFor(() => expect(location.href).toBe('/dashboard/index.html?restaurant=demo-pizzeria'))

    expect(localStorage.getItem('restaurantSlug')).toBe('demo-pizzeria')
  })

  it('a login with a URL slug but no slug in the response stores the URL slug in "restaurantSlug" (Phase 2)', async () => {
    const { location } = await submitLogin({
      search: '?restaurant=de-la-url',
      loginResponse: {
        token: 'header.payload.signature',
        businessId: 1,
        role: 'OWNER',
        username: 'demo',
        expiresAt: '2030-01-01T00:00:00Z',
      },
    })

    await waitFor(() => expect(location.href).toBe('/dashboard/index.html?restaurant=de-la-url'))

    expect(localStorage.getItem('restaurantSlug')).toBe('de-la-url')
  })

  it('without a response slug and without a URL slug no stale "restaurantSlug" is kept (Phase 2)', async () => {
    localStorage.setItem('restaurantSlug', 'local-viejo')

    const { location } = await submitLogin({
      search: '',
      loginResponse: {
        token: 'header.payload.signature',
        businessId: 7,
        role: 'KITCHEN',
        username: 'demo',
        expiresAt: '2030-01-01T00:00:00Z',
      },
    })

    await waitFor(() => expect(location.href).toBe('/kds/index.html'))

    expect(localStorage.getItem('restaurantSlug')).toBeNull()
  })
})
