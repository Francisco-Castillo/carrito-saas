/**
 * Platform operator panel (odd/tasks/plataforma-altas.md, S1/S3/S4): the
 * /platform view lists clients and registers new ones in one form.
 *
 * Contract under test:
 * - The view lists GET /api/platform/clients with the Bearer token: every
 *   client row shows the locale (name, slug, phone) and its owner account.
 * - The alta form POSTs the nested payload the backend contract expects
 *   (business{name,slug,phone} + owner{username,email,password}) and shows
 *   the backend's legible Spanish error inline (e.g. duplicate slug 409).
 * - On success the form clears and the list refreshes with the new client.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Platform from '../src/pages/Platform'

const CLIENTS = [
  {
    businessId: 1000000001,
    businessName: 'Local Uno',
    slug: 'local-uno',
    phone: '1150001111',
    ownerUsername: 'owner-uno',
    ownerEmail: 'owner-uno@test.dev',
  },
  {
    businessId: 1000000002,
    businessName: 'Local Dos',
    slug: 'local-dos',
    phone: null,
    ownerUsername: null,
    ownerEmail: null,
  },
]

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data })

/** La fila de la tabla del cliente: las acciones por fila viven acá —
 * screen.getByRole Matchearía el botón de TODAS las filas. */
function rowOf(businessName: string): HTMLElement {
  const cell = screen.getByText(businessName)
  const row = cell.closest('tr')
  if (!row) throw new Error('row not found for ' + businessName)
  return row
}

function stubSession() {
  localStorage.setItem('token', 'header.payload.signature')
  localStorage.setItem('role', 'PLATFORM_ADMIN')
  localStorage.setItem('username', 'platform-admin')
  localStorage.setItem('expiresAt', String(Date.now() + 60_000))
}

beforeEach(() => {
  localStorage.clear()
  stubSession()
})

describe('platform operator view', () => {
  it('lists the clients from GET /api/platform/clients with locale and owner', async () => {
    const fetchMock = vi.fn(async () => okJson(CLIENTS))
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/platform/clients',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer header.payload.signature' }) }),
    ))

    expect(await screen.findByText('Local Uno')).toBeTruthy()
    expect(screen.getByText('local-uno')).toBeTruthy()
    expect(screen.getByText('owner-uno')).toBeTruthy()
    // The locale without an owner still shows, honestly marked.
    expect(screen.getByText('Local Dos')).toBeTruthy()
    expect(screen.getByText(/sin dueño/i)).toBeTruthy()
  })

  it('registers a client through the alta form and refreshes the listing', async () => {
    const created = {
      businessId: 1000000003,
      businessName: 'Local Nuevo',
      slug: 'local-nuevo',
      phone: '1150002222',
      ownerUsername: 'owner-nuevo',
      ownerEmail: 'owner-nuevo@test.dev',
    }
    let listingCalls = 0
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients' && init?.method === 'POST') {
        return { ok: true, status: 201, json: async () => created }
      }
      if (u === '/api/platform/clients') {
        // First GET = initial load (without the new client); the GET after
        // the alta = the refresh, which must include it.
        listingCalls += 1
        return listingCalls === 1 ? okJson(CLIENTS) : okJson([...CLIENTS, created])
      }
      throw new Error('unexpected fetch: ' + u)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByPlaceholderText('Nombre del local')).toBeTruthy())
    await user.type(screen.getByPlaceholderText('Nombre del local'), 'Local Nuevo')
    await user.type(screen.getByPlaceholderText('Slug'), 'local-nuevo')
    await user.type(screen.getByPlaceholderText('Teléfono'), '1150002222')
    await user.type(screen.getByPlaceholderText('Usuario del dueño'), 'owner-nuevo')
    await user.type(screen.getByPlaceholderText('Email del dueño'), 'owner-nuevo@test.dev')
    await user.type(screen.getByPlaceholderText('Contraseña del dueño'), 'client-pass-123')
    await user.click(screen.getByRole('button', { name: /dar de alta/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/platform/clients',
      expect.objectContaining({ method: 'POST' }),
    ))

    const postCall = fetchMock.mock.calls.find(
      ([u, init]) => String(u) === '/api/platform/clients' && (init as RequestInit).method === 'POST',
    )
    expect(JSON.parse(String((postCall![1] as RequestInit).body))).toEqual({
      business: { name: 'Local Nuevo', slug: 'local-nuevo', phone: '1150002222' },
      owner: { username: 'owner-nuevo', email: 'owner-nuevo@test.dev', password: 'client-pass-123' },
    })

    // The form clears and the listing refreshes with the new client.
    await waitFor(() => expect((screen.getByPlaceholderText('Nombre del local') as HTMLInputElement).value).toBe(''))
    expect(await screen.findByText('Local Nuevo')).toBeTruthy()
  })

  it('shows the backend legible error inline when the alta fails (duplicate slug)', async () => {
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients' && init?.method === 'POST') {
        return {
          ok: false,
          status: 409,
          json: async () => ({ message: 'Ya existe un local con ese slug' }),
        }
      }
      if (u === '/api/platform/clients') {
        return okJson(CLIENTS)
      }
      throw new Error('unexpected fetch: ' + u)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByPlaceholderText('Nombre del local')).toBeTruthy())
    await user.type(screen.getByPlaceholderText('Nombre del local'), 'Otro')
    await user.type(screen.getByPlaceholderText('Slug'), 'local-uno')
    await user.type(screen.getByPlaceholderText('Usuario del dueño'), 'owner-x')
    await user.type(screen.getByPlaceholderText('Email del dueño'), 'owner-x@test.dev')
    await user.type(screen.getByPlaceholderText('Contraseña del dueño'), 'client-pass-123')
    await user.click(screen.getByRole('button', { name: /dar de alta/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ya existe un local con ese slug')
  })

  it('deactivates a client through the row action after confirmation', async () => {
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients/1000000001' && init?.method === 'DELETE') {
        return { ok: true, status: 200, json: async () => ({ ...CLIENTS[0], active: false }) }
      }
      if (u === '/api/platform/clients') {
        return okJson(CLIENTS)
      }
      throw new Error('unexpected fetch: ' + u)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByText('Local Uno')).toBeTruthy())

    // The row action asks for confirmation before the DELETE — the baja is
    // consequential (S3), the click-through does not happen silently.
    await user.click(within(rowOf('Local Uno')).getByRole('button', { name: /dar de baja/i }))
    expect(fetchMock.mock.calls.some(([u, init]) => init?.method === 'DELETE')).toBe(false)

    await user.click(screen.getByRole('button', { name: /confirmar/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/platform/clients/1000000001',
      expect.objectContaining({ method: 'DELETE' }),
    ))
  })

  it('reactivates a deactivated client and marks the row in the listing', async () => {
    const DEACTIVATED = [
      { ...CLIENTS[0], active: false },
      { ...CLIENTS[1], active: true },
    ]
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients/1000000001' && init?.method === 'PUT') {
        return { ok: true, status: 200, json: async () => ({ ...DEACTIVATED[0], active: true }) }
      }
      if (u === '/api/platform/clients') {
        return okJson(DEACTIVATED)
      }
      throw new Error('unexpected fetch: ' + u)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByText('Local Uno')).toBeTruthy())

    // The deactivated row is honestly marked in the listing: the badge
    // lives in the row of the deactivated client (the active row shows
    // its own "Dar de baja" button, which also matches /de baja/i).
    expect(within(rowOf('Local Uno')).getByText(/de baja/i)).toBeTruthy()

    await user.click(within(rowOf('Local Uno')).getByRole('button', { name: /reactivar/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/platform/clients/1000000001',
      expect.objectContaining({ method: 'PUT' }),
    ))

    const putCall = fetchMock.mock.calls.find(
      ([u, init]) => (init as RequestInit).method === 'PUT',
    )
    expect(JSON.parse(String((putCall![1] as RequestInit).body))).toEqual({ active: true })
  })

  it('edits a client through the inline form with the safe fields only', async () => {
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients/1000000001' && init?.method === 'PUT') {
        return { ok: true, status: 200, json: async () => ({ ...CLIENTS[0], businessName: 'Local Editado' }) }
      }
      if (u === '/api/platform/clients') {
        return okJson(CLIENTS)
      }
      throw new Error('unexpected fetch: ' + u)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByText('Local Uno')).toBeTruthy())

    await user.click(within(rowOf('Local Uno')).getByRole('button', { name: /editar/i }))

    // The edit form preloads the current safe fields; the slug is shown
    // READ-ONLY as a note inside the modal (S2: the public identity does
    // not move) — not as an editable input. The slug text also lives in
    // the listing row, so the assertion is scoped to the modal.
    const nameInput = await screen.findByDisplayValue('Local Uno')
    const modal = screen.getByRole('dialog')
    expect(within(modal).getByText('local-uno')).toBeTruthy()

    await user.clear(nameInput)
    await user.type(nameInput, 'Local Editado')
    await user.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/platform/clients/1000000001',
      expect.objectContaining({ method: 'PUT' }),
    ))

    const putCall = fetchMock.mock.calls.find(
      ([u, init]) => String(u) === '/api/platform/clients/1000000001' && (init as RequestInit).method === 'PUT',
    )
    // The form preloads the current safe fields and sends what it shows:
    // the edited name plus the untouched phone/email (the backend treats
    // them as updates to the same values). The slug never travels.
    expect(JSON.parse(String((putCall![1] as RequestInit).body))).toEqual({
      name: 'Local Editado',
      phone: '1150001111',
      ownerEmail: 'owner-uno@test.dev',
    })
  })

  it('resets the owner password through the row action', async () => {
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients/1000000001/password' && init?.method === 'POST') {
        return { ok: true, status: 200, json: async () => CLIENTS[0] }
      }
      if (u === '/api/platform/clients') {
        return okJson(CLIENTS)
      }
      throw new Error('unexpected fetch: ' + u)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByText('Local Uno')).toBeTruthy())

    await user.click(within(rowOf('Local Uno')).getByRole('button', { name: /nueva clave/i }))

    const passwordInput = await screen.findByPlaceholderText('Nueva contraseña del dueño')
    await user.type(passwordInput, 'nueva-clave-789')
    await user.click(screen.getByRole('button', { name: /confirmar/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/platform/clients/1000000001/password',
      expect.objectContaining({ method: 'POST' }),
    ))

    const postCall = fetchMock.mock.calls.find(
      ([u, init]) => String(u) === '/api/platform/clients/1000000001/password',
    )
    expect(JSON.parse(String((postCall![1] as RequestInit).body))).toEqual({ password: 'nueva-clave-789' })
  })

  it('shows the backend legible error inline when a row action fails', async () => {
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients/1000000001/password' && init?.method === 'POST') {
        return {
          ok: false,
          status: 400,
          json: async () => ({ message: 'La contraseña debe tener al menos 8 caracteres' }),
        }
      }
      if (u === '/api/platform/clients') {
        return okJson(CLIENTS)
      }
      throw new Error('unexpected fetch: ' + u)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByText('Local Uno')).toBeTruthy())

    await user.click(within(rowOf('Local Uno')).getByRole('button', { name: /nueva clave/i }))
    const passwordInput = await screen.findByPlaceholderText('Nueva contraseña del dueño')
    await user.type(passwordInput, 'corta')
    await user.click(screen.getByRole('button', { name: /confirmar/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'La contraseña debe tener al menos 8 caracteres',
    )
  })
})
