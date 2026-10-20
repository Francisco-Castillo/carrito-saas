/**
 * El combo con imagen propia manda en el menú (odd/tasks/combo-images.md,
 * S1/T4): si el payload del combo trae imageUrl, la tarjeta la usa — la
 * derivación por componente más caro queda como FALLBACK para los combos sin
 * imagen propia.
 */
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Menu from '../src/pages/Menu'

const SLUG = 'combo-image-local'

const RESTAURANT = { name: 'Local Combos' }

const MENU_PAYLOAD = {
  business: { name: 'Local Combos', whatsappNumber: '541155500011' },
  products: [
    {
      id: 990052,
      name: 'Clasica',
      description: 'medallón',
      price: 7500,
      categoryId: 1,
      categoryName: 'Hamburguesas',
      imageUrl: '/dev-media/clasica.jpg',
    },
    {
      id: 990054,
      name: 'Coca-Cola 500ml',
      description: 'gaseosa',
      price: 2500,
      categoryId: 2,
      categoryName: 'Bebidas',
      imageUrl: '/dev-media/bebida-gaseosa.jpg',
    },
  ],
  combos: [],
}

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data })

function stubMenuFetch(menuPayload: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string | URL | Request) => {
      const u = String(url instanceof Request ? url.url : url)
      if (u.startsWith('/api/restaurants/slug/')) return okJson(RESTAURANT)
      if (u.startsWith('/api/menu/')) return okJson(menuPayload)
      return okJson({})
    }),
  )
  vi.stubGlobal('location', { search: `?restaurant=${SLUG}`, href: '', replace: vi.fn() })
}

async function renderMenuWith(menuPayload: unknown) {
  stubMenuFetch(menuPayload)
  render(<Menu />)
}

describe('la imagen propia del combo en el menú', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('usa la imagen propia del combo cuando el payload la trae', async () => {
    await renderMenuWith({
      ...MENU_PAYLOAD,
      combos: [
        {
          id: 990091,
          name: 'Combo Imagen Propia',
          price: 9000,
          categoryId: 1,
          imageUrl: '/api/images/combos/propia.jpg',
          items: [{ productId: 990052, productName: 'Clasica', quantity: 1 }],
        },
      ],
    })

    // La tarjeta del combo muestra la imagen SUBIDA, no la derivada del
    // componente (que sería clasica.jpg).
    const img = await screen.findByAltText('Combo Imagen Propia')
    expect(img.getAttribute('src')).toBe('/api/images/combos/propia.jpg')
  })

  it('deriva la imagen del componente más caro cuando el combo no tiene propia', async () => {
    await renderMenuWith({
      ...MENU_PAYLOAD,
      combos: [
        {
          id: 990092,
          name: 'Combo Sin Propia',
          price: 9000,
          categoryId: 1,
          items: [{ productId: 990052, productName: 'Clasica', quantity: 1 }],
        },
      ],
    })

    const img = await screen.findByAltText('Combo Sin Propia')
    expect(img.getAttribute('src')).toBe('/dev-media/clasica.jpg')
  })
})
