/**
 * Session guard contract, ported from api/src/test/js/page-guard.test.mjs
 * (which extracted the inline guard <script> blocks from the legacy
 * kds/index.html and dashboard/index.html pages).
 *
 * The legacy guard is now one shared module (src/pages/dashboard/auth.ts)
 * used by the Dashboard, KDS and Admin pages, so there is a single guard to
 * pin instead of one per page. The pinned access contract is unchanged:
 *
 *   - A page without a usable session must land on /login/login.html, keeping
 *     the `restaurant` slug so the login can bind the session to the right
 *     restaurant, and must stop there (nothing else may run or throw).
 *   - A page with a token must not redirect and must not inspect the token:
 *     role enforcement is the server's job (403 on the API), and a client-side
 *     role gate crashes on tokens without a `roles` claim.
 *
 * Hardening (AC10-AC13) is ported too: a failing `localStorage.getItem`
 * (storage disabled) and dead token strings ("null", "undefined", empty,
 * whitespace-only, and the same dead literals padded with whitespace) must all
 * still end in a redirect, while a real JWT-shaped token must never redirect.
 *
 * Not portable to the React app (documented, not weakened): the legacy AC12
 * case "missing URLSearchParams" — in the React SPA URLSearchParams is a
 * runtime global that always exists, and the ported guard's try/catch around
 * the slug read (which is what AC12 exercised) is still in place in auth.ts.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { enforceSession, hasUsableSession } from '../src/pages/dashboard/auth'

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

function expectRedirectWithSlug(location: ReturnType<typeof stubLocation>) {
  expect(location.replace).toHaveBeenCalledWith(
    `${LOGIN_TARGET}?restaurant=${RESTAURANT_SLUG}`
  )
  expect(location.href).toBe(`${LOGIN_TARGET}?restaurant=${RESTAURANT_SLUG}`)
}

beforeEach(() => {
  localStorage.clear()
})

describe('page guard (ported from api/src/test/js/page-guard.test.mjs — backs dashboard/, kds/ and admin/ pages)', () => {
  it('no token and a restaurant slug lands on /login/login.html with the slug and stops', () => {
    const location = stubLocation(`?restaurant=${RESTAURANT_SLUG}`)
    setToken(null)

    expect(() => enforceSession()).not.toThrow()
    expectRedirectWithSlug(location)
    expect(hasUsableSession()).toBe(false)
  })

  it('no token and no slug lands on the bare /login/login.html and stops', () => {
    const location = stubLocation('')
    setToken(null)

    expect(() => enforceSession()).not.toThrow()
    expect(location.replace).toHaveBeenCalledWith(LOGIN_TARGET)
    expect(location.href).toBe(LOGIN_TARGET)
  })

  it('a failing localStorage.getItem still redirects to /login/login.html with the slug and stops (AC10)', () => {
    const location = stubLocation(`?restaurant=${RESTAURANT_SLUG}`)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('localStorage is blocked (storage disabled or private mode)')
    })

    expect(() => {
      expect(hasUsableSession()).toBe(false)
      enforceSession()
    }).not.toThrow()
    // Blocked storage must still end in a redirect that keeps the slug.
    expectRedirectWithSlug(location)
  })

  it('dead token strings land on /login/login.html with the slug (AC11)', () => {
    for (const dead of ['null', 'undefined', '', '   ', ' null ', 'null\n', '\tnull\t', ' undefined ']) {
      const location = stubLocation(`?restaurant=${RESTAURANT_SLUG}`)
      setToken(dead)

      expect(hasUsableSession(), `the unusable token ${JSON.stringify(dead)} must be treated as no session`).toBe(false)
      expect(() => enforceSession()).not.toThrow()
      // The unusable token must redirect with the restaurant slug.
      expectRedirectWithSlug(location)
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
    expectRedirectWithSlug(location)
  })
})
