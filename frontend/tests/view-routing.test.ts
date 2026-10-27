/**
 * View routing contract: ONE React bundle serves every entry path.
 *
 * The Spring build ships the SPA shell at the legacy static paths
 * (admin/admin.html, dashboard/index.html, ...) and, since the clean-URLs
 * task, the login redirects and users land on bare paths too (/admin,
 * /dashboard, ...). Both families must resolve to the same view, with and
 * without the trailing slash (Spring MVC no longer matches it implicitly, so
 * the route table owns it). Unknown paths keep falling back to the login
 * view, and the dev-only ?view= escape hatch keeps forcing a view on
 * non-route paths.
 *
 * odd/tasks/retire-menu-route.md (S6/S7): the /menu page route is retired.
 * /menu, /menu/{slug} and ?view=menu no longer resolve the menu view — the
 * carta lives only at /{slug} — so they all fall back to the login view (the
 * backend 404s them before the SPA is even served). The "menu" segment stays
 * in RESERVED_SEGMENTS forever: no business may ever claim it as a slug.
 */
import { afterEach, describe, expect, it } from 'vitest'
import { getView, resolveView, titleForView, type AppView } from '../src/view'

function at(path: string) {
  window.history.replaceState(null, '', path)
}

afterEach(() => {
  window.history.replaceState(null, '', '/')
})

describe('view routing (one SPA, every entry path)', () => {
  it.each([
    ['/dashboard', 'dashboard'],
    ['/dashboard/', 'dashboard'],
    ['/dashboard/index.html', 'dashboard'],
    ['/admin', 'admin'],
    ['/admin/', 'admin'],
    ['/admin/admin.html', 'admin'],
    ['/kds', 'kds'],
    ['/kds/', 'kds'],
    ['/kds/index.html', 'kds'],
    ['/login', 'login'],
    ['/login/', 'login'],
    ['/login/login.html', 'login'],
    ['/platform', 'platform'],
    ['/platform/', 'platform'],
    ['/platform/index.html', 'platform'],
  ])('%s resolves to the %s view', (path, view) => {
    at(path)
    expect(getView()).toBe(view)
  })

  it('un path multi-segmento desconocido cae al login (un slug-shaped suelto ahora es la carta pública del local, urls-por-slug.md S3)', () => {
    at('/some/unknown/place')
    expect(getView()).toBe('login')
  })

  // odd/tasks/retire-menu-route.md S6: la ruta /menu está jubilada — la única
  // carta pública es /{slug}. /menu en todas sus formas cae al fallback
  // (login); el backend igual responde 404 antes de servir el SPA.
  it.each([
    '/menu',
    '/menu/',
    '/menu/index.html',
    '/menu/e2e-demo',
    '/menu/e2e-demo/',
  ])('%s ya NO resuelve la vista carta: cae al fallback login (S6)', (path) => {
    at(path)
    expect(resolveView(path)).toEqual({ view: 'login', slug: null, legacy: false })
  })

  // S6: el escape hatch no reanimar la vista jubilada — ?view=menu cae al
  // fallback como cualquier otro valor desconocido.
  it('?view=menu ya no fuerza la vista carta (S6): cae al login', () => {
    expect(resolveView('/?view=menu')).toEqual({ view: 'login', slug: null, legacy: false })
  })

  // S7: "menu" queda reservado para siempre — ningún local puede tener slug
  // "menu", así que /menu jamás puede ser /{slug} de un negocio.
  it('"menu" sigue siendo un segmento reservado (S7): /menu no puede resolverse como /{slug} de un local', () => {
    const resolution = resolveView('/menu')
    expect(resolution.view).not.toBe('menu')
    expect(resolution.slug).toBeNull()
  })

  it('the dev-only ?view= escape hatch still forces a view on a non-route path', () => {
    at('/?view=admin')
    expect(getView()).toBe('admin')
  })
})

describe('browser tab titles per view', () => {
  it.each([
    ['login', 'Login'],
    ['menu', 'Carta Digital'],
    ['dashboard', 'Dashboard'],
    ['admin', 'Panel del local'],
    ['kds', 'Cocina'],
    ['orders', 'Pedidos'],
    ['platform', 'Plataforma'],
  ] as Array<[AppView, string]>)('%s shows “%s” in the tab', (view, title) => {
    expect(titleForView(view)).toBe(title)
  })
})
