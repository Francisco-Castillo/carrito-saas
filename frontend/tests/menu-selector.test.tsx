/**
 * Selector público de sucursales del storefront (multi-sucursal,
 * odd/tasks/multi-sucursal.md, S4/T7).
 *
 * <p>Reglas bajo prueba:</p>
 * <ul>
 *   <li>Con DOS o más sucursales activas en el payload del menú
 *       (`business.branches`) aparece el selector (nombre + dirección); la
 *       elección se refleja en la URL (`?sucursal=<id>`, reemplazando el
 *       query param sin recargar) y el pedido (POST checkout) lleva el
 *       `branchId` de la sucursal elegida.</li>
 *   <li>Con UNA sola sucursal (o ninguna / payload viejo sin `branches`) la
 *       UI es idéntica a hoy: sin selector, sin `branchId` en el pedido
 *       (cero regresión visual y de payload).</li>
 * </ul>
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Menu from '../src/pages/Menu'

const SLUG = 'selector-local'

const BRANCHES = [
  { id: 7, name: 'Casa central', address: 'Av. Siempre Viva 742' },
  { id: 9, name: 'Palermo', address: 'Calle Falsa 123' },
]

// Semana siempre abierta: el badge/confirmación requieren horarios efectivos.
const ALWAYS_OPEN_HOURS = [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({
  dayOfWeek,
  shifts: [
    { opensAt: '00:00', closesAt: '12:00' },
    { opensAt: '12:00', closesAt: '00:00' },
  ],
}))

const PRODUCT = {
  id: 101,
  name: 'Milanesa',
  description: 'con papas',
  price: 100,
  categoryId: 1,
  categoryName: 'Platos',
  imageUrl: 'img.jpg',
}

const menuPayload = (branches?: Array<{ id: number; name: string; address?: string }>) => ({
  business: {
    name: 'Local Selector',
    whatsappNumber: '541155500011',
    logoUrl: null,
    primaryColor: '#7C3AED',
    hours: ALWAYS_OPEN_HOURS,
    ...(branches ? { branches } : {}),
  },
  products: [PRODUCT],
  combos: [],
})

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data })

interface FetchCall {
  url: string
  method: string
  body?: string
}

function makeFetch(payload: ReturnType<typeof menuPayload>) {
  const calls: FetchCall[] = []
  const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const u = String(url instanceof Request ? url.url : url)
    const call: FetchCall = {
      url: u,
      method: init?.method ?? 'GET',
      body: typeof init?.body === 'string' ? init.body : undefined,
    }
    calls.push(call)
    if (u === '/api/menu') return okJson(payload)
    if (u === `/api/menu/${SLUG}`) return okJson(payload)
    if (u.startsWith('/api/orders')) return okJson({ orderId: 42, status: 'NEW' })
    throw new Error('unexpected fetch: ' + u)
  })
  return { calls, fetchMock }
}

/** Sets the query string on the real jsdom location (same-document URL change). */
function setSearch(search: string) {
  window.history.replaceState(null, '', search === '' ? '/' : `/${search}`)
}

async function agregarYConfirmar(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Agregar Milanesa' }))
  await user.click(
    within(screen.getByRole('dialog', { name: 'Detalles de Milanesa' })).getByRole('button', {
      name: 'Agregar al pedido',
    }),
  )
  await user.click(screen.getByRole('button', { name: 'Ver pedido' }))
  await user.type(screen.getByPlaceholderText('Nombre y Apellido'), 'Cliente Selector')
  await user.type(screen.getByPlaceholderText('Celular (ej. 11 5555 1234)'), '11 5555 1234')
  await user.click(screen.getByRole('button', { name: 'Hacer el pedido' }))
}

describe('Selector de sucursales del storefront (multi-sucursal S4/T7)', () => {
  afterEach(() => {
    cleanup()
    setSearch('')
  })

  it('con dos sucursales aparece el selector y la elección queda en la URL', async () => {
    const user = userEvent.setup()
    const { fetchMock } = makeFetch(menuPayload(BRANCHES))
    setSearch('')
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu />)

    await screen.findByText('Milanesa')

    // El selector muestra nombre + dirección de cada sucursal activa.
    const selector = screen.getByRole('group', { name: 'Elegí una sucursal' })
    expect(within(selector).getByText('Casa central')).toBeInTheDocument()
    expect(within(selector).getByText('Av. Siempre Viva 742')).toBeInTheDocument()
    expect(within(selector).getByText('Palermo')).toBeInTheDocument()
    expect(within(selector).getByText('Calle Falsa 123')).toBeInTheDocument()

    // Elegir Palermo se refleja en la URL (?sucursal=9) sin recargar.
    await user.click(within(selector).getByRole('button', { name: /Palermo/ }))
    expect(window.location.search).toBe('?sucursal=9')
    expect(
      within(selector).getByRole('button', { name: /Palermo/ }).getAttribute('aria-pressed'),
    ).toBe('true')
  })

  it('el pedido confirmado lleva el branchId de la sucursal elegida', async () => {
    const user = userEvent.setup()
    const { calls, fetchMock } = makeFetch(menuPayload(BRANCHES))
    setSearch('')
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu />)

    await screen.findByText('Milanesa')

    await user.click(screen.getByRole('button', { name: /Palermo/ }))
    await agregarYConfirmar(user)

    await waitFor(() => {
      const orderCall = calls.find((call) => call.url.startsWith('/api/orders'))
      expect(orderCall).toBeDefined()
      const payload = JSON.parse(orderCall!.body!)
      // El pedido viaja a la sucursal elegida (el DTO de checkout ya lo acepta).
      expect(payload.branchId).toBe(9)
    })
  })

  it('con una sola sucursal no aparece el selector y la UI es idéntica a hoy', async () => {
    const user = userEvent.setup()
    const { calls, fetchMock } = makeFetch(menuPayload([BRANCHES[0]]))
    setSearch('')
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu />)

    await screen.findByText('Milanesa')
    expect(screen.queryByRole('group', { name: 'Elegí una sucursal' })).not.toBeInTheDocument()

    await agregarYConfirmar(user)
    await waitFor(() => {
      const orderCall = calls.find((call) => call.url.startsWith('/api/orders'))
      expect(orderCall).toBeDefined()
      // Una sola sucursal: el backend resuelve la default; el payload no cambia.
      expect(JSON.parse(orderCall!.body!).branchId).toBeUndefined()
    })
  })

  it('sin branches en el payload (carta vieja) no hay selector ni branchId', async () => {
    const { calls, fetchMock } = makeFetch(menuPayload())
    setSearch('')
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu />)

    await screen.findByText('Milanesa')
    expect(screen.queryByRole('group', { name: 'Elegí una sucursal' })).not.toBeInTheDocument()
    // El resto de la carta sigue funcionando igual (cero regresión).
    expect(screen.getByRole('button', { name: 'Agregar Milanesa' })).toBeInTheDocument()
    expect(calls.some((call) => call.url.startsWith('/api/orders'))).toBe(false)
  })
})
