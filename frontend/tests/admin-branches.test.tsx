/**
 * Sección "Sucursales" del panel admin (nueva vista del sidebar), contra el
 * contrato ya implementado en el backend (T1-T7 de odd/tasks/multi-sucursal):
 *
 *   a) GET /api/business/branches lista las sucursales (incluye inactivas)
 *      con nombre, dirección y WhatsApp, y badge "inactiva" si active=false.
 *   b) Alta con nombre vacío NO llama al API: guard local con el mismo
 *      mensaje del backend ("El nombre de la sucursal es obligatorio").
 *   c) Un 409 del backend muestra el mensaje exacto que viaja.
 *   d) "Trabajar acá" llama POST /api/business/branches/select/{id} y
 *      REEMPLAZA el token de la sesión (localStorage "token", leído en
 *      caliente por authHeader) por el que responde, marcando la sucursal.
 *   e) Baja con confirmación (ConfirmModal común del panel): DELETE y la
 *      fila pasa a "inactiva" tras el refresco.
 *   f) QR de una sucursal activa: fetch autenticado al PNG y descarga como
 *      qr-<nombre>.png (fetch → blob → anchor download).
 *   g) Cero regresión: negocio con una sola sucursal (estado post-migración)
 *      muestra esa fila y nada raro.
 *   h) "Trabajar acá" con 404 (sucursal recién dada de baja): muestra el
 *      error y refresca la lista.
 */
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Admin from '../src/pages/Admin'

function jwt(payload: object): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url')
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode(payload)}.signature`
}

/** Token inicial del panel: sin branchId (vista negocio). */
const TOKEN_A = jwt({ sub: 'demo', exp: 4102444800 })
/** Token que devuelve el select: fija la sucursal 2. */
const TOKEN_B = jwt({ sub: 'demo', exp: 4102444800, branchId: 2 })

const BRANCHES = [
  { id: 1, name: 'Sucursal Centro', address: 'Av. Central 123', phone: '5491111111111', active: true, businessId: 1 },
  { id: 2, name: 'Sucursal Norte', address: 'Calle 9 456', phone: '5492222222222', active: true, businessId: 1 },
  { id: 3, name: 'Sucursal Vieja', address: null, phone: null, active: false, businessId: 1 },
]

interface RecordedCall {
  url: string
  method: string
  body: string | null
  headers: Record<string, string>
}

let calls: RecordedCall[] = []

interface StubOptions {
  list?: unknown[]
  postBranch?: { status: number; message?: string }
  select?: { status: number; message?: string }
  qrOk?: boolean
}

function stubFetch(options: StubOptions = {}) {
  return vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    const method = (init?.method ?? 'GET').toUpperCase()
    calls.push({
      url,
      method,
      body: init?.body ?? null,
      headers: Object.fromEntries(
        Object.entries((init?.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]),
      ),
    })

    if (url === '/api/business/branches' && method === 'GET') {
      return { ok: true, status: 200, json: async () => options.list ?? BRANCHES } as Response
    }
    if (url === '/api/business/branches' && method === 'POST') {
      if (options.postBranch) {
        return { ok: false, status: options.postBranch.status, json: async () => ({ message: options.postBranch.message }) } as Response
      }
      return { ok: true, status: 200, json: async () => BRANCHES[0] } as Response
    }
    if (/\/api\/business\/branches\/select\/\d+$/.test(url)) {
      if (options.select) {
        return { ok: false, status: options.select.status, json: async () => ({ message: options.select.message }) } as Response
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({ token: TOKEN_B, branchId: 2, branchName: 'Sucursal Norte', expiresAt: '2031-01-01T00:00:00Z' }),
      } as Response
    }
    if (/\/api\/business\/branches\/\d+\/qr$/.test(url)) {
      if (options.qrOk === false) {
        return { ok: false, status: 404, json: async () => ({ message: 'No encontrada' }) } as Response
      }
      return { ok: true, status: 200, blob: async () => new Blob(['png'], { type: 'image/png' }) } as unknown as Response
    }
    if (/\/api\/business\/branches\/\d+$/.test(url) && method === 'DELETE') {
      // Tras la baja lógica, el refresco devuelve la fila inactiva.
      return { ok: true, status: 200, json: async () => ({}) } as Response
    }
    if (/\/api\/business\/branches\/\d+$/.test(url) && method === 'PUT') {
      return { ok: true, status: 200, json: async () => BRANCHES[0] } as Response
    }
    // El resto (perfil del brand block, etc.) falla en silencio, como el
    // seam de admin-panel.test.tsx.
    return { ok: false, status: 500, json: async () => [] } as Response
  }))
}

function seedStorage() {
  localStorage.setItem('token', TOKEN_A)
  localStorage.setItem('businessId', '1')
  localStorage.setItem('role', 'OWNER')
  localStorage.setItem('username', 'demo')
  localStorage.setItem('expiresAt', '2030-01-01T00:00:00Z')
  localStorage.setItem('restaurantSlug', 'demo-pizzeria')
}

async function openBranchesView() {
  render(<Admin />)
  await userEvent.click(await screen.findByRole('button', { name: 'Sucursales' }))
}

beforeEach(() => {
  localStorage.clear()
  seedStorage()
  calls = []
  vi.stubGlobal('URL.createObjectURL', vi.fn(() => 'blob:mock'))
  vi.stubGlobal('URL.revokeObjectURL', vi.fn())
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('Sección Sucursales: lista del GET', () => {
  it('(a) lista las sucursales con nombre, dirección, WhatsApp y badge inactiva', async () => {
    stubFetch()
    await openBranchesView()

    expect(await screen.findByText('Sucursal Centro')).toBeInTheDocument()
    expect(screen.getByText('Sucursal Norte')).toBeInTheDocument()
    expect(screen.getByText('Av. Central 123')).toBeInTheDocument()
    expect(screen.getByText('5491111111111')).toBeInTheDocument()
    expect(screen.getByText('Sucursal Vieja')).toBeInTheDocument()
    // Badge exacto "inactiva" sólo para la fila dada de baja.
    expect(screen.getByText('Inactiva')).toBeInTheDocument()
  })

  it('(g) cero regresión: negocio con una sola sucursal (post-migración) muestra esa fila y nada raro', async () => {
    stubFetch({ list: [BRANCHES[0]] })
    await openBranchesView()

    expect(await screen.findByText('Sucursal Centro')).toBeInTheDocument()
    expect(screen.queryByText('Inactiva')).not.toBeInTheDocument()
    // El resto del panel sigue: el título de vista es el label español.
    expect(document.querySelector('#view-title')?.textContent).toBe('Sucursales')
    expect(screen.getByRole('button', { name: 'Local' })).toBeInTheDocument()
  })
})

describe('Alta de sucursal', () => {
  it('(b) nombre vacío no llama al API y muestra el mensaje requerido', async () => {
    stubFetch()
    await openBranchesView()
    await screen.findByText('Sucursal Centro')

    const postCallsBefore = calls.filter((c) => c.method === 'POST' && c.url === '/api/business/branches').length
    await userEvent.click(screen.getByRole('button', { name: 'Agregar sucursal' }))

    expect(await screen.findByText('El nombre de la sucursal es obligatorio')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST' && c.url === '/api/business/branches').length).toBe(postCallsBefore)
  })

  it('(c) un 409 del backend muestra el mensaje exacto que viaja', async () => {
    stubFetch({ postBranch: { status: 409, message: 'Ya existe una sucursal con ese nombre' } })
    await openBranchesView()
    await screen.findByText('Sucursal Centro')

    await userEvent.type(screen.getByLabelText('Nombre de la sucursal'), 'Sucursal Centro')
    await userEvent.click(screen.getByRole('button', { name: 'Agregar sucursal' }))

    expect(await screen.findByText('Ya existe una sucursal con ese nombre')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.url === '/api/business/branches')).toBe(true)
  })
})

describe('"Trabajar acá": reemplazo del token de sesión', () => {
  it('(d) llama select/{id} y reemplaza el token guardado por el que responde', async () => {
    stubFetch()
    await openBranchesView()
    await screen.findByText('Sucursal Centro')

    await userEvent.click(screen.getByRole('button', { name: 'Trabajar acá Sucursal Norte' }))

    const selectCall = calls.find((c) => c.method === 'POST' && /\/api\/business\/branches\/select\/2$/.test(c.url))
    expect(selectCall).toBeDefined()
    expect(selectCall?.headers['authorization']).toBe(`Bearer ${TOKEN_A}`)

    // El token del panel fue REEMPLAZADO (y el resto de la sesión intacto).
    expect(localStorage.getItem('token')).toBe(TOKEN_B)
    expect(localStorage.getItem('businessId')).toBe('1')
    expect(localStorage.getItem('restaurantSlug')).toBe('demo-pizzeria')
    // La expiración nueva viaja en la respuesta: se actualiza con el token.
    expect(localStorage.getItem('expiresAt')).toBe('2031-01-01T00:00:00Z')

    // La sucursal elegida queda marcada visualmente.
    expect(await screen.findByText(/actual/)).toBeInTheDocument()
  })

  it('(h) select 404 (sucursal recién dada de baja): muestra el error y refresca la lista', async () => {
    stubFetch({ select: { status: 404, message: 'No encontrada' } })
    await openBranchesView()
    await screen.findByText('Sucursal Centro')

    const listGetsBefore = calls.filter((c) => c.method === 'GET' && c.url === '/api/business/branches').length
    await userEvent.click(screen.getByRole('button', { name: 'Trabajar acá Sucursal Norte' }))

    expect(await screen.findByText('No encontrada')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'GET' && c.url === '/api/business/branches').length).toBeGreaterThan(listGetsBefore)
    // El token NO cambió cuando el select falló.
    expect(localStorage.getItem('token')).toBe(TOKEN_A)
  })
})

describe('Baja de sucursal', () => {
  it('(e) pasa por la confirmación, llama DELETE y la fila pasa a inactiva', async () => {
    let deleted = false
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = (init?.method ?? 'GET').toUpperCase()
      calls.push({ url, method, body: init?.body ?? null, headers: {} })
      if (url === '/api/business/branches' && method === 'GET') {
        return { ok: true, status: 200, json: async () => BRANCHES.map((b) => (b.id === 2 && deleted ? { ...b, active: false } : b)) } as Response
      }
      if (url === '/api/business/branches/2' && method === 'DELETE') {
        deleted = true
        return { ok: true, status: 200, json: async () => ({}) } as Response
      }
      return { ok: false, status: 500, json: async () => [] } as Response
    })
    vi.stubGlobal('fetch', fetchMock)
    await openBranchesView()
    await screen.findByText('Sucursal Centro')

    await userEvent.click(screen.getByRole('button', { name: 'Eliminar Sucursal Norte' }))

    // Confirmación del panel: Cancelar NO borra.
    await screen.findByRole('alertdialog')
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(deleted).toBe(false)

    await userEvent.click(screen.getByRole('button', { name: 'Eliminar Sucursal Norte' }))
    await screen.findByRole('alertdialog')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }))

    expect(deleted).toBe(true)
    // Tras el refresco, la fila muestra el badge inactiva (dos ahora).
    const badges = await screen.findAllByText('Inactiva')
    expect(badges.length).toBe(2)
  })
})

describe('QR por sucursal', () => {
  it('(f) descarga el PNG autenticado como qr-<nombre-sucursal>.png', async () => {
    stubFetch()
    await openBranchesView()
    await screen.findByText('Sucursal Centro')

    const originalCreateElement = document.createElement.bind(document)
    const anchors: HTMLAnchorElement[] = []
    const spy = vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
      const el = originalCreateElement(tag)
      if (tag === 'a') anchors.push(el as HTMLAnchorElement)
      return el
    }) as typeof document.createElement)

    await userEvent.click(screen.getByRole('button', { name: 'Descargar QR Sucursal Norte' }))

    const qrCall = calls.find((c) => c.url === '/api/business/branches/2/qr')
    expect(qrCall).toBeDefined()
    expect(qrCall?.headers['authorization']).toBe(`Bearer ${TOKEN_A}`)
    // El blob viaja por object URL y el anchor sugiere el nombre del archivo.
    expect(anchors[0]?.href).toMatch(/^blob:/)
    expect(anchors[0]?.download).toBe('qr-Sucursal Norte.png')

    spy.mockRestore()
  })
})
