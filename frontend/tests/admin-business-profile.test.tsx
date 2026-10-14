/**
 * Admin "Local" view contracts against the owner endpoints:
 *
 *   - GET  /api/business/profile loads the storefront fields (name, logo,
 *     banner, address, Instagram) and the seven weekday rows into the form.
 *     Each day carries an ORDERED LIST of shifts (`{ dayOfWeek, shifts:
 *     [{ opensAt, closesAt }] }`); a day with an empty list IS a closed day
 *     and renders an honest "Cerrado" instead of empty rows.
 *   - PUT  /api/business/profile REPLACES everything: the payload carries the
 *     whole form (blank string = clear) and the hours array rebuilt with ALL
 *     seven days, each with its `shifts` array (including `[]` for closed
 *     days), with times converted from "HH:mm" (input) to "HH:mm:ss"
 *     (backend).
 *   - A 400 from the backend surfaces its readable `message` in Spanish (an
 *     overlap is a classic rejection) and keeps the form usable.
 *   - The midnight convention: a closing time earlier than the opening time
 *     is sent as-is, and the UI explains the convention in one short line.
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

const PROFILE = {
  name: 'Foodtruck Demo',
  logoUrl: '/dev-media/logo-local.jpg',
  bannerUrl: '/dev-media/banner-local.jpg',
  address: 'Av. Siempreviva 742, Springfield',
  instagramUrl: 'https://instagram.com/foodtruckdemo',
  hours: [
    { dayOfWeek: 1, shifts: [{ opensAt: '11:00:00', closesAt: '15:00:00' }, { opensAt: '19:00:00', closesAt: '23:00:00' }] },
    { dayOfWeek: 2, shifts: [{ opensAt: '11:00:00', closesAt: '23:00:00' }] },
    { dayOfWeek: 3, shifts: [] },
    { dayOfWeek: 4, shifts: [{ opensAt: '11:00:00', closesAt: '23:00:00' }] },
    { dayOfWeek: 5, shifts: [{ opensAt: '11:00:00', closesAt: '00:30:00' }] },
    { dayOfWeek: 6, shifts: [{ opensAt: '11:00:00', closesAt: '23:00:00' }] },
    { dayOfWeek: 7, shifts: [] },
  ],
}

interface RecordedCall {
  url: string
  method: string
  headers: Record<string, string>
  body: string | null
}

let calls: RecordedCall[] = []

/**
 * Fetch stub that records every call (url, method, headers, body) and serves
 * the GET profile fixture; the caller can override the PUT outcome per test.
 * Anything unexpected fails quietly, like the seam in admin-products-combos.test.tsx.
 */
function stubFetch(putHandler?: () => { status: number; body: unknown }) {
  return vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
      const method = (init?.method ?? 'GET').toUpperCase()
      calls.push({
        url,
        method,
        headers: Object.fromEntries(
          Object.entries((init?.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]),
        ),
        body: typeof init?.body === 'string' ? init.body : null,
      })
      if (url === '/api/business/profile' && method === 'GET') {
        return { ok: true, status: 200, json: async () => PROFILE }
      }
      if (url === '/api/business/profile' && method === 'PUT') {
        const outcome = putHandler?.()
        if (outcome) return { ok: outcome.status < 400, status: outcome.status, json: async () => outcome.body }
        return { ok: true, status: 200, json: async () => PROFILE }
      }
      // Dashboard loaders (and anything unexpected): fail quietly.
      return { ok: false, status: 500, json: async () => [] }
    }),
  )
}

function seedStorage() {
  localStorage.setItem('token', TOKEN)
  localStorage.setItem('businessId', '1')
  localStorage.setItem('role', 'ADMIN')
  localStorage.setItem('username', 'demo')
  localStorage.setItem('expiresAt', '2030-01-01T00:00:00Z')
}

/** Renders the admin and opens the "Local" view, waiting for the GET to fill the form. */
async function openLocalView() {
  render(<Admin />)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: '🏪 Local' }))
  await screen.findByDisplayValue('Foodtruck Demo')
  return user
}

function putCall(): RecordedCall {
  const put = calls.find((c) => c.method === 'PUT' && c.url === '/api/business/profile')
  expect(put, 'a PUT /api/business/profile must have been issued').toBeDefined()
  return put!
}

function putHours(): Array<{ dayOfWeek: number; shifts: Array<{ opensAt: string | null; closesAt: string | null }> }> {
  return JSON.parse(putCall().body ?? '{}').hours
}

beforeEach(() => {
  localStorage.clear()
  seedStorage()
  calls = []
})

afterEach(() => {
  cleanup()
})

describe('Admin "Local" view — business profile editor', () => {
  it('renders the form from the GET payload: a day with two shifts, closed days as "Cerrado", previews', async () => {
    stubFetch()
    await openLocalView()

    // Text fields loaded from GET /api/business/profile.
    expect(screen.getByLabelText('Nombre del local')).toHaveValue('Foodtruck Demo')
    expect(screen.getByLabelText('URL del logo')).toHaveValue('/dev-media/logo-local.jpg')
    expect(screen.getByLabelText('URL del banner')).toHaveValue('/dev-media/banner-local.jpg')
    expect(screen.getByLabelText('Dirección')).toHaveValue('Av. Siempreviva 742, Springfield')
    expect(screen.getByLabelText('URL de Instagram')).toHaveValue('https://instagram.com/foodtruckdemo')

    // Lunes carries TWO shifts, both loaded with times converted to "HH:mm".
    expect(screen.getByLabelText('Apertura Lunes turno 1')).toHaveValue('11:00')
    expect(screen.getByLabelText('Cierre Lunes turno 1')).toHaveValue('15:00')
    expect(screen.getByLabelText('Apertura Lunes turno 2')).toHaveValue('19:00')
    expect(screen.getByLabelText('Cierre Lunes turno 2')).toHaveValue('23:00')

    // Closed days (empty shifts list) read as closed instead of empty rows,
    // and have no time inputs at all.
    expect(screen.getByText('Cerrado Miércoles')).toBeInTheDocument()
    expect(screen.getByText('Cerrado Domingo')).toBeInTheDocument()
    expect(screen.queryByLabelText('Apertura Miércoles turno 1')).not.toBeInTheDocument()

    // Midnight case loaded as-is: Friday closes at 00:30.
    expect(screen.getByLabelText('Cierre Viernes turno 1')).toHaveValue('00:30')

    // Image previews render for non-empty URLs.
    expect(screen.getByAltText('Vista previa del logo')).toHaveAttribute('src', '/dev-media/logo-local.jpg')
    expect(screen.getByAltText('Vista previa del banner')).toHaveAttribute('src', '/dev-media/banner-local.jpg')
  })

  it('adding and removing a shift flips a day between open and closed', async () => {
    stubFetch()
    const user = await openLocalView()

    // Miércoles starts closed.
    expect(screen.getByText('Cerrado Miércoles')).toBeInTheDocument()

    // Adding a shift opens the day: an empty editable row appears.
    await user.click(screen.getByRole('button', { name: 'Agregar turno Miércoles' }))
    expect(screen.queryByText('Cerrado Miércoles')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Apertura Miércoles turno 1')).toHaveValue('')
    expect(screen.getByLabelText('Cierre Miércoles turno 1')).toHaveValue('')

    await user.type(screen.getByLabelText('Apertura Miércoles turno 1'), '12:00')
    await user.type(screen.getByLabelText('Cierre Miércoles turno 1'), '14:00')

    // Removing it closes the day again: the row is gone, "Cerrado" is back.
    await user.click(screen.getByRole('button', { name: 'Quitar turno Miércoles 1' }))
    expect(screen.getByText('Cerrado Miércoles')).toBeInTheDocument()
    expect(screen.queryByLabelText('Apertura Miércoles turno 1')).not.toBeInTheDocument()

    // Removing one of two shifts keeps the other one intact.
    await user.click(screen.getByRole('button', { name: 'Quitar turno Lunes 1' }))
    expect(screen.getByLabelText('Apertura Lunes turno 1')).toHaveValue('19:00')
    expect(screen.getByLabelText('Cierre Lunes turno 1')).toHaveValue('23:00')
    expect(screen.queryByLabelText('Apertura Lunes turno 2')).not.toBeInTheDocument()
  })

  it('a day with no shifts renders as closed and is sent as an empty shifts list', async () => {
    stubFetch()
    const user = await openLocalView()

    expect(screen.getByText('Cerrado Miércoles')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    const hours = putHours()
    expect(hours).toHaveLength(7)
    expect(hours.find((h) => h.dayOfWeek === 3)?.shifts).toEqual([])
    expect(hours.find((h) => h.dayOfWeek === 7)?.shifts).toEqual([])
  })

  it('saving issues a PUT with the exact payload: all seven days, each with its shifts, times in HH:mm:ss', async () => {
    stubFetch()
    const user = await openLocalView()

    await user.clear(screen.getByLabelText('Nombre del local'))
    await user.type(screen.getByLabelText('Nombre del local'), 'Foodtruck Demo Bar')
    await user.clear(screen.getByLabelText('Apertura Lunes turno 1'))
    await user.type(screen.getByLabelText('Apertura Lunes turno 1'), '10:30')
    // A second shift on Martes: mediodía y noche on the same day.
    await user.click(screen.getByRole('button', { name: 'Agregar turno Martes' }))
    await user.type(screen.getByLabelText('Apertura Martes turno 2'), '19:00')
    await user.type(screen.getByLabelText('Cierre Martes turno 2'), '22:00')

    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    const put = putCall()
    expect(put.headers['authorization']).toBe(`Bearer ${TOKEN}`)
    expect(JSON.parse(put.body ?? '{}')).toEqual({
      name: 'Foodtruck Demo Bar',
      logoUrl: '/dev-media/logo-local.jpg',
      bannerUrl: '/dev-media/banner-local.jpg',
      address: 'Av. Siempreviva 742, Springfield',
      instagramUrl: 'https://instagram.com/foodtruckdemo',
      hours: [
        { dayOfWeek: 1, shifts: [{ opensAt: '10:30:00', closesAt: '15:00:00' }, { opensAt: '19:00:00', closesAt: '23:00:00' }] },
        { dayOfWeek: 2, shifts: [{ opensAt: '11:00:00', closesAt: '23:00:00' }, { opensAt: '19:00:00', closesAt: '22:00:00' }] },
        { dayOfWeek: 3, shifts: [] },
        { dayOfWeek: 4, shifts: [{ opensAt: '11:00:00', closesAt: '23:00:00' }] },
        { dayOfWeek: 5, shifts: [{ opensAt: '11:00:00', closesAt: '00:30:00' }] },
        { dayOfWeek: 6, shifts: [{ opensAt: '11:00:00', closesAt: '23:00:00' }] },
        { dayOfWeek: 7, shifts: [] },
      ],
    })

    // Success feedback reuses the admin toast helper.
    expect(await screen.findByText('Perfil actualizado')).toBeInTheDocument()
  })

  it('a 400 from the backend (overlap) shows its message inline and keeps the form editable', async () => {
    stubFetch(() => ({
      status: 400,
      body: { message: 'Los turnos del día no pueden solaparse: 11:00–23:00 y 12:00–14:00' },
    }))
    const user = await openLocalView()

    // An overlap the backend rejects: a second shift inside the first one.
    await user.click(screen.getByRole('button', { name: 'Agregar turno Martes' }))
    await user.type(screen.getByLabelText('Apertura Martes turno 2'), '12:00')
    await user.type(screen.getByLabelText('Cierre Martes turno 2'), '14:00')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    // The backend's message is rendered, not swallowed.
    expect(await screen.findByText('Los turnos del día no pueden solaparse: 11:00–23:00 y 12:00–14:00')).toBeInTheDocument()

    // No success toast on failure, and the form is still editable + saveable.
    expect(screen.queryByText('Perfil actualizado')).not.toBeInTheDocument()
    await user.clear(screen.getByLabelText('Nombre del local'))
    await user.type(screen.getByLabelText('Nombre del local'), 'Reintentando')
    expect(screen.getByLabelText('Nombre del local')).toHaveValue('Reintentando')
  })

  it('the midnight note is rendered and a closing time earlier than the opening time is sent as-is', async () => {
    stubFetch()
    const user = await openLocalView()

    // The convention is explained in one short line (it is not guessable).
    expect(screen.getByText(/cierre es anterior a la de apertura.*cierra después de medianoche/i)).toBeInTheDocument()

    // Friday closes at 00:30 (earlier than 11:00): preserved untouched.
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    const friday = putHours().find((h) => h.dayOfWeek === 5)
    expect(friday).toEqual({ dayOfWeek: 5, shifts: [{ opensAt: '11:00:00', closesAt: '00:30:00' }] })
  })
})
