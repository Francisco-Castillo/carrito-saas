/**
 * Admin combos CRUD contracts (unidad 5 del ODD admin-panel-ui):
 *
 *   1. Search filters the loaded combo list client-side, case- AND accent-
 *      insensitively (Spanish catalogue: "César" matches "CESAR"), with an
 *      explicit empty state when nothing matches.
 *   2. Create lives in a modal: "+ Nuevo combo" opens it and Guardar POSTs
 *      /api/combos with the ComboCreateDTO fields (name, price, categoryId,
 *      products [{productId, quantity}]) and refreshes the list.
 *   3. Edit prefills the modal from the row (name, price, category and the
 *      items with their quantities) and PUTs /api/combos/{id}.
 *   4. The product picker adds and removes rows.
 *   5. A backend 400 keeps the modal open and shows the backend's Spanish
 *      message INLINE in the modal.
 */
import { cleanup, render, screen, within } from '@testing-library/react'
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
  {
    id: 1,
    name: 'Pizza Margherita',
    price: 8500,
    cost: 3000,
    description: 'Muzzarella y albahaca',
    stock: 10,
    active: true,
    categoryId: 3628,
    unitMeasure: 'PORCION',
  },
  {
    id: 2,
    name: 'Hamburguesa Completa',
    price: 9500,
    cost: 4000,
    description: 'Con papas',
    stock: null,
    active: true,
    categoryId: 990011,
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
    unitMeasure: 'UNIDAD',
  },
  {
    // Mismo nombre que el id 3, otra categoría: el prefill por nombre no
    // puede distinguirlos — el item del combo tiene que viajar con productId.
    id: 4,
    name: 'Ensalada César',
    price: 7000,
    cost: 3000,
    description: 'Lechuga y pollo (lleno)',
    stock: 8,
    active: true,
    categoryId: 990014,
    unitMeasure: 'UNIDAD',
  },
]

/** Full ComboDTO shape the backend returns on GET /api/combos (items included). */
const COMBOS = [
  {
    id: 7,
    name: 'Combo Familiar',
    price: 15000,
    categoryId: 990012,
    items: [{ productId: 1, productName: 'Pizza Margherita', quantity: 2 }],
  },
  {
    id: 8,
    name: 'Combo César',
    price: 9900,
    categoryId: 3628,
    items: [{ productId: 3, productName: 'Ensalada César', quantity: 1 }],
  },
  {
    // Payload sin productId: el contrato del backend (ComboItemDTO desde el
    // PR #52) siempre lo trae, así que este caso sólo puede venir de un
    // payload viejo o corrupto. El prefill no puede adivinar por nombre:
    // la fila nace vacía y el operador elige, en lugar de un pick silencioso
    // que puede caer en el producto equivocado cuando el nombre se repite.
    id: 9,
    name: 'Combo Sin Id',
    price: 5000,
    categoryId: 3628,
    items: [{ productName: 'Ensalada César', quantity: 2 }],
  },
]

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

function parseBody(init?: RequestInit): unknown {
  try {
    return JSON.parse(String(init?.body ?? '{}'))
  } catch {
    return {}
  }
}

interface StubHandlers {
  postCombo?: (body: unknown) => ResponseLike
  putCombo?: (id: string, body: unknown) => ResponseLike
}

/**
 * Fetch stub that records every call (url, method, headers) and routes:
 * products/categories/combos GETs serve the fixtures and the caller can
 * override the POST /api/combos and PUT /api/combos/{id} outcomes per test.
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
    if (url === '/api/products' && method === 'GET') return okJson(PRODUCTS)
    if (url === '/api/categorias' && method === 'GET') return okJson(CATEGORIES)
    if (url === '/api/combos' && method === 'GET') return okJson(COMBOS)
    if (url === '/api/combos' && method === 'POST') {
      return handlers.postCombo ? handlers.postCombo(parseBody(init)) : okJson({ id: 99 })
    }
    if (method === 'PUT' && /^\/api\/combos\/\d+$/.test(url)) {
      return handlers.putCombo
        ? handlers.putCombo(url.split('/').pop()!, parseBody(init))
        : okJson({ id: Number(url.split('/').pop()) })
    }
    // Dashboard loaders (and anything unexpected): fail quietly.
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

async function openCombosView() {
  render(<Admin />)
  await screen.findByText('PRODUCTOS', {}, { timeout: 2000 }).catch(() => undefined)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Combos' }))
}

beforeEach(() => {
  localStorage.clear()
  seedStorage()
  calls = []
})

afterEach(() => {
  cleanup()
})

describe('Admin combos search', () => {
  it('filters client-side case- and accent-insensitively and shows an empty state when nothing matches', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openCombosView()

    expect(await screen.findByText('Combo Familiar')).toBeInTheDocument()

    // Accent- AND case-insensitive: "CESAR" (no accent, upper case) matches "César".
    const search = screen.getByRole('searchbox', { name: 'Buscar combos por nombre' })
    await user.type(search, 'CESAR')
    expect(screen.getByText('Combo César')).toBeInTheDocument()
    expect(screen.queryByText('Combo Familiar')).not.toBeInTheDocument()

    // Empty state when the filter matches nothing.
    await user.clear(search)
    await user.type(search, 'sushi xyz')
    expect(screen.getByText('No hay combos que coincidan con la búsqueda')).toBeInTheDocument()
    expect(screen.queryByText('Combo César')).not.toBeInTheDocument()
  })
})

describe('Admin combo create (modal)', () => {
  it('opens the modal, lets the picker build the item rows and POSTs the ComboCreateDTO payload', async () => {
    const postCombo = vi.fn(() => okJson({ id: 99, name: 'Combo Nuevo' }))
    stubFetch({ postCombo })
    const user = userEvent.setup()
    await openCombosView()

    expect(await screen.findByText('Combo Familiar')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '+ Nuevo combo' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('Nombre'), 'Combo Nuevo')
    await user.type(within(dialog).getByLabelText('Precio'), '12000')
    await user.selectOptions(within(dialog).getByLabelText('Categoría'), '990012')

    // The picker starts with no rows: add one, pick a product and a quantity.
    await user.click(within(dialog).getByRole('button', { name: '+ Agregar producto' }))
    await user.selectOptions(within(dialog).getByLabelText('Producto del combo'), '1')
    // La fila arranca con cantidad 1: se limpia antes de tipear la destino.
    await user.clear(within(dialog).getByLabelText('Cantidad'))
    await user.type(within(dialog).getByLabelText('Cantidad'), '2')

    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }))

    // The exact ComboCreateDTO the backend service validates.
    expect(postCombo).toHaveBeenCalledTimes(1)
    expect(postCombo.mock.calls[0][0]).toEqual({
      name: 'Combo Nuevo',
      price: 12000,
      categoryId: 990012,
      products: [{ productId: 1, quantity: 2 }],
    })

    await waitForModalToClose()
    // Success closes the modal and refreshes the list.
    expect(calls.filter((c) => c.url === '/api/combos' && c.method === 'GET').length).toBeGreaterThanOrEqual(2)
  })
})

describe('Admin combo edit (modal)', () => {
  it('prefills name, price, category and items with quantities, then sends PUT /api/combos/{id}', async () => {
    const putCombo = vi.fn(() => okJson({ id: 7 }))
    stubFetch({ putCombo })
    const user = userEvent.setup()
    await openCombosView()

    expect(await screen.findByText('Combo Familiar')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Editar Combo Familiar' }))
    const dialog = await screen.findByRole('dialog')

    // Prefilled from the row (full ComboDTO served by GET /api/combos).
    expect(within(dialog).getByLabelText('Nombre')).toHaveValue('Combo Familiar')
    expect(within(dialog).getByLabelText('Precio')).toHaveValue(15000)
    expect(within(dialog).getByLabelText('Categoría')).toHaveValue('990012')

    // The one seeded item (Pizza Margherita x2) comes back as a picker row.
    const productSelect = within(dialog).getByLabelText('Producto del combo')
    expect(productSelect).toHaveValue('1')
    expect(within(dialog).getByLabelText('Cantidad')).toHaveValue(2)

    await user.clear(within(dialog).getByLabelText('Precio'))
    await user.type(within(dialog).getByLabelText('Precio'), '18000')
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }))

    expect(putCombo).toHaveBeenCalledTimes(1)
    const [id, body] = putCombo.mock.calls[0]
    expect(id).toBe('7')
    expect(body).toMatchObject({
      name: 'Combo Familiar',
      price: 18000,
      categoryId: 990012,
      products: [{ productId: 1, quantity: 2 }],
    })

    await waitForModalToClose()
    expect(calls.filter((c) => c.url === '/api/combos' && c.method === 'GET').length).toBeGreaterThanOrEqual(2)
  })

  it('resolves the picker by productId when two products share a name', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openCombosView()

    expect(await screen.findByText('Combo César')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Editar Combo César' }))
    const dialog = await screen.findByRole('dialog')

    // Dos productos se llaman "Ensalada César" (ids 3 y 4, categorías
    // distintas). El item del combo apunta al id 3: el prefill tiene que
    // usar ese productId y no el primer (o último) match por nombre.
    const productSelect = within(dialog).getByLabelText('Producto del combo')
    expect(productSelect).toHaveValue('3')
    // La cantidad viaja intacta con el item.
    expect(within(dialog).getByLabelText('Cantidad')).toHaveValue(1)
  })

  it('never pre-fills by product name when an item carries no productId', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openCombosView()

    expect(await screen.findByText('Combo Sin Id')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Editar Combo Sin Id' }))
    const dialog = await screen.findByRole('dialog')

    // El item viaja sin productId y "Ensalada César" existe dos veces (ids 3
    // y 4): el cruce por nombre podría elegir cualquiera y el operador no se
    // enteraría. El prefill deja la fila vacía; el operador elige.
    expect(within(dialog).getByLabelText('Producto del combo')).toHaveValue('')
    expect(within(dialog).getByLabelText('Cantidad')).toHaveValue(2)
  })
})

describe('Admin combo product picker', () => {
  it('adds and removes item rows inside the modal', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openCombosView()

    expect(await screen.findByText('Combo Familiar')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '+ Nuevo combo' }))
    const dialog = await screen.findByRole('dialog')

    // Starts empty; each "+ Agregar producto" adds one row.
    expect(within(dialog).queryByLabelText('Producto del combo')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '+ Agregar producto' }))
    expect(within(dialog).getByLabelText('Producto del combo')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '+ Agregar producto' }))
    expect(within(dialog).getAllByLabelText('Producto del combo')).toHaveLength(2)

    // Removing a row takes out exactly one.
    await user.click(within(dialog).getAllByRole('button', { name: 'Quitar producto' })[0])
    expect(within(dialog).getAllByLabelText('Producto del combo')).toHaveLength(1)
  })
})

describe('Admin combo backend error', () => {
  it('keeps the modal open and shows the backend message INLINE when saving fails with 400', async () => {
    stubFetch({
      putCombo: () => ({
        ok: false,
        status: 400,
        json: async () => ({ message: 'El precio del combo debe ser mayor que cero' }),
      }),
    })
    const user = userEvent.setup()
    await openCombosView()

    expect(await screen.findByText('Combo Familiar')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Editar Combo Familiar' }))
    const dialog = await screen.findByRole('dialog')

    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }))

    // The backend's Spanish message appears inline inside the modal, NOT as
    // a dismissible toast, and the modal stays open.
    const dialogAfter = screen.getByRole('dialog')
    expect(within(dialogAfter).getByText('El precio del combo debe ser mayor que cero')).toBeInTheDocument()
  })
})

/** Waits until the combo modal is gone (success path closes it). */
async function waitForModalToClose() {
  const deadline = Date.now() + 2000
  while (Date.now() < deadline) {
    if (!document.querySelector('[role="dialog"]')) return
    await new Promise((r) => setTimeout(r, 25))
  }
  throw new Error('el modal del combo seguía abierto')
}
