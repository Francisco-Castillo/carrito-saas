/**
 * ComboModal × imagen propia (odd/tasks/combo-images.md, S1/T3): el modal de
 * crear/editar combo maneja la imagen con el MISMO flujo que ProductModal —
 * preview con "Cambiar imagen", "Quitar imagen", y el upload viaja DESPUÉS
 * del guardado del combo (un fallo de imagen no revierte el guardado).
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ComboModal from '../src/pages/admin/ComboModal'

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data })

const COMBO_EDIT = {
  id: 5,
  name: 'Combo Editable',
  price: 9000,
  categoryId: 7,
  active: true,
  items: [{ productId: 990052, productName: 'Clasica', quantity: 1 }],
  imageUrl: '/api/images/combos/vieja.jpg',
}

interface FetchCall {
  url: string
  method: string
  body?: FormData | string
}

function stubFetch() {
  const calls: FetchCall[] = []
  const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const u = String(url instanceof Request ? url.url : url)
    const method = init?.method ?? 'GET'
    calls.push({ url: u, method, body: init?.body as FormData | string | undefined })
    if (u.startsWith('/api/categorias')) return okJson([{ id: 7, name: 'Hamburguesas' }])
    if (u.startsWith('/api/products')) return okJson([{ id: 990052, name: 'Clasica', price: 7500 }])
    if (u === '/api/combos/5' && method === 'PUT') return okJson({ id: 5, name: 'Combo Editable' })
    if (u === '/api/combos/5/image' && method === 'POST') return okJson({ id: 5, imageUrl: '/api/images/combos/nueva.jpg' })
    if (u === '/api/combos/5/image' && method === 'DELETE') return okJson({ id: 5, imageUrl: null })
    return okJson({})
  })
  vi.stubGlobal('fetch', fetchMock)
  return calls
}

const PNG_FILE = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'combo.png', { type: 'image/png' })

describe('ComboModal — imagen propia del combo', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('en edición muestra el preview de la imagen existente con Cambiar y Quitar', () => {
    const calls = stubFetch()

    render(<ComboModal combo={COMBO_EDIT} onClose={vi.fn()} onSaved={vi.fn()} />)

    expect(document.querySelector('img[src="/api/images/combos/vieja.jpg"]')).not.toBeNull()
    expect(screen.getByText('Cambiar imagen')).toBeInTheDocument()
    expect(screen.getByText('Quitar imagen')).toBeInTheDocument()
    expect(calls.length).toBeGreaterThan(0)
  })

  it('sube la imagen elegida DESPUÉS de guardar el combo', async () => {
    const calls = stubFetch()
    const user = userEvent.setup()

    render(<ComboModal combo={COMBO_EDIT} onClose={vi.fn()} onSaved={vi.fn()} />)

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    await user.upload(input, PNG_FILE)

    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => {
      expect(calls.some((c) => c.url === '/api/combos/5/image' && c.method === 'POST')).toBe(true)
    })
    // El guardado del combo fue antes que el upload de la imagen.
    const putIndex = calls.findIndex((c) => c.url === '/api/combos/5' && c.method === 'PUT')
    const postIndex = calls.findIndex((c) => c.url === '/api/combos/5/image' && c.method === 'POST')
    expect(putIndex).toBeLessThan(postIndex)
  })

  it('Quitar imagen borra la imagen del combo al guardar', async () => {
    const calls = stubFetch()
    const user = userEvent.setup()

    render(<ComboModal combo={COMBO_EDIT} onClose={vi.fn()} onSaved={vi.fn()} />)

    await user.click(screen.getByText('Quitar imagen'))
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => {
      expect(calls.some((c) => c.url === '/api/combos/5/image' && c.method === 'DELETE')).toBe(true)
    })
    expect(calls.some((c) => c.url === '/api/combos/5/image' && c.method === 'POST')).toBe(false)
  })
})
