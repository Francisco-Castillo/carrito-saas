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
  dateExceptions: [
    { date: '2026-12-24', shifts: [{ opensAt: '11:00:00', closesAt: '18:00:00' }] },
    { date: '2026-12-25', shifts: [] },
  ],
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

/** Igual que PROFILE pero con la Navidad cargada como EXCEPCIÓN ANUAL (yearly: true). */
const PROFILE_WITH_YEARLY = {
  ...PROFILE,
  dateExceptions: [
    { date: '2024-12-25', yearly: true, shifts: [] },
    { date: '2026-12-24', shifts: [{ opensAt: '11:00:00', closesAt: '18:00:00' }] },
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
function stubFetch(
  putHandler?: () => { status: number; body: unknown },
  profile: typeof PROFILE = PROFILE,
) {
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
        return { ok: true, status: 200, json: async () => profile }
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
  await user.click(screen.getByRole('button', { name: 'Local' }))
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

function putDateExceptions(): Array<{
  date: string
  yearly: boolean
  shifts: Array<{ opensAt: string | null; closesAt: string | null }>
}> {
  return JSON.parse(putCall().body ?? '{}').dateExceptions
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
      primaryColor: null,
      dateExceptions: [
        { date: '2026-12-24', yearly: false, shifts: [{ opensAt: '11:00:00', closesAt: '18:00:00' }] },
        { date: '2026-12-25', yearly: false, shifts: [] },
      ],
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

describe('Admin "Local" view — fechas especiales (date exceptions)', () => {
  it('renders the loaded exceptions ordered by date, each with its weekday name and its closed marker', async () => {
    stubFetch()
    await openLocalView()

    // Both loaded exceptions render with the weekday beside the date:
    // 2026-12-24 is a Thursday and 2026-12-25 is a Friday (pinned calendar facts).
    const dec24 = screen.getByText('24/12 (jueves)')
    const dec25 = screen.getByText('25/12 (viernes)')
    expect(dec24).toBeInTheDocument()
    expect(dec25).toBeInTheDocument()
    // Ordered by date: 24/12 before 25/12 in the DOM.
    expect(dec24.compareDocumentPosition(dec25) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    // The closed exception (empty shifts) renders the honest closed marker.
    expect(screen.getByText('Cerrado 25/12 (viernes)')).toBeInTheDocument()

    // The exception with shifts reuses the weekday shift-row controls.
    expect(screen.getByLabelText('Apertura 24/12 (jueves) turno 1')).toHaveValue('11:00')
    expect(screen.getByLabelText('Cierre 24/12 (jueves) turno 1')).toHaveValue('18:00')

    // With exceptions loaded there is no empty state.
    expect(screen.queryByText(/Sin fechas especiales/)).not.toBeInTheDocument()
  })

  it('the weekday label is computed from the date parts, never from a UTC parse (2026-12-25 is a Friday)', async () => {
    stubFetch()
    await openLocalView()

    // In a negative-offset timezone `new Date('2026-12-25')` lands on Dec 24
    // local time and the label would wrongly read "jueves". The label must
    // come from the parsed parts: Friday.
    expect(screen.getByText('25/12 (viernes)')).toBeInTheDocument()
  })

  it('adding a closed date and saving sends { date, shifts: [] } and keeps the loaded ones', async () => {
    stubFetch()
    const user = await openLocalView()

    // 2027-01-01 is a Friday too; the new row must show the weekday name.
    await user.type(screen.getByLabelText('Nueva fecha especial'), '2027-01-01')
    await user.click(screen.getByRole('button', { name: 'Agregar fecha' }))

    // It lands in the list, sorted, as a closed date.
    expect(screen.getByText('1/1 (viernes)')).toBeInTheDocument()
    expect(screen.getByText('Cerrado 1/1 (viernes)')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    expect(putDateExceptions()).toEqual([
      { date: '2026-12-24', yearly: false, shifts: [{ opensAt: '11:00:00', closesAt: '18:00:00' }] },
      { date: '2026-12-25', yearly: false, shifts: [] },
      { date: '2027-01-01', yearly: false, shifts: [] },
    ])
  })

  it('adding a date with shifts sends them in HH:mm:ss, sorted by opening time', async () => {
    stubFetch()
    const user = await openLocalView()

    await user.type(screen.getByLabelText('Nueva fecha especial'), '2027-01-01')
    await user.click(screen.getByRole('button', { name: 'Agregar fecha' }))

    // Two shifts entered OUT of order: the later opening goes in first.
    await user.click(screen.getByRole('button', { name: 'Agregar turno 1/1 (viernes)' }))
    await user.type(screen.getByLabelText('Apertura 1/1 (viernes) turno 1'), '20:00')
    await user.type(screen.getByLabelText('Cierre 1/1 (viernes) turno 1'), '23:00')
    await user.click(screen.getByRole('button', { name: 'Agregar turno 1/1 (viernes)' }))
    await user.type(screen.getByLabelText('Apertura 1/1 (viernes) turno 2'), '12:00')
    await user.type(screen.getByLabelText('Cierre 1/1 (viernes) turno 2'), '15:00')

    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    expect(putDateExceptions()).toEqual([
      { date: '2026-12-24', yearly: false, shifts: [{ opensAt: '11:00:00', closesAt: '18:00:00' }] },
      { date: '2026-12-25', yearly: false, shifts: [] },
      {
        date: '2027-01-01',
        yearly: false,
        shifts: [
          { opensAt: '12:00:00', closesAt: '15:00:00' },
          { opensAt: '20:00:00', closesAt: '23:00:00' },
        ],
      },
    ])
  })

  it('removing a date drops it from the payload: that day is a normal weekday again', async () => {
    stubFetch()
    const user = await openLocalView()

    await user.click(screen.getByRole('button', { name: 'Quitar fecha 25/12 (viernes)' }))
    expect(screen.queryByText('25/12 (viernes)')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    expect(putDateExceptions()).toEqual([
      { date: '2026-12-24', yearly: false, shifts: [{ opensAt: '11:00:00', closesAt: '18:00:00' }] },
    ])
  })

  it('a profile with no exceptions renders the empty state and still sends dateExceptions: []', async () => {
    const { dateExceptions: _omitted, ...withoutExceptions } = PROFILE
    stubFetch(undefined, withoutExceptions)
    const user = await openLocalView()

    expect(screen.getByText('Sin fechas especiales: los días se rigen por el horario semanal.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    // The field is MANDATORY on the PUT: it travels even when empty.
    expect(putDateExceptions()).toEqual([])
  })

  it('adding a duplicate date is refused locally and the row is not duplicated', async () => {
    stubFetch()
    const user = await openLocalView()

    await user.type(screen.getByLabelText('Nueva fecha especial'), '2026-12-25')
    await user.click(screen.getByRole('button', { name: 'Agregar fecha' }))

    expect(screen.getByText('Ya hay una excepción para esa fecha')).toBeInTheDocument()
    expect(screen.getAllByText('Cerrado 25/12 (viernes)')).toHaveLength(1)
  })

  it('a 400 from the backend (duplicate date) shows its message and keeps the user\'s work editable', async () => {
    stubFetch(() => ({
      status: 400,
      body: { message: 'Ya hay una excepción para esa fecha' },
    }))
    const user = await openLocalView()

    await user.type(screen.getByLabelText('Nueva fecha especial'), '2027-01-01')
    await user.click(screen.getByRole('button', { name: 'Agregar fecha' }))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    // The backend's message is rendered, not swallowed.
    expect(await screen.findByText('Ya hay una excepción para esa fecha')).toBeInTheDocument()
    expect(screen.queryByText('Perfil actualizado')).not.toBeInTheDocument()

    // The user's work is not dropped: the added date is still in the form.
    expect(screen.getByText('1/1 (viernes)')).toBeInTheDocument()
    expect(screen.getByLabelText('Nueva fecha especial')).toBeEnabled()
  })
})

describe('Admin "Local" view — fechas especiales ANUALES (yearly)', () => {
  it('a loaded yearly exception renders as "25/12 (cada año)", distinct from the one-off weekday label', async () => {
    stubFetch(undefined, PROFILE_WITH_YEARLY)
    await openLocalView()

    // The yearly row (saved under 2024) shows the recurring wording, not a
    // weekday that would only be true for one year.
    expect(screen.getByText('25/12 (cada año)')).toBeInTheDocument()
    expect(screen.getByText('Cerrado 25/12 (cada año)')).toBeInTheDocument()
    // The one-off keeps its weekday label, so both wordings coexist.
    expect(screen.getByText('24/12 (jueves)')).toBeInTheDocument()
  })

  it('the "Se repite cada año" checkbox round-trips: checking it sends yearly: true for that date only', async () => {
    stubFetch()
    const user = await openLocalView()

    await user.click(screen.getByLabelText('Se repite cada año 25/12 (viernes)'))

    // The row's label flips to the yearly wording immediately.
    expect(screen.getByText('25/12 (cada año)')).toBeInTheDocument()
    expect(screen.queryByText('25/12 (viernes)')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    // yearly travels for EVERY row: true for the checked one, false otherwise.
    expect(putDateExceptions()).toEqual([
      { date: '2026-12-24', yearly: false, shifts: [{ opensAt: '11:00:00', closesAt: '18:00:00' }] },
      { date: '2026-12-25', yearly: true, shifts: [] },
    ])
  })

  it('a second yearly date with the same month/day (even in another year) is refused locally with the backend message', async () => {
    stubFetch(undefined, PROFILE_WITH_YEARLY)
    const user = await openLocalView()

    // 2027-12-25 is a DIFFERENT date from the yearly 2024-12-25, so adding it
    // as a one-off is allowed; ticking "cada año" makes it a second yearly
    // row for 25/12, which the form refuses with the backend's own message.
    await user.type(screen.getByLabelText('Nueva fecha especial'), '2027-12-25')
    await user.click(screen.getByRole('button', { name: 'Agregar fecha' }))
    expect(screen.getByText('25/12 (sábado)')).toBeInTheDocument() // 2027-12-25 is a Saturday

    await user.click(screen.getByLabelText('Se repite cada año 25/12 (sábado)'))

    expect(screen.getByText('Ya hay una excepción anual para ese día y mes')).toBeInTheDocument()
    // Still only ONE yearly row for 25/12, and the refusal is local: no PUT needed.
    expect(screen.getAllByText('25/12 (cada año)')).toHaveLength(1)

    // The refused row stays a one-off in the payload.
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    const rows = putDateExceptions()
    expect(rows.find((r) => r.date === '2027-12-25')?.yearly).toBe(false)
    expect(rows.filter((r) => r.yearly)).toHaveLength(1)
  })

  it('a yearly and a one-off sharing the same month/day are both accepted', async () => {
    stubFetch(undefined, PROFILE_WITH_YEARLY)
    const user = await openLocalView()

    // Same month/day as the yearly 2024-12-25, but left as a one-off:
    // the backend allows it (the exact date wins its year) so the form does too.
    await user.type(screen.getByLabelText('Nueva fecha especial'), '2027-12-25')
    await user.click(screen.getByRole('button', { name: 'Agregar fecha' }))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    expect(await screen.findByText('Perfil actualizado')).toBeInTheDocument()
    expect(putDateExceptions()).toEqual([
      { date: '2024-12-25', yearly: true, shifts: [] },
      { date: '2026-12-24', yearly: false, shifts: [{ opensAt: '11:00:00', closesAt: '18:00:00' }] },
      { date: '2027-12-25', yearly: false, shifts: [] },
    ])
  })
})
