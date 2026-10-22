/**
 * Role guard of the SPA views (odd/tasks/urls-por-slug.md, S4/S6): routes
 * render by path, so the router must bounce sessions to their own slugged
 * landing before painting a view that doesn't belong to them.
 *
 * La decisión vive en guardRedirect (src/view.ts, función pura): acá se
 * ejercita la matriz completa sin depender de la semántica de navegación de
 * jsdom; la navegación real en browser la cubre el e2e
 * frontend/e2e/view-guards.spec.ts.
 */
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../src/App'
import { guardRedirect, landingForRole } from '../src/view'

function stubSession(opts: { role?: string | null; slug?: string | null; token?: string | null } = {}) {
  const { role = 'OWNER', slug = 'demo-e2e', token = 'header.payload.signature' } = opts
  if (token === null) localStorage.removeItem('token')
  else localStorage.setItem('token', token)
  localStorage.setItem('role', role ?? '')
  if (slug === null) localStorage.removeItem('restaurantSlug')
  else localStorage.setItem('restaurantSlug', slug)
}

beforeEach(() => {
  localStorage.clear()
})

describe('landingForRole', () => {
  it('ADMIN → /platform (sin slug: el operador no tiene negocio)', () => {
    expect(landingForRole('ADMIN', null)).toBe('/platform')
  })

  it('los roles de negocio viven bajo el slug de su local', () => {
    expect(landingForRole('OWNER', 'demo-e2e')).toBe('/demo-e2e/admin')
    expect(landingForRole('KITCHEN', 'demo-e2e')).toBe('/demo-e2e/kds')
    expect(landingForRole('PEDIDOS', 'demo-e2e')).toBe('/demo-e2e/pedidos')
  })

  it('un rol de negocio sin slug no sabe a dónde ir → /login', () => {
    expect(landingForRole('OWNER', null)).toBe('/login')
    expect(landingForRole(null, null)).toBe('/login')
  })
})

describe('guardRedirect: carta pública', () => {
  it('/{slug} a secas es la carta pública: nadie es redirigido', () => {
    expect(guardRedirect({ view: 'menu', slug: 'e2e-demo', legacy: false }, { role: null, slug: null, hasSession: false })).toBeNull()
    expect(guardRedirect({ view: 'menu', slug: 'e2e-demo', legacy: false }, { role: 'OWNER', slug: 'demo-e2e', hasSession: true })).toBeNull()
  })

  it('/menu/{slug} (legacy) → /{slug}: la carta canónica nueva (S5)', () => {
    expect(guardRedirect({ view: 'menu', slug: 'e2e-demo', legacy: true }, { role: null, slug: null, hasSession: false })).toBe('/e2e-demo')
  })

  it('/menu a secas (deployment de uno) no redirige', () => {
    expect(guardRedirect({ view: 'menu', slug: null, legacy: false }, { role: null, slug: null, hasSession: false })).toBeNull()
  })
})

describe('guardRedirect: /platform', () => {
  it('el operador se queda', () => {
    expect(guardRedirect({ view: 'platform', slug: null, legacy: false }, { role: 'ADMIN', slug: null, hasSession: true })).toBeNull()
  })

  it('sin sesión → /login', () => {
    expect(guardRedirect({ view: 'platform', slug: null, legacy: false }, { role: null, slug: null, hasSession: false })).toBe('/login')
  })

  it('una sesión de negocio va a su panel con slug', () => {
    expect(guardRedirect({ view: 'platform', slug: null, legacy: false }, { role: 'OWNER', slug: 'demo-e2e', hasSession: true })).toBe('/demo-e2e/admin')
  })
})

describe('guardRedirect: paneles de negocio con slug del path (S6)', () => {
  const panel = { view: 'admin' as const, slug: 'demo-e2e', legacy: false }

  it('la sesión del mismo negocio se queda (null)', () => {
    expect(guardRedirect(panel, { role: 'OWNER', slug: 'demo-e2e', hasSession: true })).toBeNull()
  })

  it('sin sesión → /login', () => {
    expect(guardRedirect(panel, { role: null, slug: null, hasSession: false })).toBe('/login')
  })

  it('el operador de plataforma → /platform', () => {
    expect(guardRedirect(panel, { role: 'ADMIN', slug: null, hasSession: true })).toBe('/platform')
  })

  it('la sesión de OTRO negocio re-loguea (el backend sólo sirve datos del token)', () => {
    expect(guardRedirect(panel, { role: 'OWNER', slug: 'otro-local', hasSession: true })).toBe('/login')
  })

  it('kds y pedidos bajo slug se comportan igual', () => {
    expect(guardRedirect({ view: 'kds', slug: 'demo-e2e', legacy: false }, { role: 'KITCHEN', slug: 'demo-e2e', hasSession: true })).toBeNull()
    expect(guardRedirect({ view: 'orders', slug: 'demo-e2e', legacy: false }, { role: 'PEDIDOS', slug: 'demo-e2e', hasSession: true })).toBeNull()
  })
})

describe('guardRedirect: paths legacy sin slug (S4)', () => {
  it('con sesión de negocio → equivalente con el slug de la sesión', () => {
    expect(guardRedirect({ view: 'admin', slug: null, legacy: true }, { role: 'OWNER', slug: 'demo-e2e', hasSession: true })).toBe('/demo-e2e/admin')
    expect(guardRedirect({ view: 'dashboard', slug: null, legacy: true }, { role: 'OWNER', slug: 'demo-e2e', hasSession: true })).toBe('/demo-e2e/dashboard')
    expect(guardRedirect({ view: 'kds', slug: null, legacy: true }, { role: 'KITCHEN', slug: 'demo-e2e', hasSession: true })).toBe('/demo-e2e/kds')
    expect(guardRedirect({ view: 'orders', slug: null, legacy: true }, { role: 'PEDIDOS', slug: 'demo-e2e', hasSession: true })).toBe('/demo-e2e/pedidos')
  })

  it('sin sesión → /login (el post-login resuelve el slug)', () => {
    expect(guardRedirect({ view: 'admin', slug: null, legacy: true }, { role: null, slug: null, hasSession: false })).toBe('/login')
  })

  it('el operador de plataforma → /platform', () => {
    expect(guardRedirect({ view: 'admin', slug: null, legacy: true }, { role: 'ADMIN', slug: null, hasSession: true })).toBe('/platform')
  })
})

describe('render con el guard activo', () => {
  it('el operador ADMIN en /platform ve su panel (sin redirect)', async () => {
    stubSession({ role: 'ADMIN', slug: null })
    window.history.pushState({}, '', '/platform')
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })))

    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Panel de operador' })).toBeTruthy()
  })

  it('OWNER en /demo-e2e/admin ve el panel de SU local (sin redirect)', async () => {
    stubSession({ role: 'OWNER', slug: 'demo-e2e' })
    window.history.pushState({}, '', '/demo-e2e/admin')
    vi.stubGlobal('fetch', vi.fn(async (url: string | URL) => {
      const u = String(url)
      if (u.includes('/trends')) return { ok: false, status: 404, json: async () => ({}) }
      // Listados (productos/categorías/combos): array vacío — la tabla
      // mapea; un {} reventaría filtered.map y no es lo que este test mira.
      if (u.match(/products|categories|combos/)) return { ok: true, status: 200, json: async () => [] }
      return { ok: true, status: 200, json: async () => ({}) }
    }))

    const user = userEvent.setup()
    render(<App />)
    // El panel del local renderiza su nav sin ser expulsado; el operador de
    // plataforma jamás pisa esta vista (guardRedirect lo saca).
    expect(screen.queryByRole('heading', { name: 'Panel de operador' })).toBeNull()
    // Los links internos del nav viajan CON el slug (urls-por-slug.md): sin
    // URL intermedia sin slug ni doble recarga por el redirect del guard.
    expect(screen.getByText('KDS Cocina').closest('a')).toHaveAttribute('href', '/demo-e2e/kds')
    expect(screen.getByText('Pedidos').closest('a')).toHaveAttribute('href', '/demo-e2e/pedidos')
    await user.click(await screen.findByText('Productos'))
  })

  it('OWNER en /{slug} a secas ve la carta pública del local', async () => {
    stubSession({ role: 'OWNER', slug: 'demo-e2e' })
    window.history.pushState({}, '', '/demo-e2e')
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ business: {} }) })))

    render(<App />)
    // La carta pública no exige sesión ni redirige al panel (S3).
    expect(screen.queryByRole('heading', { name: 'Panel de operador' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Panel del local' })).toBeNull()
  })
})
