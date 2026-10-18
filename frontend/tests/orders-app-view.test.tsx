/**
 * App shell wiring for the orders console (T5): the single bundle must render
 * the Orders page on /pedidos, and the main screens (Dashboard, Admin) must
 * expose an equivalent nav entry — Spanish label "Pedidos", clean path,
 * without redesigning their navigation.
 */
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../src/App'
import Dashboard from '../src/pages/Dashboard'
import Admin from '../src/pages/Admin'
import { seedPanelSession } from './helpers/ordersFixtures'

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    activate(): void {}
    deactivate(): void {}
    subscribe(): void {}
    publish(): void {}
  },
}))

function at(path: string) {
  window.history.replaceState(null, '', path)
}

function stubFailingFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok: false, status: 500, json: async () => [] }))
  )
}

beforeEach(() => {
  seedPanelSession()
})

afterEach(() => {
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('App renders the orders view (T5)', () => {
  it('/pedidos renders the orders console', async () => {
    stubFailingFetch()
    at('/pedidos')

    render(<App />)

    // The orders console chrome, not the login form and not the dashboard.
    expect(await screen.findByRole('heading', { name: 'Pedidos' })).toBeTruthy()
    expect(screen.queryByPlaceholderText('Usuario')).toBeNull()
  })

  it('unknown paths still fall back to the login view (previous behavior preserved)', () => {
    stubFailingFetch()
    at('/some-unknown-place')

    render(<App />)

    expect(screen.getByPlaceholderText('Usuario')).toBeTruthy()
  })
})

describe('main screens link to the orders console (S3)', () => {
  it('the Dashboard topbar carries a Pedidos entry point', () => {
    stubFailingFetch()

    render(<Dashboard />)

    const link = screen.getByRole('link', { name: 'Pedidos' })
    expect(link.getAttribute('href')).toBe('/pedidos')
  })

  it('the Admin sidebar carries a Pedidos entry point', () => {
    stubFailingFetch()

    render(<Admin />)

    const link = screen.getByRole('link', { name: 'Pedidos' })
    expect(link.getAttribute('href')).toBe('/pedidos')
  })
})
