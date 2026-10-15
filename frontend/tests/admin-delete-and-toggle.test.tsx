/**
 * Delete contracts for the two admin tables that lacked it (admin-delete-and-toggle):
 *
 *   1. CategoriesTable: a 🗑 action in Acciones opens the same ConfirmModal
 *      the products table uses; Escape/backdrop/cancel issue no DELETE;
 *      confirming sends DELETE /api/categorias/{id} with the auth header and
 *      refreshes the list. A 409 (the category still has products) surfaces
 *      the backend's Spanish message through the toast, not a generic error.
 *   2. CombosTable: the same delete interaction, sending
 *      DELETE /api/combos/{id}, with the same 409 treatment.
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

const CATEGORIES = [
  { id: 11, name: 'Pizzas', order: 1, active: true },
  { id: 12, name: 'Bebidas Frías', order: 2, active: false },
]

const COMBOS = [
  { id: 7, name: 'Combo Familiar', price: 15000, active: true, items: [] },
  { id: 8, name: 'Combo Pausado', price: 9000, active: false, items: [] },
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

interface StubHandlers {
  deleteCategory?: (id: string) => ResponseLike
  deleteCombo?: (id: string) => ResponseLike
}

/**
 * Fetch stub that records every call (url, method, headers) and routes the
 * list endpoints; the caller can override the DELETE outcomes per test.
 * Everything else fails quietly like the seam in admin-panel.test.tsx.
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
    if (url === '/api/combos' && method === 'GET') return okJson(COMBOS)
    if (url === '/api/products' && method === 'GET') return okJson([])
    if (method === 'PUT' && /\/api\/combos\/\d+\/activar/.test(url)) {
      return okJson(COMBOS.find((c) => url.startsWith(`/api/combos/${c.id}/`)))
    }
    if (method === 'DELETE' && /^\/api\/categorias\/\d+$/.test(url)) {
      return handlers.deleteCategory ? handlers.deleteCategory(url.split('/').pop()!) : noContent()
    }
    if (method === 'DELETE' && /^\/api\/combos\/\d+$/.test(url)) {
      return handlers.deleteCombo ? handlers.deleteCombo(url.split('/').pop()!) : noContent()
    }
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

async function openAdminView(label: 'Categorías' | 'Combos') {
  render(<Admin />)
  await screen.findByText('Panel de gestión', {}, { timeout: 2000 }).catch(() => undefined)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: label }))
}

function listRequests(path: string): RecordedCall[] {
  return calls.filter((c) => c.method === 'GET' && c.url === path)
}

beforeEach(() => {
  localStorage.clear()
  seedStorage()
  calls = []
})

afterEach(() => {
  cleanup()
})

describe('Admin category delete', () => {
  it('the confirm modal gates the DELETE: cancelling issues none, confirming sends DELETE /api/categorias/{id} with the auth header and refreshes', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openAdminView('Categorías')

    expect(await screen.findByText('Pizzas')).toBeInTheDocument()

    // Ask to delete: the confirmation modal appears, no DELETE yet.
    await user.click(screen.getByRole('button', { name: 'Eliminar Pizzas' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)

    // Escape aborts: still no DELETE.
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)

    // Confirm this time: DELETE goes out with the auth header, then the list reloads.
    await user.click(screen.getByRole('button', { name: 'Eliminar Pizzas' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const del = calls.find((c) => c.method === 'DELETE')
    expect(del?.url).toBe('/api/categorias/11')
    expect(del?.headers['authorization']).toBe(`Bearer ${TOKEN}`)
    expect(listRequests('/api/categorias').length).toBeGreaterThanOrEqual(2)
  })

  it('surfaces the backend 409 message (category still has products) through the toast and keeps the view alive', async () => {
    stubFetch({
      deleteCategory: () => ({
        ok: false,
        status: 409,
        json: async () => ({ message: 'La categoría tiene productos asociados y no puede eliminarse' }),
      }),
    })
    const user = userEvent.setup()
    await openAdminView('Categorías')

    expect(await screen.findByText('Pizzas')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Eliminar Pizzas' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    // The backend's message, verbatim, not a generic failure.
    expect(
      await screen.findByText(/La categoría tiene productos asociados y no puede eliminarse/),
    ).toBeInTheDocument()

    // No crash: the categories table is still rendered.
    expect(screen.getByText('Bebidas Frías')).toBeInTheDocument()
  })
})

describe('Admin combo toggle (switch)', () => {
  it('renders each row toggle as a switch with aria-checked and a state-based accessible name', async () => {
    stubFetch()
    await openAdminView('Combos')

    const activeSwitch = await screen.findByRole('switch', { name: 'Desactivar Combo Familiar' })
    expect(activeSwitch).toHaveAttribute('aria-checked', 'true')

    const inactiveSwitch = screen.getByRole('switch', { name: 'Activar Combo Pausado' })
    expect(inactiveSwitch).toHaveAttribute('aria-checked', 'false')
  })

  it('sends activo=true for an inactive combo and activo=false for an active one, then reloads the list', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openAdminView('Combos')

    expect(await screen.findByText('Combo Pausado')).toBeInTheDocument()

    // Inactive combo (id 8): target state is active → activo=true.
    await user.click(screen.getByRole('switch', { name: 'Activar Combo Pausado' }))
    let toggle = calls.find((c) => c.method === 'PUT' && c.url.includes('/activar'))
    expect(toggle?.url).toBe('/api/combos/8/activar?activo=true')
    expect(toggle?.headers['authorization']).toBe(`Bearer ${TOKEN}`)

    // Active combo (id 7): target state is inactive → activo=false.
    await user.click(screen.getByRole('switch', { name: 'Desactivar Combo Familiar' }))
    toggle = calls.filter((c) => c.method === 'PUT' && c.url.includes('/activar')).at(-1)
    expect(toggle?.url).toBe('/api/combos/7/activar?activo=false')

    // Like the categories switch (the sibling pattern), the row updates in
    // place: the accessible name and aria-checked reflect the new state.
    expect(screen.getByRole('switch', { name: 'Activar Combo Familiar' })).toHaveAttribute(
      'aria-checked',
      'false',
    )
    expect(screen.getByRole('switch', { name: 'Desactivar Combo Pausado' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
  })
})

describe('Admin combo delete', () => {
  it('the confirm modal gates the DELETE: cancelling issues none, confirming sends DELETE /api/combos/{id} with the auth header', async () => {
    stubFetch()
    const user = userEvent.setup()
    await openAdminView('Combos')

    expect(await screen.findByText('Combo Familiar')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Eliminar Combo Familiar' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)

    // Backdrop click also aborts (same ConfirmModal interaction as products).
    await user.click(screen.getByRole('alertdialog'))
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Eliminar Combo Familiar' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    const del = calls.find((c) => c.method === 'DELETE')
    expect(del?.url).toBe('/api/combos/7')
    expect(del?.headers['authorization']).toBe(`Bearer ${TOKEN}`)
  })

  it('surfaces the backend 409 message (combo used by an order) through the toast and keeps the view alive', async () => {
    stubFetch({
      deleteCombo: () => ({
        ok: false,
        status: 409,
        json: async () => ({ message: 'El combo está siendo usado por pedidos y no puede eliminarse' }),
      }),
    })
    const user = userEvent.setup()
    await openAdminView('Combos')

    expect(await screen.findByText('Combo Familiar')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Eliminar Combo Familiar' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    expect(
      await screen.findByText(/El combo está siendo usado por pedidos y no puede eliminarse/),
    ).toBeInTheDocument()

    expect(screen.getByText('Combo Familiar')).toBeInTheDocument()
  })
})
