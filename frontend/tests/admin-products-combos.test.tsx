/**
 * Admin products/combos contracts against the backend endpoints added in
 * cdaa4d0 (all authenticated by JWT and scoped by the caller's business):
 *
 *   1. GET /api/products is called with the Authorization header and without
 *      the legacy hardcoded `businessId` query parameter.
 *   2. The toggle is a real switch (role="switch" + aria-checked) with a
 *      meaningful accessible name, and sends the TARGET state:
 *      `activo=true` for an inactive product and `activo=false` for an
 *      active one (the legacy always sent activo=false, so activating never
 *      worked).
 *   3. Deleting asks for confirmation first (same ConfirmModal interaction
 *      as CategoriesTable): cancelling/Escape/backdrop issue no DELETE;
 *      confirming issues DELETE /api/products/{id} with the auth header.
 *      A 409 (product used by a combo) surfaces a readable message to the
 *      operator via toast instead of failing silently.
 *   4. GET /api/combos is called with the Authorization header.
 *   5. Search filters the loaded list client-side, case- AND accent-
 *      insensitively (Spanish catalogue: "César" matches "cesar"), with an
 *      explicit empty state when nothing matches.
 *   6. Create and edit live in a modal: create POSTs /api/products with the
 *      ProductCreateDTO fields and refreshes the list; edit prefills from
 *      the row and PUTs /api/products/{id}. A backend 400 keeps the modal
 *      open and shows the backend's Spanish message INLINE in the modal.
 */
import { act, cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Admin from '../src/pages/Admin'

/** JWT-shaped token with a far-future exp so the admin's session guard keeps the page up. */
function futureJwt(): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url')
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: 'demo', exp: 4102444800 })}.signature`
}

const TOKEN = futureJwt()

/** Full ProductDTO shape the backend actually returns on GET /api/products. */
const PRODUCTS = [
  {
    id: 1,
    name: 'Pizza Margherita',
    price: 8500,
    cost: 3000,
    description: 'Muzzarella y albahaca',
    stock: 10,
    active: true,
    categoryId: 3628,
    categoryName: 'Pizzas',
    unitMeasure: 'PORCION',
  },
  {
    id: 2,
    name: 'Combo Cantor',
    price: 12000,
    cost: 5000,
    description: 'Para compartir',
    stock: null,
    active: false,
    categoryId: 990011,
    categoryName: 'Hamburguesas',
    unitMeasure: 'UNIDAD',
  },
  {
    id: 3,
    name: 'Ensalada César',
    price: 6200,
    cost: 2500,
    description: 'Lechuga y pollo',
    stock: 5,
    active: true,
    categoryId: 990013,
    categoryName: 'Postres',
    unitMeasure: 'UNIDAD',
  },
]

const COMBOS = [{ id: 7, name: 'Combo Familiar', price: 15000, active: true, items: [] }]

const CATEGORIES = [
  { id: 990012, name: 'Bebidas', active: true },
  { id: 3628, name: 'Pizzas', active: true },
]

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

function okJson(body: unknown, status = 200): ResponseLike {
  return { ok: true, status, json: async () => body }
}

function noContent(): ResponseLike {
  return { ok: true, status: 204, json: async () => undefined }
}

function parseBody(init?: RequestInit): unknown {
  try {
    return JSON.parse(String(init?.body ?? '{}'))
  } catch {
    return {}
  }
}

interface StubHandlers {
  delete?: (id: string) => ResponseLike
  postProduct?: (body: unknown) => ResponseLike
  putProduct?: (id: string, body: unknown) => ResponseLike
}

/**
 * Fetch stub that records every call (url, method, headers) and routes:
 * dashboard calls fail quietly, products/categories/combos GETs serve the
 * fixtures, and the caller can override the DELETE /api/products/{id},
 * POST /api/products and PUT /api/products/{id} outcomes per test.
 */
function stubFetch(handlers: StubHandlers = {}) {
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
    if (url === '/api/categorias' && method === 'GET') return okJson(CATEGORIES)
    if (url === '/api/combos' && method === 'GET') return okJson(COMBOS)
    if (url === '/api/products' && method === 'POST') {
      return handlers.postProduct ? handlers.postProduct(parseBody(init)) : okJson({})
    }
    if (method === 'PUT' && /\/api\/products\/\d+\/activar/.test(url)) {
      return okJson(PRODUCTS.find((p) => url.startsWith(`/api/products/${p.id}/`)))
    }
    if (method === 'PUT' && /^\/api\/products\/\d+$/.test(url)) {
      return handlers.putProduct ? handlers.putProduct(url.split('/').pop()!, parseBody(init)) : okJson({})
    }
    if (method === 'DELETE' && /^\/api\/products\/\d+$/.test(url)) {
      return handlers.delete ? handlers.delete(url.split('/').pop()!) : noContent()
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

async function openAdminView(label: 'Productos' | 'Combos') {
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
    await openAdminView('Productos')

    expect(await screen.findByText('Pizza Margherita')).toBeInTheDocument()

    const requests = productRequests()
    expect(requests.length).toBeGreaterThan(0)
    expect(requests[0].headers['authorization']).toBe(`Bearer ${TOKEN}`)
    expect(new URL(requests[0].url, 'http://localhost').searchParams.has('businessId')).toBe(false)
    expect(requests[0].url).toBe('/api/products')
  })
})

describe('Admin products search', () => {
  it('filters client-side case- and accent-insensitively and shows an empty state when nothing matches', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openAdminView('Productos')

    expect(await screen.findByText('Ensalada César')).toBeInTheDocument()

    // Accent- AND case-insensitive: "CESAR" (no accent, upper case) matches "César".
    const search = screen.getByRole('searchbox', { name: 'Buscar productos por nombre' })
    await user.type(search, 'CESAR')
    expect(screen.getByText('Ensalada César')).toBeInTheDocument()
    expect(screen.queryByText('Pizza Margherita')).not.toBeInTheDocument()
    expect(screen.queryByText('Combo Cantor')).not.toBeInTheDocument()

    // Empty state when the filter matches nothing.
    await user.clear(search)
    await user.type(search, 'sushi xyz')
    expect(screen.getByText('No hay productos que coincidan con la búsqueda')).toBeInTheDocument()
    expect(screen.queryByText('Ensalada César')).not.toBeInTheDocument()
  })
})

describe('Admin product toggle (switch)', () => {
  it('renders each row toggle as a switch with aria-checked and a meaningful accessible name', async () => {
    stubFetch()
    await openAdminView('Productos')

    const activeSwitch = await screen.findByRole('switch', { name: 'Desactivar Pizza Margherita' })
    expect(activeSwitch).toHaveAttribute('aria-checked', 'true')

    const inactiveSwitch = screen.getByRole('switch', { name: 'Activar Combo Cantor' })
    expect(inactiveSwitch).toHaveAttribute('aria-checked', 'false')
  })

  it('sends activo=true for an inactive product and activo=false for an active one, then reloads the list', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openAdminView('Productos')

    expect(await screen.findByText('Combo Cantor')).toBeInTheDocument()

    // Toggle the inactive product (id 2): target state is active → activo=true.
    await user.click(screen.getByRole('switch', { name: 'Activar Combo Cantor' }))
    let toggle = calls.find((c) => c.method === 'PUT' && c.url.includes('/activar'))
    expect(toggle?.url).toBe('/api/products/2/activar?activo=true')
    expect(toggle?.headers['authorization']).toBe(`Bearer ${TOKEN}`)

    // Toggle the active product (id 1): target state is inactive → activo=false.
    await user.click(screen.getByRole('switch', { name: 'Desactivar Pizza Margherita' }))
    toggle = calls.filter((c) => c.method === 'PUT' && c.url.includes('/activar')).at(-1)
    expect(toggle?.url).toBe('/api/products/1/activar?activo=false')

    // Legacy behaviour kept: the list reloads after the mutation.
    expect(productRequests().length).toBeGreaterThanOrEqual(2)
  })
})

describe('Admin product create (modal)', () => {
  it('opens the modal, posts the ProductCreateDTO payload to /api/products and refreshes the list', async () => {
    const postProduct = vi.fn(() => okJson({ id: 99, name: 'Pizza Napolitana' }))
    stubFetch({ postProduct })
    const user = userEvent.setup()
    await openAdminView('Productos')

    expect(await screen.findByText('Pizza Margherita')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '+ Nuevo producto' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('Nombre'), 'Pizza Napolitana')
    await user.type(within(dialog).getByLabelText('Descripción'), 'Tomate, anchoas y muzzarella')
    await user.type(within(dialog).getByLabelText('Precio'), '9900')
    await user.type(within(dialog).getByLabelText('Costo'), '4000')
    await user.type(within(dialog).getByLabelText('Stock'), '3')
    await user.selectOptions(within(dialog).getByLabelText('Categoría'), '3628')
    await user.selectOptions(within(dialog).getByLabelText('Unidad de medida'), 'PORCION')

    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }))

    // The exact ProductCreateDTO the backend service validates.
    expect(postProduct).toHaveBeenCalledTimes(1)
    expect(postProduct.mock.calls[0][0]).toEqual({
      categoryId: 3628,
      name: 'Pizza Napolitana',
      description: 'Tomate, anchoas y muzzarella',
      price: 9900,
      cost: 4000,
      stock: 3,
      active: true,
      unitMeasure: 'PORCION',
    })

    // Success closes the modal and refreshes the list.
    await waitForModalToClose()
    expect(productRequests().length).toBeGreaterThanOrEqual(2)
  })

  it('keeps the modal open and shows the backend message INLINE when creation fails with 400', async () => {
    stubFetch({
      postProduct: () => ({
        ok: false,
        status: 400,
        json: async () => ({ message: 'El nombre es obligatorio' }),
      }),
    })
    const user = userEvent.setup()
    await openAdminView('Productos')

    expect(await screen.findByText('Pizza Margherita')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '+ Nuevo producto' }))
    const dialog = await screen.findByRole('dialog')

    await user.type(within(dialog).getByLabelText('Nombre'), 'Pizza sin precio')
    await user.type(within(dialog).getByLabelText('Descripción'), 'Faltan campos')
    await user.type(within(dialog).getByLabelText('Precio'), '100')
    await user.type(within(dialog).getByLabelText('Costo'), '50')
    await user.selectOptions(within(dialog).getByLabelText('Categoría'), '3628')

    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }))

    // The backend's Spanish message appears inline inside the modal (like the
    // profile form), NOT as a dismissible toast, and the modal stays open.
    const dialogAfter = screen.getByRole('dialog')
    expect(within(dialogAfter).getByText('El nombre es obligatorio')).toBeInTheDocument()

    // Nothing was created: no refresh of the list happened after the failure.
    expect(productRequests().length).toBe(1)
  })
})

describe('Admin product edit (modal)', () => {
  it('prefills the modal from the row and sends PUT /api/products/{id}, then refreshes', async () => {
    const putProduct = vi.fn(() => okJson({}))
    stubFetch({ putProduct })
    const user = userEvent.setup()
    await openAdminView('Productos')

    expect(await screen.findByText('Pizza Margherita')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Editar Pizza Margherita' }))
    const dialog = await screen.findByRole('dialog')

    // Prefilled from the row (full ProductDTO served by GET /api/products).
    expect(within(dialog).getByLabelText('Nombre')).toHaveValue('Pizza Margherita')
    expect(within(dialog).getByLabelText('Descripción')).toHaveValue('Muzzarella y albahaca')
    expect(within(dialog).getByLabelText('Precio')).toHaveValue(8500)
    expect(within(dialog).getByLabelText('Costo')).toHaveValue(3000)
    expect(within(dialog).getByLabelText('Categoría')).toHaveValue('3628')
    expect(within(dialog).getByLabelText('Stock')).toHaveValue(10)

    await user.clear(within(dialog).getByLabelText('Nombre'))
    await user.type(within(dialog).getByLabelText('Nombre'), 'Pizza Margherita XL')
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }))

    expect(putProduct).toHaveBeenCalledTimes(1)
    const [id, body] = putProduct.mock.calls[0]
    expect(id).toBe('1')
    expect(body).toMatchObject({
      id: 1,
      name: 'Pizza Margherita XL',
      description: 'Muzzarella y albahaca',
      price: 8500,
      cost: 3000,
      stock: 10,
      categoryId: 3628,
    })

    await waitForModalToClose()
    expect(productRequests().length).toBeGreaterThanOrEqual(2)
  })
})

describe('Admin product delete confirmation', () => {
  it('cancelling the modal (Escape) issues no DELETE, confirming issues DELETE /api/products/{id} with the auth header', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openAdminView('Productos')

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
    stubFetch({
      delete: () => ({
        ok: false,
        status: 409,
        json: async () => ({ message: 'No se puede eliminar: el producto pertenece a un combo' }),
      }),
    })
    const user = userEvent.setup()
    await openAdminView('Productos')

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
    await openAdminView('Combos')

    expect(await screen.findByText('Combo Familiar')).toBeInTheDocument()

    const request = calls.find((c) => c.url === '/api/combos' && c.method === 'GET')
    expect(request).toBeDefined()
    expect(request?.headers['authorization']).toBe(`Bearer ${TOKEN}`)
  })
})

/** Waits until the product modal is gone (success path closes it). */
async function waitForModalToClose() {
  const deadline = Date.now() + 2000
  while (Date.now() < deadline) {
    if (!document.querySelector('[role="dialog"]')) return
    await new Promise((r) => setTimeout(r, 25))
  }
  throw new Error('el modal del producto seguía abierto')
}

describe('Admin tables loading state', () => {
  it('shows the dashboard-style skeleton while the catalog loads', async () => {
    let resolveProducts!: (value: ResponseLike) => void
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
        const method = (init?.method ?? 'GET').toUpperCase()
        if (url === '/api/products' && !url.includes('/activar') && method === 'GET') {
          return new Promise<ResponseLike>((resolve) => {
            resolveProducts = resolve
          })
        }
        if (url === '/api/categorias' && method === 'GET') return okJson(CATEGORIES)
        if (url === '/api/combos' && method === 'GET') return okJson(COMBOS)
        return { ok: false, status: 500, json: async () => [] }
      })
    )
    seedStorage()
    await openAdminView('Productos')

    // While the catalog is pending: the dashboard-style skeleton, announced
    // to screen readers (role=status + aria-label) instead of an empty table.
    expect(screen.getByRole('status', { name: /cargando productos/i })).toBeInTheDocument()
    expect(document.querySelectorAll('.skeleton-line').length).toBeGreaterThan(0)

    act(() => resolveProducts(okJson(PRODUCTS)))
    expect(await screen.findByText('Pizza Margherita')).toBeInTheDocument()
    expect(screen.queryByRole('status', { name: /cargando productos/i })).not.toBeInTheDocument()
  })
})
