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
 * La pestaña tiene que EXPLICARSE (specs S1-S5 de
 * odd/tasks/qr-mesas-ux.md): diferencia entre el QR público y el de cada
 * mesa, qué imprime cada plantilla, a qué URL apunta cada QR y qué mesas
 * va a crear el alta por lote antes de crearlas.
 *
 * Los endpoints de QR responden binario (image/png / application/pdf): el
 * stub de fetch entrega blobs y se stubbea URL.createObjectURL.
 */
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
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
    { id: 11, tableNumber: 1, tableName: 'Mesa 1', qrToken: 'tok-1', qrUrl: 'https://x/menu/foodtruck-demo?table=11', hasActiveOrder: false, activeOrderId: null },
    { id: 12, tableNumber: 2, tableName: 'Mesa 2', qrToken: 'tok-2', qrUrl: 'https://x/menu/foodtruck-demo?table=12', hasActiveOrder: true, activeOrderId: 77 },
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

function stubFetch(options?: { bulkTables?: unknown[]; deleteOk?: boolean; tablesPage?: unknown }) {
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
      return { ok: true, status: 200, json: async () => options?.tablesPage ?? TABLES_PAGE } as Response
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

  it('alta por lote: POST /api/tables/bulk con cuántas mesas NUEVAS se agregan y refresca la lista', async () => {
    stubFetch({ bulkTables: [{ id: 13, tableNumber: 3, tableName: 'Mesa 3' }] })
    await openLocalView()
    await userEvent.click(screen.getByRole('tab', { name: 'QR y mesas' }))
    await screen.findByText('Mesa 1')

    const input = screen.getByLabelText('Cantidad de mesas nuevas')
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

/**
 * Claridad de la pestaña "QR y mesas" (odd/tasks/qr-mesas-ux.md).
 *
 * Cada test fija UNA regla de comprensión: la diferencia entre el QR público
 * y el de mesa, qué imprime cada plantilla, qué mesas crea el alta por lote,
 * a dónde apunta el QR de cada mesa y cómo se ve sin descargarlo.
 */
describe('pestaña "QR y mesas": la configuración se explica sola', () => {
  async function openQrTab() {
    await openLocalView()
    await userEvent.click(screen.getByRole('tab', { name: 'QR y mesas' }))
  }

  it('S1: explica la diferencia entre el QR de cada mesa y el del menú público', async () => {
    stubFetch()
    await openQrTab()

    // Escanear el QR de una mesa abre la carta CON esa mesa identificada.
    expect(await screen.findByText(/carta se abre con esa mesa/i)).toBeTruthy()
    // El QR público, en cambio, no identifica mesa.
    expect(screen.getByText(/no identifica mesa/i)).toBeTruthy()
  })

  it('S2: cada plantilla de PDF dice cuántos QRs imprime por hoja', async () => {
    stubFetch()
    await openQrTab()

    const select = await screen.findByLabelText('Plantilla de PDF')
    const options = within(select).getAllByRole('option').map((o) => o.textContent ?? '')

    // Cada plantilla se explica sola: cuántos QRs salen por hoja.
    expect(options.some((t) => /1 por hoja/i.test(t))).toBe(true)
    expect(options.some((t) => /9 por hoja/i.test(t))).toBe(true)
    expect(options.some((t) => /16 por hoja/i.test(t))).toBe(true)
    // La grilla 4x4 ya tiene estrategia en el backend, así que sí se ofrece.
    expect(options.some((t) => /4\s*[x×]\s*4/i.test(t))).toBe(true)

    // La ayuda cuenta qué sale impreso con la plantilla elegida.
    await userEvent.selectOptions(select, 'ACRYLIC')
    expect(screen.getByText(/pie de acrílico o portarretrato/i)).toBeTruthy()

    // La 4x4 está cableada de verdad: la ayuda aparece y el PDF general
    // pide la plantilla elegida al backend.
    await userEvent.selectOptions(select, 'GRID_4X4')
    expect(screen.getByText(/Dieciséis QRs|dieciséis QRs/i)).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Descargar QR de todas las mesas' }))
    const pdfCall = calls.find((c) => c.url.includes('/api/tables/qr/pdf'))
    expect(pdfCall).toBeTruthy()
    expect(pdfCall!.url).toContain('template=GRID_4X4')
  })

  it('S3: el alta de mesas dice cuántas mesas NUEVAS crea y con qué números', async () => {
    stubFetch()
    await openQrTab()
    await screen.findByText('Mesa 1')

    const input = screen.getByLabelText('Cantidad de mesas nuevas')
    await userEvent.clear(input)
    await userEvent.type(input, '5')

    // BACKEND: POST /api/tables/bulk recibe cuántas mesas AGREGAR y las numera
    // a continuación de la más alta — con Mesa 1 y Mesa 2, "5" crea 3, 4, 5,
    // 6 y 7 (antes "5" creaba solo 3, 4 y 5, y era imposible de anticipar).
    expect(await screen.findByText(/Mesa 3 a Mesa 7/)).toBeTruthy()
    expect(screen.getByText(/Vas a quedar con 7 mesas/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Generar mesas' })).toBeEnabled()

    // Una sola mesa nueva también se anticipa sin ambigüedad.
    await userEvent.clear(input)
    await userEvent.type(input, '1')
    expect(await screen.findByText(/Se va a crear: Mesa 3\./)).toBeTruthy()
  })

  it('S3b: crear mesas confirma cuántas se crearon', async () => {
    stubFetch({
      bulkTables: [
        { id: 13, tableNumber: 3, tableName: 'Mesa 3' },
        { id: 14, tableNumber: 4, tableName: 'Mesa 4' },
        { id: 15, tableNumber: 5, tableName: 'Mesa 5' },
        { id: 16, tableNumber: 6, tableName: 'Mesa 6' },
      ],
    })
    await openQrTab()
    await screen.findByText('Mesa 1')

    const input = screen.getByLabelText('Cantidad de mesas nuevas')
    await userEvent.clear(input)
    await userEvent.type(input, '4')
    await userEvent.click(screen.getByRole('button', { name: 'Generar mesas' }))

    expect(await screen.findByText('Se crearon 4 mesas: Mesa 3, Mesa 4, Mesa 5 y Mesa 6')).toBeTruthy()
  })

  it('S4: cada mesa muestra la URL a la que apunta su QR', async () => {
    stubFetch()
    await openQrTab()

    expect(await screen.findByText('https://x/menu/foodtruck-demo?table=11')).toBeTruthy()
    expect(screen.getByText('https://x/menu/foodtruck-demo?table=12')).toBeTruthy()
  })

  it('S5: el QR público muestra el acceso a la carta del local', async () => {
    stubFetch()
    await openQrTab()

    const link = await screen.findByRole('link', { name: /ver la carta/i })
    expect(link).toHaveAttribute('href', '/foodtruck-demo')
  })

  it('S6: "Ver QR" muestra la vista previa de esa mesa sin descargar el archivo', async () => {
    stubFetch()
    await openQrTab()
    await screen.findByText('Mesa 1')

    await userEvent.click(screen.getByRole('button', { name: 'Ver QR de Mesa 1' }))

    await waitFor(() => {
      expect(calls.some((c) => c.url.includes('/api/tables/11/qr'))).toBe(true)
    })
    expect(await screen.findByAltText('Vista previa del QR de Mesa 1')).toBeTruthy()
  })

  it('S7: una mesa eliminada se muestra como Eliminada y sin acciones', async () => {
    // BACKEND: la baja es LÓGICA — la fila sobrevive con status "DISABLED" y
    // GET /api/tables la sigue devolviendo, pero su QR público ya no funciona.
    stubFetch({
      tablesPage: {
        content: [
          { id: 11, tableNumber: 1, tableName: 'Mesa 1', qrToken: 'tok-1', qrUrl: 'https://x/menu/foodtruck-demo?table=11', hasActiveOrder: false, activeOrderId: null },
          { id: 99, tableNumber: 2, tableName: 'Mesa 2', qrToken: 'tok-2', qrUrl: 'https://x/menu/foodtruck-demo?table=99', hasActiveOrder: false, activeOrderId: null, status: 'DISABLED' },
        ],
        totalElements: 2,
        totalPages: 1,
        number: 0,
      },
    })
    await openQrTab()

    // Se muestra como Eliminada, no como Libre, con el aviso de que su QR murió.
    expect(await screen.findByText('Mesa 2')).toBeTruthy()
    expect(screen.getByText('Eliminada')).toBeTruthy()
    expect(screen.getByText(/Su QR ya no funciona\./)).toBeTruthy()
    // La eliminada no ofrece NADA para cliquear: ni ver, ni descargar, ni borrar.
    expect(screen.queryByRole('button', { name: /Ver QR de Mesa 2|Descargar QR PNG Mesa 2|Eliminar Mesa 2/ })).toBeNull()
    // La mesa viva conserva todas sus acciones.
    expect(screen.getByRole('button', { name: 'Ver QR de Mesa 1' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Eliminar Mesa 1' })).toBeTruthy()
    // El destino del QR de la eliminada no se muestra: ese QR ya no lleva a nada.
    expect(screen.queryByText('https://x/menu/foodtruck-demo?table=99')).toBeNull()
  })

  it('S8: el alta reutiliza el número libre que dejó una mesa eliminada', async () => {
    // BACKEND: el bulk numera con el NÚMERO LIBRE más bajO — libre = no usado
    // por una mesa viva; una eliminada libera el suyo. Con vivas {1, 3} y 3
    // nuevas, crea la 2, la 4 y la 5 (no 4, 5 y 6).
    stubFetch({
      tablesPage: {
        content: [
          { id: 11, tableNumber: 1, tableName: 'Mesa 1', qrToken: 'tok-1', qrUrl: 'https://x/menu/foodtruck-demo?table=11', hasActiveOrder: false, activeOrderId: null },
          { id: 12, tableNumber: 3, tableName: 'Mesa 3', qrToken: 'tok-3', qrUrl: 'https://x/menu/foodtruck-demo?table=12', hasActiveOrder: false, activeOrderId: null },
          { id: 99, tableNumber: 2, tableName: 'Mesa 2', qrToken: 'tok-2', qrUrl: 'https://x/menu/foodtruck-demo?table=99', hasActiveOrder: false, activeOrderId: null, status: 'DISABLED' },
        ],
        totalElements: 3,
        totalPages: 1,
        number: 0,
      },
    })
    await openQrTab()
    await screen.findByText('Mesa 1')

    const input = screen.getByLabelText('Cantidad de mesas nuevas')
    await userEvent.clear(input)
    await userEvent.type(input, '3')

    // La preview promete exactamente los números que va a crear el backend:
    // el 2 liberado por la eliminada, después la 4 y la 5.
    expect(await screen.findByText(/Mesa 2.*Mesa 4.*Mesa 5/)).toBeTruthy()
    // 2 vivas + 3 nuevas = 5 en total (la eliminada no cuenta).
    expect(screen.getByText(/Vas a quedar con 5 mesas/)).toBeTruthy()
  })
})
