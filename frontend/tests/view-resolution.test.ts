/**
 * Slug-aware view resolution (odd/tasks/urls-por-slug.md, S2/S3/S5): the
 * business lives in the URL again — /{slug} is the public menu, /{slug}/panel
 * are the business panels, and the old clean paths resolve as legacy (the
 * guard redirects them to their slugged equivalent using the session slug).
 */
import { afterEach, describe, expect, it } from 'vitest'
import { resolveView, getView, type AppView } from '../src/view'

function at(path: string) {
  window.history.replaceState(null, '', path)
}

afterEach(() => {
  window.history.replaceState(null, '', '/')
})

describe('resolveView: /{slug} a secas = carta pública', () => {
  it.each([
    ['/e2e-demo', 'menu', 'e2e-demo'],
    ['/e2e-demo/', 'menu', 'e2e-demo'],
  ])('%s resuelve carta pública del slug', (path, view, slug) => {
    at(path)
    expect(resolveView(path)).toEqual({ view: view as AppView, slug, legacy: false })
  })

  it('un segmento que no tiene forma de slug no es un local (login fallback)', () => {
    at('/E2E Demo')
    expect(resolveView('/E2E Demo')).toEqual({ view: 'login', slug: null, legacy: false })
  })

  it('segmentos reservados nunca son un slug de local', () => {
    at('/api')
    expect(resolveView('/api').view).toBe('login')
    at('/assets')
    expect(resolveView('/assets').view).toBe('login')
  })
})

describe('resolveView: /{slug}/panel = paneles del negocio', () => {
  it.each([
    ['/e2e-demo/admin', 'admin', 'e2e-demo'],
    ['/e2e-demo/admin/', 'admin', 'e2e-demo'],
    ['/e2e-demo/dashboard', 'dashboard', 'e2e-demo'],
    ['/e2e-demo/kds', 'kds', 'e2e-demo'],
    ['/e2e-demo/pedidos', 'orders', 'e2e-demo'],
  ])('%s resuelve el panel con el slug del path', (path, view, slug) => {
    at(path)
    expect(resolveView(path)).toEqual({ view: view as AppView, slug, legacy: false })
  })

  it('segundo segmento que no es un panel conocido cae al login', () => {
    at('/e2e-demo/xyz')
    expect(resolveView('/e2e-demo/xyz')).toEqual({ view: 'login', slug: null, legacy: false })
  })

  it('"platform" bajo un slug no existe (el operador no tiene negocio)', () => {
    at('/e2e-demo/platform')
    expect(resolveView('/e2e-demo/platform').view).toBe('login')
  })
})

describe('resolveView: paths limpios viejos = legacy (el guard los redirige)', () => {
  it.each([
    ['/admin', 'admin'],
    ['/admin/', 'admin'],
    ['/admin/admin.html', 'admin'],
    ['/dashboard', 'dashboard'],
    ['/kds', 'kds'],
    ['/pedidos', 'orders'],
  ])('%s resuelve su vista marcada legacy', (path, view) => {
    at(path)
    expect(resolveView(path)).toEqual({ view: view as AppView, slug: null, legacy: true })
  })

  it('/menu/{slug} es legacy: la carta canónica ahora es /{slug}', () => {
    at('/menu/e2e-demo')
    expect(resolveView('/menu/e2e-demo')).toEqual({ view: 'menu', slug: 'e2e-demo', legacy: true })
  })

  it('/menu a secas sigue existiendo (shell sin slug, deployment de uno)', () => {
    at('/menu')
    expect(resolveView('/menu')).toEqual({ view: 'menu', slug: null, legacy: false })
  })
})

describe('resolveView: no-slug views y compatibilidad', () => {
  it.each([
    ['/platform', 'platform'],
    ['/platform/', 'platform'],
    ['/login', 'login'],
  ])('%s no lleva slug', (path, view) => {
    at(path)
    expect(resolveView(path)).toEqual({ view: view as AppView, slug: null, legacy: false })
  })

  it('getView() sigue devolviendo la vista a secas (compat)', () => {
    at('/e2e-demo/admin')
    expect(getView()).toBe('admin')
    at('/e2e-demo')
    expect(getView()).toBe('menu')
  })

  it('el escape hatch dev-only ?view= sigue funcionando en paths no-route', () => {
    at('/?view=admin')
    expect(resolveView('/?view=admin').view).toBe('admin')
  })

  it('path multi-segmento desconocido cae al login', () => {
    at('/some/unknown/place')
    expect(resolveView('/some/unknown/place').view).toBe('login')
  })

  it('un slug-shaped suelto ES la carta pública del local (S3), no un desconocido', () => {
    at('/some-unknown-place')
    expect(resolveView('/some-unknown-place')).toEqual({
      view: 'menu',
      slug: 'some-unknown-place',
      legacy: false,
    })
  })
})
