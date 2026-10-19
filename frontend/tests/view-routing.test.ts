/**
 * View routing contract: ONE React bundle serves every entry path.
 *
 * The Spring build ships the SPA shell at the legacy static paths
 * (menu/index.html, admin/admin.html, ...) and, since the clean-URLs task,
 * the login redirects and users land on bare paths too (/admin, /dashboard,
 * ...). Both families must resolve to the same view, with and without the
 * trailing slash (Spring MVC no longer matches it implicitly, so the route
 * table owns it). Unknown paths keep falling back to the login view, and the
 * dev-only ?view= escape hatch keeps forcing a view on non-route paths.
 */
import { afterEach, describe, expect, it } from 'vitest'
import { getView } from '../src/view'

function at(path: string) {
  window.history.replaceState(null, '', path)
}

afterEach(() => {
  window.history.replaceState(null, '', '/')
})

describe('view routing (one SPA, every entry path)', () => {
  it.each([
    ['/menu', 'menu'],
    ['/menu/', 'menu'],
    ['/menu/index.html', 'menu'],
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

  it('an unknown path falls back to the login view', () => {
    at('/some-unknown-place')
    expect(getView()).toBe('login')
  })

  it('the dev-only ?view= escape hatch still forces a view on a non-route path', () => {
    at('/?view=admin')
    expect(getView()).toBe('admin')
  })
})
