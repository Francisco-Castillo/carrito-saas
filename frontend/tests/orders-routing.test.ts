/**
 * Routing contract for the orders console (T5 of odd/tasks/orders-console.md):
 * `/pedidos` joins the clean-path family of the SPA route table at the same
 * level as /dashboard, /admin and /kds (S3). Both entry-path families must
 * resolve — bare clean path, trailing slash and the legacy static-file
 * pattern — and the dev-only ?view= escape hatch must force the view too.
 */
import { afterEach, describe, expect, it } from 'vitest'
import { getView } from '../src/view'

function at(path: string) {
  window.history.replaceState(null, '', path)
}

afterEach(() => {
  window.history.replaceState(null, '', '/')
})

describe('orders console routing (/pedidos)', () => {
  it.each([
    ['/pedidos', 'orders'],
    ['/pedidos/', 'orders'],
    ['/pedidos/index.html', 'orders'],
  ])('%s resolves to the orders view', (path) => {
    at(path)
    expect(getView()).toBe('orders')
  })

  it('the dev-only ?view= escape hatch forces the orders view', () => {
    at('/?view=pedidos')
    expect(getView()).toBe('orders')
  })
})
