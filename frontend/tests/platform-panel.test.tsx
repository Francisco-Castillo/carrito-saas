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
import { render, screen, waitFor } from '@testing-library/react'
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
})
