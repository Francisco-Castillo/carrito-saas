/**
 * Comanda printing configuration (admin panel, "Datos del local"):
 *
 *  - Two toggles live at business level: the master "Impresión de comandas"
 *    and the dependent "Impresión automática al entrar pedido".
 *  - The dependent toggle is only usable when the master is ON.
 *  - "Guardar cambios" sends both flags in the PUT /api/business/profile body
 *    (the PUT replaces the whole profile, so they always travel).
 *  - A profile with the flags stored loads them checked.
 */
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Admin from '../src/pages/Admin'

function futureJwt(): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url')
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: 'demo', exp: 4102444800 })}.signature`
}

const TOKEN = futureJwt()

const BASE_PROFILE = {
  name: 'Foodtruck Demo',
  address: 'Av. Siempreviva 742',
  hours: [
    { dayOfWeek: 1, shifts: [{ opensAt: '11:00:00', closesAt: '23:00:00' }] },
    { dayOfWeek: 2, shifts: [] },
    { dayOfWeek: 3, shifts: [] },
    { dayOfWeek: 4, shifts: [] },
    { dayOfWeek: 5, shifts: [] },
    { dayOfWeek: 6, shifts: [] },
    { dayOfWeek: 7, shifts: [] },
  ],
  dateExceptions: [],
}

interface RecordedCall {
  url: string
  method: string
  body: string | null
}

let calls: RecordedCall[] = []

function stubFetch(profile: Record<string, unknown>) {
  return vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ?? null })

    if (url.includes('/api/business/profile')) {
      return { ok: true, status: 200, json: async () => profile } as Response
    }
    return { ok: false, status: 404, json: async () => ({ message: 'unexpected' }) } as Response
  }))
}

beforeEach(() => {
  localStorage.setItem('token', TOKEN)
  localStorage.setItem('businessId', '1')
  localStorage.setItem('role', 'OWNER')
  localStorage.setItem('username', 'demo')
  localStorage.setItem('expiresAt', '2030-01-01T00:00:00Z')
  localStorage.setItem('restaurantSlug', 'foodtruck-demo')
  calls = []
})

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.unstubAllGlobals()
})

async function openDatosDelLocal(): Promise<void> {
  render(<Admin />)
  await userEvent.click(await screen.findByRole('button', { name: 'Local' }))
  await screen.findByLabelText('Nombre del local')
}

describe('comanda printing toggles (admin, Datos del local)', () => {
  it('defaults to OFF for a business without stored flags', async () => {
    stubFetch(BASE_PROFILE)
    await openDatosDelLocal()

    const master = screen.getByLabelText('Impresión de comandas') as HTMLInputElement
    const auto = screen.getByLabelText('Impresión automática al entrar pedido') as HTMLInputElement
    expect(master.checked).toBe(false)
    expect(auto.checked).toBe(false)
  })

  it('the automatic toggle is unusable while the master is OFF', async () => {
    stubFetch(BASE_PROFILE)
    await openDatosDelLocal()

    const auto = screen.getByLabelText('Impresión automática al entrar pedido') as HTMLInputElement
    expect(auto.disabled).toBe(true)
  })

  it('turning the master ON enables and can check the automatic toggle', async () => {
    stubFetch(BASE_PROFILE)
    await openDatosDelLocal()

    const master = screen.getByLabelText('Impresión de comandas') as HTMLInputElement
    const auto = screen.getByLabelText('Impresión automática al entrar pedido') as HTMLInputElement

    await userEvent.click(master)
    expect(auto.disabled).toBe(false)
    await userEvent.click(auto)
    expect(auto.checked).toBe(true)
  })

  it('Guardar cambios sends both flags in the PUT body', async () => {
    stubFetch(BASE_PROFILE)
    await openDatosDelLocal()

    const master = screen.getByLabelText('Impresión de comandas') as HTMLInputElement
    const auto = screen.getByLabelText('Impresión automática al entrar pedido') as HTMLInputElement
    await userEvent.click(master)
    await userEvent.click(auto)
    await userEvent.click(screen.getByRole('button', { name: 'Guardar cambios' }))

    await screen.findByText('Perfil actualizado')
    const put = calls.find((c) => c.url.includes('/api/business/profile') && c.method === 'PUT')
    expect(put).toBeTruthy()
    const body = JSON.parse(put?.body ?? '{}')
    expect(body.comandaPrintingEnabled).toBe(true)
    expect(body.comandaAutoPrint).toBe(true)
  })

  it('a profile with the flags stored loads them checked', async () => {
    stubFetch({ ...BASE_PROFILE, comandaPrintingEnabled: true, comandaAutoPrint: false })
    await openDatosDelLocal()

    const master = screen.getByLabelText('Impresión de comandas') as HTMLInputElement
    const auto = screen.getByLabelText('Impresión automática al entrar pedido') as HTMLInputElement
    expect(master.checked).toBe(true)
    expect(auto.checked).toBe(false)
    expect(auto.disabled).toBe(false)
  })
})
