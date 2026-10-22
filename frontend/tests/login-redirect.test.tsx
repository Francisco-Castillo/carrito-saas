/**
 * Login redirect chain, ported from api/src/test/js/page-guard.test.mjs
 * (which executed the real legacy login/login.js in a vm sandbox).
 *
 * After login, every role must land on its own page with the restaurant slug
 * (taken from the login response, as in the legacy login.js). Role model of
 * odd/tasks/simplificacion-roles.md (S1/S2): ADMIN is platform-exclusive and
 * lands on /platform; OWNER inherited the business permissions of ADMIN.
 *
 *   ADMIN   -> /platform   (no slug: the operator has no business)
 *   OWNER   -> /{slug}/admin   (slug via session, from the login response)
 *   KITCHEN -> /{slug}/kds     (slug via session, from the login response)
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
  it('OWNER lands on /admin (inherits the business landing the old ADMIN had)', async () => {
    // S1 de simplificacion-roles.md: OWNER hereda los permisos Y el landing
    // de negocio del viejo ADMIN. El dashboard queda alcanzable por URL.
    const { location } = await submitLogin('OWNER')
    await waitFor(() => expect(location.href).toBe(`/${RESTAURANT_SLUG}/admin`))
  })

  it('KITCHEN lands on /{slug}/kds (the session carries the slug)', async () => {
    const { location } = await submitLogin('KITCHEN')
    await waitFor(() => expect(location.href).toBe(`/${RESTAURANT_SLUG}/kds`))
  })

  it('ADMIN lands on /platform (the platform-exclusive role, no business slug)', async () => {
    // S2 de simplificacion-roles.md: ADMIN es el rol exclusivo del panel de
    // plataforma — ya no existe PLATFORM_ADMIN ni el /admin de negocio.
    const { location } = await submitLogin('ADMIN', '')
    await waitFor(() => expect(location.href).toBe(`/platform`))
  })

  it('PEDIDOS lands on /{slug}/pedidos (the orders console is its own role)', async () => {
    const { location } = await submitLogin('PEDIDOS')
    await waitFor(() => expect(location.href).toBe(`/${RESTAURANT_SLUG}/pedidos`))
  })

  it('the restaurant slug from the login response is stored verbatim in the session', async () => {
    // The legacy login.js used data.restaurantSlug as-is (no re-encoding); the
    // port must keep storing it verbatim — the session is now the only place
    // the slug travels through.
    const { location } = await submitLogin('OWNER', 'demo pizzeria norte')
    // El slug viaja verbatim también en la URL de destino (S1).
    await waitFor(() => expect(location.href).toBe('/demo pizzeria norte/admin'))

    expect(localStorage.getItem('restaurantSlug')).toBe('demo pizzeria norte')
  })
})
