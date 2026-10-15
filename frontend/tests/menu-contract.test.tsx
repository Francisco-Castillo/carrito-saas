/**
 * Public menu contract, ported 1:1 from api/src/test/js/menu-app.test.mjs
 * (which ran the legacy vanilla menu/app.js in a vm sandbox).
 *
 * The pinned contract is unchanged: when the `restaurant` query parameter is
 * missing, or the restaurant/menu cannot be loaded, the page must show a
 * comprehensible in-page message and STOP — no fetch of `/api/menu/undefined`
 * (or `/null`), no product list, no order panel through which a broken link
 * could still submit an order. The happy path (menu load, cart, order
 * submission with the legacy payload shape) is pinned too, so the failure
 * handling can never silently break it.
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import Menu from '../src/pages/Menu'

const SLUG = 'ok-local'
const RESTAURANT = { name: 'Local OK', whatsappNumber: '' }
const MENU_PAYLOAD = {
  products: [
    {
      id: 101,
      name: 'Milanesa',
      description: 'con papas',
      price: 100,
      categoryId: 1,
      categoryName: 'Platos',
      imageUrl: 'img.jpg',
    },
  ],
  combos: [],
}

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data })

interface FetchCall {
  url: string
  method: string
  body?: string
}

function makeFetch(opts: { menuFailure?: 'http-500' | 'bad-json' | 'network' } = {}) {
  const calls: FetchCall[] = []
  const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const u = String(url instanceof Request ? url.url : url)
    const call: FetchCall = {
      url: u,
      method: init?.method ?? 'GET',
      body: typeof init?.body === 'string' ? init.body : undefined,
    }
    calls.push(call)
    if (u.startsWith('/api/restaurants/slug/')) {
      return u === `/api/restaurants/slug/${SLUG}`
        ? okJson(RESTAURANT)
        : { ok: false, status: 404, json: async () => ({}) }
    }
    if (u.startsWith('/api/menu/')) {
      // Optional failure injection for the valid restaurant's menu fetch,
      // mirroring the legacy test's menuFailure modes.
      if (u === `/api/menu/${SLUG}`) {
        if (opts.menuFailure === 'network') {
          throw new TypeError('Failed to fetch')
        }
        if (opts.menuFailure === 'bad-json') {
          return {
            ok: true,
            status: 200,
            json: async () => {
              throw new SyntaxError("Unexpected token '<' is not valid JSON")
            },
          }
        }
        if (opts.menuFailure === 'http-500') {
          return { ok: false, status: 500, json: async () => ({}) }
        }
        return okJson(MENU_PAYLOAD)
      }
      return okJson({ products: [], combos: [] })
    }
    if (u.startsWith('/api/orders/menu/')) {
      return okJson({ orderId: 42, status: 'NEW' })
    }
    throw new Error('unexpected fetch: ' + u)
  })
  return { calls, fetchMock }
}

/** Sets the query string on the real jsdom location (same-document URL change). */
function setSearch(search: string) {
  window.history.replaceState(null, '', search === '' ? '/' : `/${search}`)
}

function renderMenu(fetchMock: ReturnType<typeof makeFetch>['fetchMock']) {
  // Menu.tsx calls the global alert for the order confirmation.
  const alertMock = vi.fn()
  vi.stubGlobal('alert', alertMock)
  vi.stubGlobal('fetch', fetchMock)
  render(<Menu />)
  return { alertMock }
}

/** The customer-readable load-error notice (legacy asserted it mentions "carta"). */
async function expectLoadError() {
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent(/carta/)
  return alert
}

function expectNoOrderPanel() {
  expect(document.querySelector('.cart')).not.toBeInTheDocument()
}

function expectNoFetch(calls: FetchCall[], fragment: string) {
  expect(calls.some((call) => call.url.includes(fragment))).toBe(false)
}

describe('Menu (public menu contract, ported from api/src/test/js/menu-app.test.mjs)', () => {
  it('missing restaurant parameter: shows a customer-readable message and stops before any fetch', async () => {
    const { calls, fetchMock } = makeFetch()
    setSearch('')
    renderMenu(fetchMock)

    await expectLoadError()
    // Strongest form of the legacy assertion: the page must fetch NOTHING.
    expect(calls).toEqual([])
    // In particular it must never reach /api/menu/undefined or /api/menu/null.
    expectNoFetch(calls, '/api/menu/undefined')
    expectNoFetch(calls, '/api/menu/null')
    expectNoFetch(calls, '/api/restaurants/')
    // The order panel must be hidden so a broken link can never submit an order.
    expectNoOrderPanel()
  })

  it('restaurant cannot be loaded (404): shows a customer-readable message and stops', async () => {
    const { calls, fetchMock } = makeFetch()
    setSearch('?restaurant=ghost')
    renderMenu(fetchMock)

    await expectLoadError()
    expect(calls.some((call) => call.url.includes('/api/restaurants/'))).toBe(true)
    // The page must stop instead of fetching /api/menu/ghost.
    expectNoFetch(calls, '/api/menu/')
    // The order panel must be hidden when the restaurant is unavailable, and
    // the unhappy path must never reach the order submit.
    expectNoOrderPanel()
    expectNoFetch(calls, '/api/orders/')
  })

  it('menu fetch fails (HTTP 500, network error, malformed JSON): shows a customer-readable message and stops', async () => {
    for (const menuFailure of ['http-500', 'bad-json', 'network'] as const) {
      const { calls, fetchMock } = makeFetch({ menuFailure })
      setSearch(`?restaurant=${SLUG}`)
      renderMenu(fetchMock)

      await expectLoadError()
      expect(calls.some((call) => call.url.includes('/api/restaurants/'))).toBe(true)
      // No product list may be rendered when the menu fetch fails.
      expect(document.querySelector('.menu-section')).not.toBeInTheDocument()
      // The order panel must be hidden when the menu is unavailable, and the
      // unhappy path must never reach the order submit.
      expectNoOrderPanel()
      expectNoFetch(calls, '/api/orders/')

      cleanup()
    }
  })

  it('valid link: menu still loads and an order can still be submitted (unchanged happy path)', async () => {
    const user = userEvent.setup()
    const { calls, fetchMock } = makeFetch()
    setSearch(`?restaurant=${SLUG}`)
    const { alertMock } = renderMenu(fetchMock)

    await waitFor(() =>
      expect(calls.some((call) => call.url === `/api/menu/${SLUG}`)).toBe(true)
    )
    // The happy path must not show the error message.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    // Sections render as soon as the menu loads: the client sees the products
    // without touching anything. The rest of the flow and every assertion is
    // kept (cart and order payload do not change).
    await screen.findByText('Milanesa')

    await user.click(screen.getByRole('button', { name: 'Agregar Milanesa' }))

    // The redesigned phone-first cart is a floating bar; the order form lives
    // in the cart sheet it opens.
    await user.click(screen.getByRole('button', { name: 'Ver pedido' }))
    const cart = await screen.findByRole('complementary', { name: 'Tu pedido' })
    // The cart line shows the product, its quantity and the line total.
    expect(within(cart).getByText('Milanesa')).toBeInTheDocument()
    expect(within(cart).getByLabelText('Cantidad de Milanesa')).toHaveTextContent('1')
    expect(within(cart).getByText('$100')).toBeInTheDocument()
    expect(within(cart).getByText('Total: $100')).toBeInTheDocument()

    await user.type(screen.getByPlaceholderText('Nombre y Apellido'), 'Cliente Test')
    await user.click(screen.getByRole('button', { name: 'Hacer el pedido' }))

    await waitFor(() => {
      const orderCall = calls.find((call) => call.url.startsWith('/api/orders/menu/'))
      expect(orderCall).toBeDefined()
      // The order must still be submitted to /api/orders/menu/{slug} with the
      // legacy payload shape.
      expect(orderCall!.url).toBe(`/api/orders/menu/${SLUG}`)
      expect(orderCall!.method).toBe('POST')
      const payload = JSON.parse(orderCall!.body!)
      expect(payload.items).toEqual([{ productId: 101, quantity: 1 }])
      expect(payload.customerName).toBe('Cliente Test')
    })

    // The customer must still get the order confirmation.
    expect(alertMock.mock.calls.some(([message]) => String(message).includes('Pedido recibido'))).toBe(true)

    // The success reset is still full: cart UI gone and localStorage cleaned.
    expect(screen.queryByRole('complementary', { name: 'Tu pedido' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ver pedido' })).not.toBeInTheDocument()
    expect(localStorage.getItem('cart')).toBeNull()
  })
})
