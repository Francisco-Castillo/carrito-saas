/**
 * Pantalla previa de elección de sucursal y carta scopeada (multi-sucursal,
 * odd/tasks/multi-sucursal.md, S4/T8-T10).
 *
 * <p>Reglas bajo prueba:</p>
 * <ul>
 *   <li>Con DOS o más sucursales activas y la URL SIN `?sucursal=` se muestra
 *       la pantalla previa de elección y la carta NO se ve (T8).</li>
 *   <li>Al elegir una sucursal se navega a la carta (`?sucursal=<id>` en la
 *       URL, sin recargar) y el menú SE VUELVE A PEDIR con esa sucursal:
 *       horas, WhatsApp y estado son los de la elegida (T9).</li>
 *   <li>Con `?sucursal=<id>` desde el arranque se va directo a la carta, sin
 *       pantalla previa, y el fetch incluye esa sucursal.</li>
 *   <li>Con UNA sola sucursal activa (o ninguna) → directo a la carta,
 *       comportamiento de hoy intacto (cero regresión).</li>
 *   <li>La carta NUNCA muestra un control de selección de sucursal (T10).</li>
 *   <li>El pedido confirmado lleva el `branchId` de la sucursal de la URL
 *       (no viaja en modo mesa fija; sin elección no viaja).</li>
 * </ul>
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Menu from '../src/pages/Menu'

const SLUG = 'picker-local'

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

// Forma REAL del payload (GET /api/menu/{slug}): `branches` viaja en el
// nivel superior del menú, hermano de `business` — no dentro de `business`.
const menuPayload = (branches?: Array<{ id: number; name: string; address?: string }>) => ({
  business: {
    name: 'Local Picker',
    whatsappNumber: '541155500011',
    logoUrl: null,
    primaryColor: '#7C3AED',
    hours: ALWAYS_OPEN_HOURS,
  },
  ...(branches ? { branches } : {}),
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
    if (u === `/api/restaurants/slug/${SLUG}`) return okJson({ name: 'Local Picker' })
    // El endpoint del menú con o sin ?sucursal= (el backend acepta ambos).
    if (u === `/api/menu/${SLUG}` || u.startsWith(`/api/menu/${SLUG}?`)) return okJson(payload)
    if (u === '/api/menu' || u.startsWith('/api/menu?')) return okJson(payload)
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
  await user.type(screen.getByPlaceholderText('Nombre y Apellido'), 'Cliente Picker')
  await user.type(screen.getByPlaceholderText('Celular (ej. 11 5555 1234)'), '11 5555 1234')
  await user.click(screen.getByRole('button', { name: 'Hacer el pedido' }))
}

describe('Pantalla previa de sucursales y carta scopeada (S4/T8-T10)', () => {
  afterEach(() => {
    cleanup()
    setSearch('')
  })

  it('(a) con dos sucursales y sin ?sucursal= se ve la pantalla de elección y NO la carta', async () => {
    const { fetchMock } = makeFetch(menuPayload(BRANCHES))
    setSearch('')
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu slug={SLUG} />)

    // La pantalla previa lista las activas (nombre + dirección).
    expect(await screen.findByRole('heading', { name: 'Elegí una sucursal' })).toBeInTheDocument()
    expect(screen.getByText('Casa central')).toBeInTheDocument()
    expect(screen.getByText('Av. Siempre Viva 742')).toBeInTheDocument()
    expect(screen.getByText('Palermo')).toBeInTheDocument()
    expect(screen.getByText('Calle Falsa 123')).toBeInTheDocument()

    // La carta no se ve: ni productos ni encabezado del bloque de menú.
    expect(screen.queryByText('Milanesa')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Agregar Milanesa' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Menú' })).not.toBeInTheDocument()
  })

  it('(b) al elegir una sucursal se muestra la carta y el menú se pidió con ?sucursal=<id>', async () => {
    const user = userEvent.setup()
    const { calls, fetchMock } = makeFetch(menuPayload(BRANCHES))
    setSearch('')
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu slug={SLUG} />)

    await screen.findByRole('heading', { name: 'Elegí una sucursal' })

    await user.click(screen.getByRole('button', { name: /Palermo/ }))

    // La carta aparece ya scopeada (refetch con la sucursal elegida, no se
    // reusa el payload de la default).
    expect(await screen.findByText('Milanesa')).toBeInTheDocument()
    expect(window.location.search).toBe('?sucursal=9')
    await waitFor(() => {
      expect(calls.some((call) => call.url === `/api/menu/${SLUG}?sucursal=9`)).toBe(true)
    })
  })

  it('(c) con ?sucursal=<id> desde el arranque va directo a la carta y el fetch incluye la sucursal', async () => {
    const { calls, fetchMock } = makeFetch(menuPayload(BRANCHES))
    setSearch('?sucursal=9')
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu slug={SLUG} />)

    // Sin pantalla previa: directo a la carta.
    expect(await screen.findByText('Milanesa')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Elegí una sucursal' })).not.toBeInTheDocument()
    expect(calls.some((call) => call.url === `/api/menu/${SLUG}?sucursal=9`)).toBe(true)
  })

  it('(d) con UNA sola sucursal va directo a la carta sin pantalla y sin ?sucursal= (cero regresión)', async () => {
    const user = userEvent.setup()
    const { calls, fetchMock } = makeFetch(menuPayload([BRANCHES[0]]))
    setSearch('')
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu slug={SLUG} />)

    expect(await screen.findByText('Milanesa')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Elegí una sucursal' })).not.toBeInTheDocument()
    // El fetch no manda sucursal: mismo contrato de hoy.
    expect(calls.some((call) => call.url === `/api/menu/${SLUG}`)).toBe(true)
    expect(calls.some((call) => call.url.includes('sucursal'))).toBe(false)

    // Sin elección el checkout tampoco manda branchId: el backend resuelve la
    // default (comportamiento de hoy intacto).
    await agregarYConfirmar(user)
    await waitFor(() => {
      const orderCall = calls.find((call) => call.url.startsWith('/api/orders'))
      expect(orderCall).toBeDefined()
      expect(JSON.parse(orderCall!.body!).branchId).toBeUndefined()
    })
  })

  it('(e) la carta no contiene ningún control de selección de sucursal', async () => {
    const { fetchMock } = makeFetch(menuPayload(BRANCHES))
    setSearch('?sucursal=9')
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu slug={SLUG} />)

    expect(await screen.findByText('Milanesa')).toBeInTheDocument()
    // Ningún grupo, heading ni botón con nombres de sucursales: el selector no
    // vive dentro de la carta (T10).
    expect(screen.queryByRole('heading', { name: 'Elegí una sucursal' })).not.toBeInTheDocument()
    expect(screen.queryByText('Casa central')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Casa central/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Palermo/ })).not.toBeInTheDocument()
  })

  it('(f) el pedido confirmado lleva el branchId de la sucursal de la URL', async () => {
    const user = userEvent.setup()
    const { calls, fetchMock } = makeFetch(menuPayload(BRANCHES))
    setSearch('?sucursal=9')
    vi.stubGlobal('fetch', fetchMock)
    render(<Menu slug={SLUG} />)

    await screen.findByText('Milanesa')
    await agregarYConfirmar(user)

    await waitFor(() => {
      const orderCall = calls.find((call) => call.url.startsWith('/api/orders'))
      expect(orderCall).toBeDefined()
      // El pedido viaja a la sucursal de la URL (el DTO de checkout ya lo acepta).
      expect(JSON.parse(orderCall!.body!).branchId).toBe(9)
    })
  })
})
