/**
 * Admin categories contracts (unit 3 of odd/tasks/admin-panel-ui.md), same
 * treatment the products table got in cdaa4d0/PR #40:
 *
 *   1. Search filters the loaded list client-side, case- AND accent-
 *      insensitively ("Bebidas Frías" matches "BEBIDAS FRIAS"), with an
 *      explicit empty state when nothing matches. Same toolbar skin as the
 *      products table (products-toolbar classes in pages/Admin.css).
 *   2. Create still works: "+ Nuevo" opens CreateCategoryModal and POSTs
 *      /api/categorias with {name, order, active: true} where order is
 *      table rows + 1 (the legacy read the tbody), then refreshes the list.
 *   3. Edit is the inline rename that already existed, now DISCOVERABLE:
 *      a pencil row action (aria-label "Editar {name}") opens the same
 *      prefilled inline input, and saving sends PUT /api/categorias/{id}
 *      with the auth header and refreshes the list. The backend has no
 *      delete endpoint for categories, so the row offers none.
 *   4. The state control is the same real switch the products table has
 *      (role="switch" + aria-checked + accessible name naming the action
 *      and the row), sending the TARGET state, with no confirmation step —
 *      same interaction as products for the same concept.
 *   5. A backend 400 on save keeps the editing input open and shows the
 *      backend's Spanish message INLINE at the editing row (role="alert"),
 *      not as a dismissible toast. Like the products save, no confirmation
 *      step precedes the PUT.
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

const CATEGORIES = [
  { id: 11, name: 'Pizzas', order: 1, active: true },
  { id: 12, name: 'Bebidas Frías', order: 2, active: false },
  { id: 13, name: 'Postres', order: 3, active: true },
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
  postCategory?: (body: unknown) => ResponseLike
  putCategory?: (id: string, body: unknown) => ResponseLike
}

/**
 * Fetch stub that records every call (url, method, headers) and routes the
 * categories endpoints; everything else (dashboard, KDS, carta, perfil del
 * negocio) fails quietly like the stubFailingFetch seam in admin-panel.test.tsx.
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
    if (url === '/api/categorias' && method === 'GET') return okJson(CATEGORIES)
    if (url === '/api/categorias' && method === 'POST') {
      return handlers.postCategory ? handlers.postCategory(parseBody(init)) : okJson({})
    }
    if (method === 'PUT' && /^\/api\/categorias\/\d+$/.test(url)) {
      return handlers.putCategory ? handlers.putCategory(url.split('/').pop()!, parseBody(init)) : okJson({})
    }
    if (url === '/api/categorias/orden' && method === 'PUT') return okJson({})
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

async function openCategoriesView() {
  render(<Admin />)
  await screen.findByText('Panel de gestión', {}, { timeout: 2000 }).catch(() => undefined)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Categorías' }))
}

function categoryListRequests(): RecordedCall[] {
  return calls.filter((c) => c.method === 'GET' && c.url === '/api/categorias')
}

beforeEach(() => {
  localStorage.clear()
  seedStorage()
  calls = []
})

afterEach(() => {
  cleanup()
})

describe('Admin categories search', () => {
  it('filters client-side case- and accent-insensitively and shows an empty state when nothing matches', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openCategoriesView()

    expect(await screen.findByText('Bebidas Frías')).toBeInTheDocument()
    expect(screen.getByText('Pizzas')).toBeInTheDocument()
    expect(screen.getByText('Postres')).toBeInTheDocument()

    // Accent- AND case-insensitive: "BEBIDAS FRIAS" (no accent, upper case) matches "Bebidas Frías".
    const search = screen.getByRole('searchbox', { name: 'Buscar categorías por nombre' })
    await user.type(search, 'BEBIDAS FRIAS')
    expect(screen.getByText('Bebidas Frías')).toBeInTheDocument()
    expect(screen.queryByText('Pizzas')).not.toBeInTheDocument()
    expect(screen.queryByText('Postres')).not.toBeInTheDocument()

    // Empty state when the filter matches nothing.
    await user.clear(search)
    await user.type(search, 'sushi xyz')
    expect(screen.getByText('No hay categorías que coincidan con la búsqueda')).toBeInTheDocument()
    expect(screen.queryByText('Bebidas Frías')).not.toBeInTheDocument()
  })
})

describe('Admin categories create', () => {
  it('still works: the modal POSTs {name, order, active: true} with order = rows + 1 and refreshes the list', async () => {
    const postCategory = vi.fn(() => okJson({ id: 14, name: 'Empanadas', order: 4, active: true }))
    stubFetch({ postCategory })
    const user = userEvent.setup()
    await openCategoriesView()

    expect(await screen.findByText('Pizzas')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '+ Nuevo' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Crear nueva categoría')).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('Nombre de la categoría'), 'Empanadas')
    await user.click(within(dialog).getByRole('button', { name: 'Crear' }))

    // The exact payload the legacy sent (admin.js:517-519 read the tbody): rows + 1.
    expect(postCategory).toHaveBeenCalledTimes(1)
    expect(postCategory.mock.calls[0][0]).toEqual({ name: 'Empanadas', order: 4, active: true })

    // Success closes the modal and refreshes the list.
    const deadline = Date.now() + 2000
    while (Date.now() < deadline && document.querySelector('[role="dialog"]')) {
      await new Promise((r) => setTimeout(r, 25))
    }
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(categoryListRequests().length).toBeGreaterThanOrEqual(2)
  })
})

describe('Admin categories edit (inline rename, now discoverable)', () => {
  it('the pencil row action opens the inline rename prefilled and sends PUT /api/categorias/{id}, then refreshes', async () => {
    const putCategory = vi.fn(() => okJson({ id: 11, name: 'Pizzas Gourmet', order: 1, active: true }))
    stubFetch({ putCategory })
    const user = userEvent.setup()
    await openCategoriesView()

    expect(await screen.findByText('Pizzas')).toBeInTheDocument()

    // Visible affordance in the actions column, named after the row.
    await user.click(screen.getByRole('button', { name: 'Editar Pizzas' }))

    // Same inline editing state as before, prefilled from the row.
    const input = screen.getByRole('textbox', { name: 'Nuevo nombre para Pizzas' })
    expect(input).toHaveValue('Pizzas')

    await user.clear(input)
    await user.type(input, 'Pizzas Gourmet')
    await user.keyboard('{Enter}')

    // No confirmation step: Enter saves directly, like the products modal's Guardar.
    expect(putCategory).toHaveBeenCalledTimes(1)
    const [id, body] = putCategory.mock.calls[0]
    expect(id).toBe('11')
    expect(body).toEqual({ name: 'Pizzas Gourmet' })
    const put = calls.find((c) => c.method === 'PUT' && /^\/api\/categorias\/\d+$/.test(c.url))
    expect(put?.headers['authorization']).toBe(`Bearer ${TOKEN}`)

    // The list refreshes after saving.
    expect(categoryListRequests().length).toBeGreaterThanOrEqual(2)
  })

  it('offers no delete action: the backend has no delete endpoint for categories', async () => {
    stubFetch()
    await openCategoriesView()

    expect(await screen.findByText('Pizzas')).toBeInTheDocument()
    expect(screen.queryByLabelText(/Eliminar/)).not.toBeInTheDocument()
    expect(screen.queryByText('🗑')).not.toBeInTheDocument()
  })
})

describe('Admin categories toggle (switch)', () => {
  it('renders each row toggle as a switch with aria-checked and a meaningful accessible name, and sends the target state without confirmation', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openCategoriesView()

    // Active row: the switch says what clicking does — "Desactivar".
    const activeSwitch = await screen.findByRole('switch', { name: 'Desactivar Pizzas' })
    expect(activeSwitch).toHaveAttribute('aria-checked', 'true')

    // Inactive row: "Activar".
    const inactiveSwitch = screen.getByRole('switch', { name: 'Activar Bebidas Frías' })
    expect(inactiveSwitch).toHaveAttribute('aria-checked', 'false')

    // Same interaction as products: the toggle fires immediately, no confirm modal.
    await user.click(inactiveSwitch)

    const put = calls.find((c) => c.method === 'PUT' && /^\/api\/categorias\/\d+$/.test(c.url))
    expect(put?.url).toBe('/api/categorias/12')
    expect(put?.headers['authorization']).toBe(`Bearer ${TOKEN}`)
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})

describe('Admin categories save error', () => {
  it('keeps the inline editing open and shows the backend 400 message INLINE at the editing row', async () => {
    stubFetch({
      putCategory: () => ({
        ok: false,
        status: 400,
        json: async () => ({ message: 'El nombre de la categoría es obligatorio' }),
      }),
    })
    const user = userEvent.setup()
    await openCategoriesView()

    expect(await screen.findByText('Pizzas')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Editar Pizzas' }))
    const input = screen.getByRole('textbox', { name: 'Nuevo nombre para Pizzas' })
    await user.clear(input)
    await user.type(input, 'Pizzas Gourmet')
    await user.keyboard('{Enter}')

    // The backend's Spanish message appears inline (role="alert") at the
    // editing row — NOT as a dismissible toast — and the input stays open.
    expect(await screen.findByRole('alert')).toHaveTextContent('El nombre de la categoría es obligatorio')
    expect(screen.getByRole('textbox', { name: 'Nuevo nombre para Pizzas' })).toBeInTheDocument()
  })
})
