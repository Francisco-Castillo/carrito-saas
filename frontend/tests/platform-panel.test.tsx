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

/** Helper tipado para acotar una fila dentro de un contenedor (modal). */
function getByTextWithin(container: HTMLElement, text: string): HTMLElement {
  const cell = within(container).getByText(text)
  const row = cell.closest('tr')
  if (!row) throw new Error('row not found for ' + text)
  return row
}

function stubSession() {
  localStorage.setItem('token', 'header.payload.signature')
  // ADMIN es el rol exclusivo de la plataforma (simplificacion-roles.md S2):
  // ya no existe PLATFORM_ADMIN.
  localStorage.setItem('role', 'ADMIN')
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
    // El slug se auto-completa desde el nombre ('local-nuevo'): el flujo
    // normal ya no lo tipea a mano (ver los tests de UX de abajo).
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

  it('opens the users section of a client and lists its users', async () => {
    const USERS = [
      { username: 'owner-uno', email: 'owner-uno@test.dev', role: 'OWNER', active: true },
      { username: 'kitchen-uno', email: 'kitchen-uno@test.dev', role: 'KITCHEN', active: false },
    ]
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients/1000000001/users') {
        return okJson(USERS)
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

    await user.click(within(rowOf('Local Uno')).getByRole('button', { name: /usuarios/i }))

    // The users section answers with the client's users: role and state
    // visible per row — scoped to the modal (the listing behind it also
    // shows the owner username).
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/platform/clients/1000000001/users',
      expect.anything(),
    ))
    const modal = await screen.findByRole('dialog')
    expect(within(modal).getByText('owner-uno')).toBeTruthy()
    expect(within(modal).getByText('kitchen-uno')).toBeTruthy()
    // The badge is on the deactivated user's row — exact text, because the
    // active owner's row carries a "Dar de baja" button that also matches
    // /de baja/i.
    const kitchenRow = getByTextWithin(modal, 'kitchen-uno')
    expect(within(kitchenRow).getByText('de baja', { exact: true })).toBeTruthy()
  })

  it('registers a locale user through the users section form', async () => {
    const USERS = [
      { username: 'owner-uno', email: 'owner-uno@test.dev', role: 'OWNER', active: true },
    ]
    const created = {
      username: 'cajero-uno',
      email: 'cajero-uno@test.dev',
      role: 'KITCHEN',
      active: true,
    }
    let userCalls = 0
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients/1000000001/users' && init?.method === 'POST') {
        return { ok: true, status: 201, json: async () => created }
      }
      if (u === '/api/platform/clients/1000000001/users') {
        userCalls += 1
        return userCalls === 1 ? okJson(USERS) : okJson([...USERS, created])
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

    await user.click(within(rowOf('Local Uno')).getByRole('button', { name: /usuarios/i }))
    await user.type(await screen.findByPlaceholderText('Usuario'), 'cajero-uno')
    await user.type(screen.getByPlaceholderText('Email'), 'cajero-uno@test.dev')
    await user.type(screen.getByPlaceholderText('Contraseña'), 'cajero-pass-123')
    await user.selectOptions(screen.getByLabelText(/rol/i), 'KITCHEN')
    await user.click(screen.getByRole('button', { name: /crear usuario/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/platform/clients/1000000001/users',
      expect.objectContaining({ method: 'POST' }),
    ))

    const postCall = fetchMock.mock.calls.find(
      ([u, init]) => (init as RequestInit).method === 'POST',
    )
    expect(JSON.parse(String((postCall![1] as RequestInit).body))).toEqual({
      username: 'cajero-uno',
      email: 'cajero-uno@test.dev',
      password: 'cajero-pass-123',
      role: 'KITCHEN',
    })

    // The list refreshes with the new user.
    expect(await screen.findByText('cajero-uno')).toBeTruthy()
  })

  it('deactivates a locale user through the users section', async () => {
    const USERS = [
      { username: 'owner-uno', email: 'owner-uno@test.dev', role: 'OWNER', active: true },
      { username: 'kitchen-uno', email: 'kitchen-uno@test.dev', role: 'KITCHEN', active: true },
    ]
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients/1000000001/users/kitchen-uno' && init?.method === 'DELETE') {
        return {
          ok: true,
          status: 200,
          json: async () => ({ ...USERS[1], active: false }),
        }
      }
      if (u === '/api/platform/clients/1000000001/users') {
        return okJson(USERS)
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

    await user.click(within(rowOf('Local Uno')).getByRole('button', { name: /usuarios/i }))
    const modal = await screen.findByRole('dialog')
    // Scope to the kitchen user's row inside the modal: the owner row has
    // its own "Dar de baja" button.
    const kitchenRow = getByTextWithin(modal, 'kitchen-uno')
    await user.click(within(kitchenRow).getByRole('button', { name: /dar de baja/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/platform/clients/1000000001/users/kitchen-uno',
      expect.objectContaining({ method: 'DELETE' }),
    ))
  })
})

describe('alta form UX: slug automático y validación inline', () => {
  it('auto-completa el slug normalizado desde el nombre del local', async () => {
    const fetchMock = vi.fn(async () => okJson(CLIENTS))
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByPlaceholderText('Nombre del local')).toBeTruthy())
    await user.type(screen.getByPlaceholderText('Nombre del local'), 'Mi Café & Bar')

    // El slug se genera en vivo: minúsculas, sin acentos, espacios y
    // símbolos a guiones. El operador ya no puede escribir un slug inválido
    // por descuido (causa histórica del 400 "sólo minúsculas").
    const slug = screen.getByPlaceholderText('Slug') as HTMLInputElement
    expect(slug.value).toBe('mi-cafe-bar')
  })

  it('no pisa el slug editado a mano por el operador', async () => {
    const fetchMock = vi.fn(async () => okJson(CLIENTS))
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByPlaceholderText('Nombre del local')).toBeTruthy())
    await user.type(screen.getByPlaceholderText('Nombre del local'), 'Local Nuevo')
    await user.clear(screen.getByPlaceholderText('Slug'))
    await user.type(screen.getByPlaceholderText('Slug'), 'mi-slug-elegido')
    // Seguir escribiendo el nombre NO regenera el slug que el operador eligió.
    await user.type(screen.getByPlaceholderText('Nombre del local'), '!')

    const slug = screen.getByPlaceholderText('Slug') as HTMLInputElement
    expect(slug.value).toBe('mi-slug-elegido')
  })

  it('valida inline y NO envía el POST cuando un campo es inválido', async () => {
    const fetchMock = vi.fn(async () => okJson(CLIENTS))
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByPlaceholderText('Nombre del local')).toBeTruthy())
    await user.type(screen.getByPlaceholderText('Nombre del local'), 'Local Valido')
    await user.type(screen.getByPlaceholderText('Usuario del dueño'), 'owner-v')
    await user.type(screen.getByPlaceholderText('Email del dueño'), 'owner-v@test.dev')
    // Contraseña corta: el formulario debe frenarla ANTES de la red.
    await user.type(screen.getByPlaceholderText('Contraseña del dueño'), 'corta')
    await user.click(screen.getByRole('button', { name: /dar de alta/i }))

    expect(await screen.findByText(/al menos 8 caracteres/i)).toBeTruthy()
    expect(fetchMock.mock.calls.some(([u, init]) => String(u) === '/api/platform/clients' && (init as RequestInit)?.method === 'POST')).toBe(false)
  })

  it('valida inline el formato de teléfono con pistas de formato', async () => {
    const fetchMock = vi.fn(async () => okJson(CLIENTS))
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByPlaceholderText('Nombre del local')).toBeTruthy())
    await user.type(screen.getByPlaceholderText('Nombre del local'), 'Local Valido')
    await user.type(screen.getByPlaceholderText('Teléfono'), '11-2233-ab')
    await user.type(screen.getByPlaceholderText('Usuario del dueño'), 'owner-v')
    await user.type(screen.getByPlaceholderText('Email del dueño'), 'owner-v@test.dev')
    await user.type(screen.getByPlaceholderText('Contraseña del dueño'), 'client-pass-123')
    await user.click(screen.getByRole('button', { name: /dar de alta/i }))

    // El error vive dentro del fieldset del teléfono (no el header de la
    // tabla de clientes) y el POST nunca sale.
    const phoneFieldset = screen.getByText('Teléfono', { selector: 'label > span' }).closest('fieldset')!
    await waitFor(() => expect(within(phoneFieldset).getByText(/sólo admite dígitos/i)).toBeTruthy())
    expect(fetchMock.mock.calls.some(([u, init]) => String(u) === '/api/platform/clients' && (init as RequestInit)?.method === 'POST')).toBe(false)
  })

  it('mapea el error 409 de slug del backend al campo slug', async () => {
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u === '/api/platform/clients' && init?.method === 'POST') {
        return { ok: false, status: 409, json: async () => ({ message: 'Ya existe un local con ese slug' }) }
      }
      if (u === '/api/platform/clients') return okJson(CLIENTS)
      throw new Error('unexpected fetch: ' + u)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<Platform />)

    const user = userEvent.setup()
    await waitFor(() => expect(screen.getByPlaceholderText('Nombre del local')).toBeTruthy())
    await user.type(screen.getByPlaceholderText('Nombre del local'), 'Duplicado')
    await user.type(screen.getByPlaceholderText('Usuario del dueño'), 'owner-d')
    await user.type(screen.getByPlaceholderText('Email del dueño'), 'owner-d@test.dev')
    await user.type(screen.getByPlaceholderText('Contraseña del dueño'), 'client-pass-123')
    await user.click(screen.getByRole('button', { name: /dar de alta/i }))

    // El mensaje del backend vive dentro del fieldset del slug, junto al
    // campo que hay que corregir.
    const slugFieldset = screen.getByText('Slug', { selector: 'label > span' }).closest('fieldset')!
    await waitFor(() => expect(within(slugFieldset).getByText('Ya existe un local con ese slug')).toBeTruthy())
  })
})
