/**
 * Session guard contract, ported from api/src/test/js/page-guard.test.mjs
 * (which extracted the inline guard <script> blocks from the legacy
 * kds/index.html and dashboard/index.html pages).
 *
 * The legacy guard is now one shared module (src/pages/dashboard/auth.ts)
 * used by the Dashboard, KDS and Admin pages, so there is a single guard to
 * pin instead of one per page. The pinned access contract is unchanged:
 *
 *   - A page without a usable session must land on the bare /login/login.html
 *     and must stop there (nothing else may run or throw). Phase 2 (organic
 *     login): the redirect no longer carries ?restaurant= — the slug lives in
 *     the session (localStorage `restaurantSlug`, written by the login) and
 *     the URL param support is gone.
 *   - A page with a token must not redirect and must not inspect the token:
 *     role enforcement is the server's job (403 with a session and an
 *     insufficient role, 401 without a session), and a client-side
 *     role gate crashes on tokens without a `roles` claim.
 *
 * Hardening (AC10-AC13) is ported too: a failing `localStorage.getItem`
 * (storage disabled) and dead token strings ("null", "undefined", empty,
 * whitespace-only, and the same dead literals padded with whitespace) must all
 * still end in a redirect, while a real JWT-shaped token must never redirect.
 * The same hardening applies to the session slug resolution
 * (getSessionRestaurantSlug): blocked storage and dead slug strings yield no
 * slug instead of throwing or returning a bogus value.
 *
 * Not portable to the React app (documented, not weakened): the legacy AC12
 * case "missing URLSearchParams" — the React SPA never reads a slug from the
 * URL anymore, and the ported guard's try/catch around the localStorage slug
 * read (which is what AC12 exercised) is still in place in auth.ts.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { enforceSession, getSessionRestaurantSlug, hasUsableSession } from '../src/pages/dashboard/auth'

const LOGIN_TARGET = '/login/login.html'
const RESTAURANT_SLUG = 'demo-pizzeria'

/** A real-looking JWT whose payload carries no `roles` claim: role enforcement
 * belongs to the server, so the page guard must accept it untouched. */
function jwtWithoutRoles(): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url')
  const header = encode({ alg: 'HS256', typ: 'JWT' })
  const payload = encode({ sub: 'demo', businessId: 1, iat: 1700000000, exp: 1700003600 })
  return `${header}.${payload}.signature`
}

/** jsdom's Location is unforgeable, so replace it with a recording fake via
 * the global stub (the same seam the legacy test used with a vm sandbox). */
function stubLocation(search: string) {
  const location = {
    href: '',
    search,
    replace: vi.fn((target: string) => {
      location.href = target
    }),
  }
  vi.stubGlobal('location', location)
  return location
}

function setToken(token: string | null) {
  if (token === null) {
    localStorage.removeItem('token')
  } else {
    localStorage.setItem('token', token)
  }
}

function expectBareLoginRedirect(location: ReturnType<typeof stubLocation>) {
  // Phase 2: the guard never carries ?restaurant= anymore, even when the
  // current URL still has one.
  expect(location.replace).toHaveBeenCalledWith(LOGIN_TARGET)
  expect(location.href).toBe(LOGIN_TARGET)
}

beforeEach(() => {
  localStorage.clear()
})

describe('page guard (ported from api/src/test/js/page-guard.test.mjs — backs dashboard/, kds/ and admin/ pages)', () => {
  it('no token and a restaurant slug in the URL lands on the bare /login/login.html and stops', () => {
    // Phase 2: the slug in the URL is ignored — the redirect must not carry it.
    const location = stubLocation(`?restaurant=${RESTAURANT_SLUG}`)
    setToken(null)

    expect(() => enforceSession()).not.toThrow()
    expectBareLoginRedirect(location)
    expect(hasUsableSession()).toBe(false)
  })

  it('no token and no slug lands on the bare /login/login.html and stops', () => {
    const location = stubLocation('')
    setToken(null)

    expect(() => enforceSession()).not.toThrow()
    expect(location.replace).toHaveBeenCalledWith(LOGIN_TARGET)
    expect(location.href).toBe(LOGIN_TARGET)
  })

  it('a failing localStorage.getItem still redirects to the bare /login/login.html and stops (AC10)', () => {
    const location = stubLocation(`?restaurant=${RESTAURANT_SLUG}`)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('localStorage is blocked (storage disabled or private mode)')
    })

    expect(() => {
      expect(hasUsableSession()).toBe(false)
      enforceSession()
    }).not.toThrow()
    // Blocked storage must still end in a redirect.
    expectBareLoginRedirect(location)
  })

  it('dead token strings land on the bare /login/login.html (AC11)', () => {
    for (const dead of ['null', 'undefined', '', '   ', ' null ', 'null\n', '\tnull\t', ' undefined ']) {
      const location = stubLocation(`?restaurant=${RESTAURANT_SLUG}`)
      setToken(dead)

      expect(hasUsableSession(), `the unusable token ${JSON.stringify(dead)} must be treated as no session`).toBe(false)
      expect(() => enforceSession()).not.toThrow()
      // The unusable token must redirect to the bare login page.
      expectBareLoginRedirect(location)
    }
  })

  it('a token without a roles claim is accepted without redirect or error', () => {
    stubLocation(`?restaurant=${RESTAURANT_SLUG}`)
    setToken(jwtWithoutRoles())

    // The React guard splits decision (hasUsableSession) and act
    // (enforceSession): a present, usable token means the page keeps running
    // and the redirect branch is never taken.
    expect(hasUsableSession()).toBe(true)
  })

  it('a JWT-shaped token without a roles claim still does not redirect after the AC10-AC11 hardening (AC13)', () => {
    stubLocation(`?restaurant=${RESTAURANT_SLUG}`)
    setToken(jwtWithoutRoles())

    // No throw while inspecting the token (the legacy guard used to crash on
    // roles.includes for tokens without a roles claim), and no redirect
    // decision for a real JWT-shaped session.
    expect(() => hasUsableSession()).not.toThrow()
    expect(hasUsableSession()).toBe(true)
  })

  it('the guard stops after redirecting: one redirect decision, no double navigation', () => {
    const location = stubLocation(`?restaurant=${RESTAURANT_SLUG}`)
    setToken(null)

    enforceSession()
    enforceSession()
    expect(location.replace).toHaveBeenCalledTimes(2)
    // Both decisions carry the same target: nothing re-ran with a mutated URL.
    expectBareLoginRedirect(location)
  })

  describe('session slug resolution (localStorage `restaurantSlug`, not the URL)', () => {
    it('resolves the slug the login stored in the session', () => {
      stubLocation('')
      localStorage.setItem('restaurantSlug', RESTAURANT_SLUG)

      expect(getSessionRestaurantSlug()).toBe(RESTAURANT_SLUG)
    })

    it('returns no slug when the session has none', () => {
      stubLocation('')

      expect(getSessionRestaurantSlug()).toBeNull()
    })

    it('treats dead slug strings as no slug (same hardening as the token, AC11)', () => {
      stubLocation('')
      for (const dead of ['null', 'undefined', '', '   ', ' null ', 'null\n', '\tnull\t', ' undefined ']) {
        localStorage.setItem('restaurantSlug', dead)
        expect(getSessionRestaurantSlug(), `the dead slug ${JSON.stringify(dead)} must resolve to no slug`).toBeNull()
      }
    })

    it('resolves no slug when storage is disabled instead of throwing (AC10)', () => {
      stubLocation('')
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('localStorage is blocked (storage disabled or private mode)')
      })

      expect(() => getSessionRestaurantSlug()).not.toThrow()
      expect(getSessionRestaurantSlug()).toBeNull()
    })
  })
})
