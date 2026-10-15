/**
 * Admin products/combos contracts against the backend endpoints added in
 * cdaa4d0 (all authenticated by JWT and scoped by the caller's business):
 *
 *   1. GET /api/products is called with the Authorization header and without
 *      the legacy hardcoded `businessId` query parameter.
 *   2. The ON/OFF toggle sends the TARGET state: `activo=true` for an
 *      inactive product and `activo=false` for an active one (the legacy
 *      always sent activo=false, so activating never worked).
 *   3. Deleting asks for confirmation first (same ConfirmModal interaction
 *      as CategoriesTable): cancelling/Escape/backdrop issue no DELETE;
 *      confirming issues DELETE /api/products/{id} with the auth header.
 *      A 409 (product used by a combo) surfaces a readable message to the
 *      operator via toast instead of failing silently.
 *   4. GET /api/combos is called with the Authorization header.
 */
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Admin from '../src/pages/Admin'

/** JWT-shaped token with a far-future exp so the admin's session guard keeps the page up. */
function futureJwt(): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url')
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: 'demo', exp: 4102444800 })}.signature`
}

const TOKEN = futureJwt()

const PRODUCTS = [
  { id: 1, name: 'Pizza Margherita', price: 8500, stock: 10, active: true },
  { id: 2, name: 'Combo Cantor', price: 12000, stock: null, active: false },
]

const COMBOS = [{ id: 7, name: 'Combo Familiar', price: 15000, active: true, items: [] }]

interface RecordedCall {
  url: string
  method: string
  headers: Record<string, string>
}

let calls: RecordedCall[] = []

interface ResponseLike {
  ok: boolean
  status: number
  json: () => Promise<unknown>
}

function okJson(body: unknown): ResponseLike {
  return { ok: true, status: 200, json: async () => body }
}

function noContent(): ResponseLike {
  return { ok: true, status: 204, json: async () => undefined }
}

/**
 * Fetch stub that records every call (url, method, headers) and routes:
 * dashboard calls fail quietly, products/combos GETs serve the fixtures, and
 * the caller can override the DELETE /api/products/{id} outcome per test.
 */
function stubFetch(deleteHandler?: (id: string) => ResponseLike) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    const method = (init?.method ?? 'GET').toUpperCase()
    calls.push({
      url,
      method,
      headers: Object.fromEntries(
        Object.entries((init?.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]),
      ),
    })
    if (url.startsWith('/api/products') && !url.includes('/activar') && method === 'GET') return okJson(PRODUCTS)
    if (url === '/api/combos' && method === 'GET') return okJson(COMBOS)
    if (method === 'PUT' && /\/api\/products\/\d+\/activar/.test(url)) {
      return okJson(PRODUCTS.find((p) => url.startsWith(`/api/products/${p.id}/`)))
    }
    if (method === 'DELETE' && /^\/api\/products\/\d+$/.test(url)) {
      return deleteHandler ? deleteHandler(url.split('/').pop()!) : noContent()
    }
    // Dashboard loaders (and anything unexpected): fail quietly like the
    // stubFailingFetch seam in admin-panel.test.tsx.
    return { ok: false, status: 500, json: async () => [] }
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function seedStorage() {
  localStorage.setItem('token', TOKEN)
  localStorage.setItem('businessId', '1')
  localStorage.setItem('role', 'ADMIN')
  localStorage.setItem('username', 'demo')
  localStorage.setItem('expiresAt', '2030-01-01T00:00:00Z')
}

async function openAdminView(label: '🍔 Productos' | '🔥 Combos') {
  render(<Admin />)
  await screen.findByText('PRODUCTOS', {}, { timeout: 2000 }).catch(() => undefined)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: label }))
}

function productRequests(): RecordedCall[] {
  return calls.filter((c) => c.method === 'GET' && c.url.startsWith('/api/products'))
}

beforeEach(() => {
  localStorage.clear()
  seedStorage()
  calls = []
})

afterEach(() => {
  cleanup()
})

describe('Admin products list', () => {
  it('requests GET /api/products with the Bearer header and no businessId query parameter', async () => {
    stubFetch()
    await openAdminView('🍔 Productos')

    expect(await screen.findByText('Pizza Margherita')).toBeInTheDocument()

    const requests = productRequests()
    expect(requests.length).toBeGreaterThan(0)
    expect(requests[0].headers['authorization']).toBe(`Bearer ${TOKEN}`)
    expect(new URL(requests[0].url, 'http://localhost').searchParams.has('businessId')).toBe(false)
    expect(requests[0].url).toBe('/api/products')  })
})

describe('Admin product toggle (ON/OFF)', () => {
  it('sends activo=true for an inactive product and activo=false for an active one, then reloads the list', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openAdminView('🍔 Productos')

    expect(await screen.findByText('Combo Cantor')).toBeInTheDocument()

    // Toggle the inactive product (id 2): target state is active → activo=true.
    await user.click(screen.getByRole('button', { name: 'Activar o desactivar Combo Cantor' }))
    let toggle = calls.find((c) => c.method === 'PUT' && c.url.includes('/activar'))
    expect(toggle?.url).toBe('/api/products/2/activar?activo=true')
    expect(toggle?.headers['authorization']).toBe(`Bearer ${TOKEN}`)

    // Toggle the active product (id 1): target state is inactive → activo=false.
    await user.click(screen.getByRole('button', { name: 'Activar o desactivar Pizza Margherita' }))
    toggle = calls.filter((c) => c.method === 'PUT' && c.url.includes('/activar')).at(-1)
    expect(toggle?.url).toBe('/api/products/1/activar?activo=false')

    // Legacy behaviour kept: the list reloads after the mutation.
    expect(productRequests().length).toBeGreaterThanOrEqual(2)
  })
})

describe('Admin product delete confirmation', () => {
  it('cancelling the modal (Escape) issues no DELETE, confirming issues DELETE /api/products/{id} with the auth header', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openAdminView('🍔 Productos')

    expect(await screen.findByText('Pizza Margherita')).toBeInTheDocument()

    // Ask to delete: the confirmation modal appears, no DELETE yet.
    await user.click(screen.getByRole('button', { name: 'Eliminar Pizza Margherita' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)

    // Escape aborts: still no DELETE.
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)

    // Confirm this time: DELETE goes out with the auth header.
    await user.click(screen.getByRole('button', { name: 'Eliminar Pizza Margherita' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const del = calls.find((c) => c.method === 'DELETE')
    expect(del?.url).toBe('/api/products/1')
    expect(del?.headers['authorization']).toBe(`Bearer ${TOKEN}`)
  })

  it('surfaces a readable message when the backend answers 409 (product used by a combo) and keeps the view alive', async () => {
    stubFetch(() => ({
      ok: false,
      status: 409,
      json: async () => ({ message: 'No se puede eliminar: el producto pertenece a un combo' }),
    }))
    const user = userEvent.setup()
    await openAdminView('🍔 Productos')

    expect(await screen.findByText('Pizza Margherita')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Eliminar Pizza Margherita' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    // The 409's message reaches the operator through the toast.
    expect(await screen.findByText(/No se puede eliminar: el producto pertenece a un combo/)).toBeInTheDocument()

    // No crash: the products table is still rendered.
    expect(screen.getByText('Combo Cantor')).toBeInTheDocument()
  })
})

describe('Admin combos list', () => {
  it('requests GET /api/combos with the Bearer header', async () => {
    stubFetch()
    await openAdminView('🔥 Combos')

    expect(await screen.findByText('Combo Familiar')).toBeInTheDocument()

    const request = calls.find((c) => c.url === '/api/combos' && c.method === 'GET')
    expect(request).toBeDefined()
    expect(request?.headers['authorization']).toBe(`Bearer ${TOKEN}`)
  })
})
