/**
 * Vista "Local" del panel admin: dos pestañas.
 *
 *   - Pestaña "Datos y horarios" (por defecto): el editor de perfil que ya
 *     existía — nombre, logo, banner, dirección, Instagram, color, horarios
 *     y fechas especiales — intacto, ahora dentro de su pestaña.
 *   - Pestaña "QR y mesas": apartado separado para
 *       a) el QR del menú público: el PNG vive en GET /api/business/qr,
 *          se pide con el Bearer token y se muestra + se puede descargar;
 *       b) las mesas: GET /api/tables (página única grande) lista cada mesa
 *          con su QR descargable (PNG y PDF por plantilla) y el PDF de
 *          todas; alta por lote con POST /api/tables/bulk y baja con
 *          DELETE /api/tables/{id} pasando por el diálogo de confirmación.
 *
 * Los endpoints de QR responden binario (image/png / application/pdf): el
 * stub de fetch entrega blobs y se stubbea URL.createObjectURL.
 */
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Admin from '../src/pages/Admin'

function futureJwt(): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url')
  return `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: 'demo', exp: 4102444800 })}.signature`
}

const TOKEN = futureJwt()

const PROFILE = {
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

const TABLES_PAGE = {
  content: [
    { id: 11, tableNumber: 1, tableName: 'Mesa 1', qrToken: 'tok-1', qrUrl: 'https://x/menu/', hasActiveOrder: false, activeOrderId: null },
    { id: 12, tableNumber: 2, tableName: 'Mesa 2', qrToken: 'tok-2', qrUrl: 'https://x/menu/', hasActiveOrder: true, activeOrderId: 77 },
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
}

interface RecordedCall {
  url: string
  method: string
  body: string | null
}

let calls: RecordedCall[] = []

function stubFetch(options?: { bulkTables?: unknown[]; deleteOk?: boolean }) {
  return vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ?? null })

    if (url.includes('/api/business/profile')) {
      return { ok: true, status: 200, json: async () => PROFILE } as Response
    }
    if (url.includes('/api/business/logo')) {
      if ((init?.method ?? 'GET') === 'DELETE') {
        return { ok: true, status: 200, json: async () => ({ ...PROFILE, logoUrl: null }) } as Response
      }
      return { ok: true, status: 200, json: async () => ({ ...PROFILE, logoUrl: '/api/images/business/logo-nuevo.png' }) } as Response
    }
    if (url.includes('/api/business/banner')) {
      if ((init?.method ?? 'GET') === 'DELETE') {
        return { ok: true, status: 200, json: async () => ({ ...PROFILE, bannerUrl: null }) } as Response
      }
      return { ok: true, status: 200, json: async () => ({ ...PROFILE, bannerUrl: '/api/images/business/banner-nuevo.png' }) } as Response
    }
    if (url.includes('/api/business/qr')) {
      return { ok: true, status: 200, blob: async () => new Blob(['png'], { type: 'image/png' }) } as unknown as Response
    }
    if (url.includes('/api/tables/bulk')) {
      return { ok: true, status: 200, json: async () => options?.bulkTables ?? [] } as Response
    }
    if (url.includes('/api/tables/qr/pdf')) {
      return { ok: true, status: 200, blob: async () => new Blob(['pdf'], { type: 'application/pdf' }) } as unknown as Response
    }
    if (/\/api\/tables\/\d+\/qr(\/pdf)?/.test(url)) {
      return { ok: true, status: 200, blob: async () => new Blob(['bin']) } as unknown as Response
    }
    if (/\/api\/tables\/\d+$/.test(url) && method === 'DELETE') {
      const ok = options?.deleteOk ?? true
      return { ok, status: ok ? 200 : 409 } as Response
    }
    if (url.includes('/api/tables')) {
      return { ok: true, status: 200, json: async () => TABLES_PAGE } as Response
    }
    return { ok: false, status: 404, json: async () => ({ message: 'unexpected' }) } as Response
  }))
}

beforeEach(() => {
  localStorage.setItem('token', TOKEN)
  localStorage.setItem('businessId', '1')
  localStorage.setItem('role', 'ADMIN')
  localStorage.setItem('username', 'demo')
  localStorage.setItem('expiresAt', '2030-01-01T00:00:00Z')
  localStorage.setItem('restaurantSlug', 'foodtruck-demo')
  calls = []
  vi.stubGlobal('URL.createObjectURL', vi.fn(() => 'blob:mock'))
})

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.unstubAllGlobals()
})

async function openLocalView() {
  render(<Admin />)
  await userEvent.click(await screen.findByRole('button', { name: 'Local' }))
}

describe('pestaña "Datos del local": upload de logo y banner', () => {
  const pngFile = new File(['png'], 'logo.png', { type: 'image/png' })

  it('ya no hay inputs de URL: la imagen se sube con un input de archivo', async () => {
    stubFetch()
    await openLocalView('Datos del local')

    expect(screen.queryByLabelText('URL del logo')).toBeNull()
    expect(screen.queryByLabelText('URL del banner')).toBeNull()
    expect(screen.getByLabelText('Elegir imagen del logo')).toBeTruthy()
    expect(screen.getByLabelText('Elegir imagen del banner')).toBeTruthy()
  })

  it('muestra la dimensión recomendada por campo: logo cuadrado, banner panorámico', async () => {
    stubFetch()
    await openLocalView('Datos del local')

    // Medidas reales de render en la carta: el logo se muestra redondo (72px)
    // y el banner a ancho completo con alto ~170-260px (≈4:1).
    const logoHint = screen.getByText(/512×512/)
    expect(logoHint.textContent).toMatch(/redonda|redondo|circular/i)
    const bannerHint = screen.getByText(/1600×400/)
    expect(bannerHint.textContent).toMatch(/panorá/i)
    // Ambos advierten formato y tamaño.
    expect(logoHint.textContent).toMatch(/JPG, PNG o WebP/)
    expect(bannerHint.textContent).toMatch(/5 MB/)
  })

  it('elegir un archivo lo sube a POST /api/business/logo y la preview muestra la URL devuelta', async () => {
    stubFetch()
    await openLocalView('Datos del local')

    await userEvent.upload(screen.getByLabelText('Elegir imagen del logo'), pngFile)

    await waitFor(() => {
      const call = calls.find((c) => c.url.includes('/api/business/logo'))
      expect(call?.method).toBe('POST')
      expect((call?.body as FormData)?.get?.('file')).toBeTruthy()
    })
    await waitFor(() => {
      expect(screen.getByAltText('Vista previa del logo')).toHaveAttribute('src', '/api/images/business/logo-nuevo.png')
    })
  })

  it('formato rechazado: error inline y ningún POST al backend', async () => {
    stubFetch()
    await openLocalView('Datos del local')

    const txt = new File(['txt'], 'logo.txt', { type: 'text/plain' })
    // applyAccept: false: el input tiene accept de imagen y user-event filtraría
    // el .txt antes del change — la validación local del formulario es justo
    // lo que este test ejercita.
    await userEvent.upload(screen.getByLabelText('Elegir imagen del logo'), txt, { applyAccept: false })

    expect(await screen.findByText('Formato no soportado: usá JPG, PNG o WebP')).toBeTruthy()
    expect(calls.some((c) => c.url.includes('/api/business/logo'))).toBe(false)
  })

  it('quitar la imagen hace DELETE /api/business/logo y la preview vuelve a "Sin imagen"', async () => {
    stubFetch()
    await openLocalView('Datos del local')

    await userEvent.upload(screen.getByLabelText('Elegir imagen del logo'), pngFile)
    // El botón Quitar sólo existe cuando hay imagen cargada: esperarlo acá
    // sincroniza con la respuesta del POST (el span vacío de la preview
    // existe siempre y no sirve de sincronización).
    await screen.findByRole('button', { name: 'Quitar logo' })
    await userEvent.click(screen.getByRole('button', { name: 'Quitar logo' }))

    await waitFor(() => {
      const del = calls.find((c) => c.url.includes('/api/business/logo') && c.method === 'DELETE')
      expect(del).toBeTruthy()
    })
    // El logo volvió al estado vacío: ya no hay <img> de preview subida.
    expect(document.querySelector('img.admin-profile-preview')).toBeNull()
  })
})

describe('vista Local: pestañas', () => {
  it('muestra tres pestañas y por defecto "Datos del local" sin horarios ni QR', async () => {
    stubFetch()
    await openLocalView()

    expect(screen.getByRole('tab', { name: 'Datos del local' })).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'Horarios' })).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'QR y mesas' })).toBeTruthy()

    // El formulario de datos está en la pestaña activa.
    expect(screen.getByLabelText('Nombre del local')).toBeTruthy()
    // Horarios y QR/mesas NO están montados en la pestaña de datos.
    expect(screen.queryByRole('heading', { name: 'Horario de atención' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Fechas especiales' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Mesas' })).toBeNull()
  })

  it('pestaña "Horarios": horario semanal y fechas especiales, con su propio guardar y sin los campos de datos', async () => {
    stubFetch()
    await openLocalView()
    await userEvent.click(screen.getByRole('tab', { name: 'Horarios' }))

    expect(await screen.findByRole('heading', { name: 'Horario de atención' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Fechas especiales' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeTruthy()
    expect(screen.queryByLabelText('Nombre del local')).toBeNull()
  })

  it('pestaña "QR y mesas": pide el QR público (GET /api/business/qr con Bearer) y lo muestra', async () => {
    stubFetch()
    await openLocalView()
    await userEvent.click(screen.getByRole('tab', { name: 'QR y mesas' }))

    expect(await screen.findByRole('heading', { name: 'QR del menú público' })).toBeTruthy()
    await waitFor(() => {
      expect(screen.getByAltText('QR del menú público')).toBeTruthy()
    })
    const qrCall = calls.find((c) => c.url.includes('/api/business/qr'))
    expect(qrCall).toBeTruthy()
    // El botón de descarga existe junto a la vista previa.
    expect(screen.getByRole('button', { name: 'Descargar QR del menú' })).toBeTruthy()
  })

  it('pestaña "QR y mesas": lista las mesas de GET /api/tables con su estado y sus descargas', async () => {
    stubFetch()
    await openLocalView()
    await userEvent.click(screen.getByRole('tab', { name: 'QR y mesas' }))

    expect(await screen.findByText('Mesa 1')).toBeTruthy()
    expect(screen.getByText('Mesa 2')).toBeTruthy()
    const tablesCall = calls.find((c) => c.url.includes('/api/tables'))
    expect(tablesCall?.url).toContain('/api/tables')

    // Descargas por mesa y el PDF de todas las mesas (GET con plantilla).
    await userEvent.click(screen.getByRole('button', { name: 'Descargar QR PDF Mesa 1' }))
    await userEvent.click(screen.getByRole('button', { name: 'Descargar QR de todas las mesas' }))
    const pdfCalls = calls.filter((c) => c.url.includes('qr/pdf'))
    expect(pdfCalls.length).toBe(2)
    expect(pdfCalls[0].url).toContain('/api/tables/11/qr/pdf')
    expect(pdfCalls[1].url).toContain('/api/tables/qr/pdf')
    expect(pdfCalls[1].url).toContain('template=')
  })

  it('alta por lote: POST /api/tables/bulk con la cantidad pedida y refresca la lista', async () => {
    stubFetch({ bulkTables: [{ id: 13, tableNumber: 3, tableName: 'Mesa 3' }] })
    await openLocalView()
    await userEvent.click(screen.getByRole('tab', { name: 'QR y mesas' }))
    await screen.findByText('Mesa 1')

    const input = screen.getByLabelText('Cantidad de mesas a generar')
    await userEvent.clear(input)
    await userEvent.type(input, '5')
    await userEvent.click(screen.getByRole('button', { name: 'Generar mesas' }))

    const bulk = calls.find((c) => c.url.includes('/api/tables/bulk'))
    expect(bulk?.method).toBe('POST')
    expect(JSON.parse(bulk?.body ?? '{}')).toEqual({ quantity: 5 })
    // Refresca la lista después de generar (segundo GET /api/tables).
    const listCalls = calls.filter((c) => c.url.includes('/api/tables') && (c.method ?? 'GET') === 'GET')
    expect(listCalls.length).toBeGreaterThanOrEqual(2)
  })

  it('baja de mesa: DELETE /api/tables/{id} sólo tras confirmar en el diálogo', async () => {
    stubFetch()
    await openLocalView()
    await userEvent.click(screen.getByRole('tab', { name: 'QR y mesas' }))
    await screen.findByText('Mesa 1')

    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)
    await userEvent.click(screen.getByRole('button', { name: 'Eliminar Mesa 1' }))

    // El diálogo pide confirmación antes de tocar el backend.
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)
    await userEvent.click(await screen.findByRole('button', { name: 'Confirmar' }))

    await waitFor(() => {
      expect(calls.some((c) => c.method === 'DELETE' && c.url.includes('/api/tables/11'))).toBe(true)
    })
  })
})

describe('vista Local: estado de carga', () => {
  it('shows the dashboard-style skeleton while the profile loads', async () => {
    let resolveProfile!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/business/profile')) {
        return new Promise<Response>((resolve) => {
          resolveProfile = resolve
        })
      }
      return { ok: false, status: 404, json: async () => ({ message: 'unexpected' }) } as Response
    }))

    render(<Admin />)
    await userEvent.click(await screen.findByRole('button', { name: 'Local' }))

    // While the profile is pending: the dashboard-style skeleton instead of
    // the old `return null` (an invisible, silent section).
    expect(screen.getByRole('status', { name: /cargando el perfil/i })).toBeInTheDocument()

    act(() => resolveProfile({ ok: true, status: 200, json: async () => PROFILE } as Response))
    expect(await screen.findByLabelText('Nombre del local')).toHaveValue('Foodtruck Demo')
    expect(screen.queryByRole('status', { name: /cargando el perfil/i })).not.toBeInTheDocument()
  })
})
