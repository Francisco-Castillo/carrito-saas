/**
 * Admin "Local" view contracts against the owner endpoints:
 *
 *   - GET  /api/business/profile loads the storefront fields (name, logo,
 *     banner, address, Instagram) and the seven weekday rows into the form;
 *     closed days render with disabled time inputs.
 *   - PUT  /api/business/profile REPLACES everything: the payload carries the
 *     whole form (blank string = clear) and the hours array rebuilt from the
 *     seven rows, with times converted from "HH:mm" (input) to "HH:mm:ss"
 *     (backend).
 *   - A 400 from the backend surfaces its readable `message` in Spanish and
 *     keeps the form usable.
 *   - Toggling "Cerrado" disables AND clears that day's times.
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
    { dayOfWeek: 1, opensAt: '11:00:00', closesAt: '23:00:00', closed: false },
    { dayOfWeek: 2, opensAt: '11:00:00', closesAt: '23:00:00', closed: false },
    { dayOfWeek: 3, opensAt: null, closesAt: null, closed: true },
    { dayOfWeek: 4, opensAt: '11:00:00', closesAt: '23:00:00', closed: false },
    { dayOfWeek: 5, opensAt: '11:00:00', closesAt: '00:30:00', closed: false },
    { dayOfWeek: 6, opensAt: '11:00:00', closesAt: '23:00:00', closed: false },
    { dayOfWeek: 7, opensAt: null, closesAt: null, closed: true },
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

beforeEach(() => {
  localStorage.clear()
  seedStorage()
  calls = []
})

afterEach(() => {
  cleanup()
})

describe('Admin "Local" view — business profile editor', () => {
  it('renders the form from the GET payload: every field and the seven days, closed days with disabled inputs', async () => {
    stubFetch()
    await openLocalView()

    // Text fields loaded from GET /api/business/profile.
    expect(screen.getByLabelText('Nombre del local')).toHaveValue('Foodtruck Demo')
    expect(screen.getByLabelText('URL del logo')).toHaveValue('/dev-media/logo-local.jpg')
    expect(screen.getByLabelText('URL del banner')).toHaveValue('/dev-media/banner-local.jpg')
    expect(screen.getByLabelText('Dirección')).toHaveValue('Av. Siempreviva 742, Springfield')
    expect(screen.getByLabelText('URL de Instagram')).toHaveValue('https://instagram.com/foodtruckdemo')

    // Seven weekday rows, Lunes (1) … Domingo (7), times converted to "HH:mm".
    expect(screen.getByLabelText('Apertura Lunes')).toHaveValue('11:00')
    expect(screen.getByLabelText('Cierre Lunes')).toHaveValue('23:00')
    expect(screen.getByLabelText('Apertura Miércoles')).toBeDisabled()
    expect(screen.getByLabelText('Cierre Miércoles')).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: 'Cerrado Miércoles' })).toBeChecked()
    // Midnight case loaded as-is: Friday closes at 00:30.
    expect(screen.getByLabelText('Cierre Viernes')).toHaveValue('00:30')
    expect(screen.getByRole('checkbox', { name: 'Cerrado Domingo' })).toBeChecked()

    // Image previews render for non-empty URLs.
    expect(screen.getByAltText('Vista previa del logo')).toHaveAttribute('src', '/dev-media/logo-local.jpg')
    expect(screen.getByAltText('Vista previa del banner')).toHaveAttribute('src', '/dev-media/banner-local.jpg')
  })

  it('editing fields and saving issues a PUT with the exact payload: seven rows and times converted to HH:mm:ss', async () => {
    stubFetch()
    const user = await openLocalView()

    await user.clear(screen.getByLabelText('Nombre del local'))
    await user.type(screen.getByLabelText('Nombre del local'), 'Foodtruck Demo Bar')
    await user.clear(screen.getByLabelText('Apertura Lunes'))
    await user.type(screen.getByLabelText('Apertura Lunes'), '10:30')

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
        { dayOfWeek: 1, opensAt: '10:30:00', closesAt: '23:00:00', closed: false },
        { dayOfWeek: 2, opensAt: '11:00:00', closesAt: '23:00:00', closed: false },
        { dayOfWeek: 3, opensAt: null, closesAt: null, closed: true },
        { dayOfWeek: 4, opensAt: '11:00:00', closesAt: '23:00:00', closed: false },
        { dayOfWeek: 5, opensAt: '11:00:00', closesAt: '00:30:00', closed: false },
        { dayOfWeek: 6, opensAt: '11:00:00', closesAt: '23:00:00', closed: false },
        { dayOfWeek: 7, opensAt: null, closesAt: null, closed: true },
      ],
    })

    // Success feedback reuses the admin toast helper.
    expect(await screen.findByText('Perfil actualizado')).toBeInTheDocument()
  })

  it('a blank field is sent as blank (the backend clears it) instead of being silently dropped', async () => {
    stubFetch()
    const user = await openLocalView()

    await user.clear(screen.getByLabelText('Dirección'))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    const body = JSON.parse(putCall().body ?? '{}')
    expect(body.address).toBe('')
  })

  it('a 400 from the backend shows its readable message and keeps the form usable', async () => {
    stubFetch(() => ({
      status: 400,
      body: { message: 'La URL del logo no es válida' },
    }))
    const user = await openLocalView()

    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    // The backend's message is rendered, not swallowed.
    expect(await screen.findByText('La URL del logo no es válida')).toBeInTheDocument()

    // No success toast on failure, and the form is still editable + saveable.
    expect(screen.queryByText('Perfil actualizado')).not.toBeInTheDocument()
    await user.clear(screen.getByLabelText('Nombre del local'))
    await user.type(screen.getByLabelText('Nombre del local'), 'Reintentando')
    expect(screen.getByLabelText('Nombre del local')).toHaveValue('Reintentando')
  })

  it('toggling "Cerrado" disables and clears that day\'s times', async () => {
    stubFetch()
    const user = await openLocalView()

    await user.click(screen.getByRole('checkbox', { name: 'Cerrado Lunes' }))

    const opens = screen.getByLabelText('Apertura Lunes') as HTMLInputElement
    const closes = screen.getByLabelText('Cierre Lunes') as HTMLInputElement
    expect(opens.disabled).toBe(true)
    expect(closes.disabled).toBe(true)
    expect(opens.value).toBe('')
    expect(closes.value).toBe('')
  })

  it('a closing time earlier than the opening time is sent as-is (closes after midnight) and the explanatory line is rendered', async () => {
    stubFetch()
    const user = await openLocalView()

    // The convention is explained in one short line (it is not guessable).
    expect(screen.getByText(/cierre es anterior a la de apertura.*cierra después de medianoche/i)).toBeInTheDocument()

    // Friday closes at 00:30 (earlier than 11:00): preserved untouched.
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    const body = JSON.parse(putCall().body ?? '{}')
    const friday = body.hours.find((h: { dayOfWeek: number }) => h.dayOfWeek === 5)
    expect(friday).toEqual({ dayOfWeek: 5, opensAt: '11:00:00', closesAt: '00:30:00', closed: false })
  })
})
