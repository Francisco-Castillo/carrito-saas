/**
 * Comanda printing (ticket printer via browser print):
 *
 *  - The ticket layout (Comanda) must carry every kitchen-relevant datum of
 *    the ManagementOrder: number, type, time, items with quantities, chosen
 *    options, item notes, customer, phone, address, table, payment, total
 *    and general notes — on screen it stays hidden (print-only container).
 *  - Manual trigger: an "Imprimir" button on each order card of the
 *    management panel prints that order's comanda.
 *  - Automatic trigger: a brand-new order entering as NEW prints its comanda
 *    once (same rule as the new-order ding). The first snapshot of the day
 *    stays silent and a POS-created order born READY prints nothing.
 */
import { act, cleanup, render, renderHook, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Orders from '../src/pages/Orders'
import Comanda from '../src/pages/orders/Comanda'
import { useOrdersFeed } from '../src/pages/orders/useOrdersFeed'
import { managementOrder, seedPanelSession } from './helpers/ordersFixtures'

// Shared sink of STOMP subscription callbacks: emitting one mimics the WS
// event the backend pushes on order changes, which is what drives the
// debounced refresh of the feed (the page never re-fetches on rerender).
const { mockSubscribers } = vi.hoisted(() => ({
  mockSubscribers: [] as Array<() => void>,
}))

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    private onConnect?: () => void;

    constructor(config: { onConnect?: () => void } = {}) {
      this.onConnect = config.onConnect;
    }

    activate(): void {
      this.onConnect?.();
    }

    deactivate(): void {}

    subscribe(_path: string, cb: () => void): void {
      mockSubscribers.push(cb);
    }

    publish(): void {}
  },
}))

/** Every live subscription fires once, like one backend broadcast. */
function emitOrdersEvent(): void {
  mockSubscribers.forEach((cb) => cb());
}

function stubOrdersFetch(
  orders: unknown[],
  options?: { profile?: Record<string, unknown> }
) {
  return vi.fn(async (input: RequestInfo | URL) => {
    if (String(input).startsWith('/api/business/orders')) {
      return { ok: true, status: 200, json: async () => orders }
    }
    if (String(input).startsWith('/api/business/profile')) {
      // Settings del negocio para la impresión de comandas: por defecto los
      // tests ejercitan el flujo con master ON y auto ON; los tests de
      // gateo pasan su propio profile.
      return {
        ok: true,
        status: 200,
        json: async () => options?.profile ?? { comandaPrintingEnabled: true, comandaAutoPrint: true, comandaDeliveryCopy: false },
      }
    }
    return { ok: false, status: 500, json: async () => ({}) }
  })
}

/** window.print exists in jsdom; a spy keeps assertions readable. */
const printSpy = () => vi.spyOn(window, 'print').mockReturnValue(undefined)

beforeEach(() => {
  seedPanelSession()
  mockSubscribers.length = 0
})

afterEach(cleanup)

const FULL_ORDER = managementOrder({
  orderId: 1,
  orderNumber: 101,
  orderType: 'DELIVERY',
  status: 'NEW',
  customerName: 'Cliente Test',
  customerPhone: '1155551234',
  customerAddress: 'Calle Falsa 123',
  paymentMethod: 'EFECTIVO',
  notes: 'Tocar timbre',
  total: 6400,
  items: [
    {
      name: 'Hamburguesa',
      quantity: 2,
      unitPrice: 3200,
      subtotal: 6400,
      notes: 'sin cebolla',
      selectedOptions: [{ groupName: 'Tipo de pan', optionName: 'Integral', priceDelta: 200 }],
    },
  ],
})

describe('comanda ticket layout', () => {
  it('renders only kitchen-relevant data: items, options, notes — no money, no contact', () => {
    render(<Comanda order={FULL_ORDER} />)

    const ticket = screen.getByTestId('comanda')
    expect(within(ticket).getByText(/#101/)).toBeTruthy()
    expect(within(ticket).getByText(/Delivery/)).toBeTruthy()
    expect(within(ticket).getByText(/2\s*×\s*Hamburguesa/)).toBeTruthy()
    expect(within(ticket).getByText(/sin cebolla/)).toBeTruthy()
    // Options WITHOUT the surcharge: money is not kitchen data.
    expect(within(ticket).getByText(/Tipo de pan: Integral/)).toBeTruthy()
    expect(within(ticket).queryByText(/\+\$\s*200/)).toBeNull()
    // The customer name stays (calling out retiro orders); phone, address,
    // payment method and TOTAL do not — none of it is kitchen data.
    expect(within(ticket).getByText(/Cliente Test/)).toBeTruthy()
    expect(within(ticket).queryByText(/1155551234/)).toBeNull()
    expect(within(ticket).queryByText(/Calle Falsa 123/)).toBeNull()
    expect(within(ticket).queryByText(/Efectivo/)).toBeNull()
    expect(within(ticket).queryByText(/TOTAL/i)).toBeNull()
    expect(within(ticket).getByText(/Tocar timbre/)).toBeTruthy()
  })

  it('shows the table name for salon orders and hides empty customer data', () => {
    const salon = managementOrder({
      orderNumber: 202,
      orderType: 'SALON',
      customerName: '',
      customerPhone: null,
      customerAddress: null,
      paymentMethod: null,
      notes: null,
      table: { id: 3, name: 'Mesa 5', number: 5 },
    })

    render(<Comanda order={salon} />)

    const ticket = screen.getByTestId('comanda')
    expect(within(ticket).getByText(/Mesa 5/)).toBeTruthy()
    expect(within(ticket).getByText(/#202/)).toBeTruthy()
    expect(within(ticket).queryByText(/Efectivo/)).toBeNull()
  })
})

describe('orders panel — manual comanda printing', () => {
  it('the Imprimir button on a card prints that order comanda', async () => {
    vi.stubGlobal('fetch', stubOrdersFetch([FULL_ORDER]))
    const print = printSpy()
    const user = userEvent.setup()

    render(<Orders />)

    const button = await screen.findByRole('button', { name: /Imprimir/ })
    await user.click(button)

    await waitFor(() => expect(print).toHaveBeenCalled())
    // The printed ticket is the one of the clicked card.
    const ticket = screen.getByTestId('comanda')
    expect(within(ticket).getByText(/#101/)).toBeTruthy()
  })
})

describe('orders panel — automatic comanda printing', () => {
  it('a brand-new NEW order prints once; the first snapshot stays silent', async () => {
    let printed = 0
    const print = printSpy()
    print.mockImplementation(() => {
      printed += 1
    })

    const fetchMock = stubOrdersFetch([])
    vi.stubGlobal('fetch', fetchMock)
    render(<Orders />)

    await screen.findByText(/No hay pedidos/)
    expect(printed).toBe(0)

    // Snapshot 2: a new NEW order arrives through the WS broadcast → one comanda.
    fetchMock.mockImplementation(stubOrdersFetch([FULL_ORDER]) as typeof fetch)
    act(() => {
      emitOrdersEvent()
    })
    await waitFor(() => expect(printed).toBe(1), { timeout: 3000 })

    // Another broadcast with the same order (no news) prints nothing more.
    act(() => {
      emitOrdersEvent()
    })
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700))
    })
    expect(printed).toBe(1)
  })

  it('an unconfirmed transferencia (PENDING_PAYMENT) does not auto-print', async () => {
    let printed = 0
    const print = printSpy()
    print.mockImplementation(() => {
      printed += 1
    })

    const fetchMock = stubOrdersFetch([])
    vi.stubGlobal('fetch', fetchMock)
    render(<Orders />)

    await screen.findByText(/No hay pedidos/)

    // Positive control: a NEW order through the WS broadcast prints once —
    // this proves the print pipeline is live inside this test.
    fetchMock.mockImplementation(stubOrdersFetch([FULL_ORDER]) as typeof fetch)
    act(() => {
      emitOrdersEvent()
    })
    await waitFor(() => expect(printed).toBe(1), { timeout: 3000 })

    // A new TRANSFERENCIA order arrives as PENDING_PAYMENT — the payment
    // was not confirmed yet, so no comanda prints. The ding still plays
    // (same feed, different sink); printing waits for confirmation
    // (R3-print-unconfirmed).
    fetchMock.mockImplementation(
      stubOrdersFetch([
        FULL_ORDER,
        managementOrder({
          orderId: 21,
          orderNumber: 121,
          status: 'PENDING_PAYMENT',
          paymentMethod: 'TRANSFERENCIA',
        }),
      ]) as typeof fetch
    )
    act(() => {
      emitOrdersEvent()
    })
    await screen.findByText('#121')
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700))
    })
    expect(printed).toBe(1)
  })

  it('a transferencia whose payment gets confirmed (PENDING_PAYMENT → NEW) prints once', async () => {
    let printed = 0
    const print = printSpy()
    print.mockImplementation(() => {
      printed += 1
    })

    const pending = managementOrder({
      orderId: 31,
      orderNumber: 131,
      status: 'PENDING_PAYMENT',
      paymentMethod: 'TRANSFERENCIA',
    })
    const fetchMock = stubOrdersFetch([pending])
    vi.stubGlobal('fetch', fetchMock)
    render(<Orders />)

    // Snapshot 1: the unconfirmed transferencia arrives — no comanda.
    await screen.findByText('#131')
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700))
    })
    expect(printed).toBe(0)

    // Snapshot 2: the payment is confirmed (console button or POS chain) —
    // the kitchen can cook, so the comanda prints exactly once.
    fetchMock.mockImplementation(
      stubOrdersFetch([{ ...pending, status: 'NEW' }]) as typeof fetch
    )
    act(() => {
      emitOrdersEvent()
    })
    await waitFor(() => expect(printed).toBe(1), { timeout: 3000 })

    // Further broadcasts with the same confirmed order print nothing more.
    act(() => {
      emitOrdersEvent()
    })
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700))
    })
    expect(printed).toBe(1)
  })

  it('a POS-created order born READY does not print', async () => {
    let printed = 0
    const print = printSpy()
    print.mockImplementation(() => {
      printed += 1
    })

    const fetchMock = stubOrdersFetch([])
    vi.stubGlobal('fetch', fetchMock)
    render(<Orders />)

    await screen.findByText(/No hay pedidos/)

    fetchMock.mockImplementation(
      stubOrdersFetch([managementOrder({ orderId: 9, orderNumber: 109, status: 'READY' })]) as typeof fetch
    )
    act(() => {
      emitOrdersEvent()
    })
    await act(async () => {
      // Covers the debounced refresh and the print timer: nothing fires.
      await new Promise((resolve) => setTimeout(resolve, 700))
    })
    expect(printed).toBe(0)
  })

  it('two orders arriving together print two comandas (queue drains)', async () => {
    let printed = 0
    const print = printSpy()
    print.mockImplementation(() => {
      printed += 1
    })

    const fetchMock = stubOrdersFetch([])
    vi.stubGlobal('fetch', fetchMock)
    render(<Orders />)

    await screen.findByText(/No hay pedidos/)

    fetchMock.mockImplementation(
      stubOrdersFetch([
        managementOrder({ orderId: 11, orderNumber: 111, status: 'NEW' }),
        managementOrder({ orderId: 12, orderNumber: 112, status: 'NEW' }),
      ]) as typeof fetch
    )
    act(() => {
      emitOrdersEvent()
    })
    await waitFor(() => expect(printed).toBe(2), { timeout: 3000 })
  })
})

describe('orders panel — comanda settings gate the printing', () => {
  it('master OFF: no Imprimir button and no auto-print', async () => {
    let printed = 0
    const print = printSpy()
    print.mockImplementation(() => {
      printed += 1
    })

    const fetchMock = stubOrdersFetch([], { profile: { comandaPrintingEnabled: false, comandaAutoPrint: false } })
    vi.stubGlobal('fetch', fetchMock)
    render(<Orders />)

    // The card renders (with the order below) but the print button is gone.
    fetchMock.mockImplementation(
      stubOrdersFetch([FULL_ORDER], { profile: { comandaPrintingEnabled: false, comandaAutoPrint: false } }) as typeof fetch
    )
    act(() => {
      emitOrdersEvent()
    })
    await screen.findByText('#101')
    expect(screen.queryByRole('button', { name: /Imprimir/ })).toBeNull()

    // A brand-new NEW order does not print either.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700))
    })
    expect(printed).toBe(0)
  })

  it('master ON with auto OFF: the button exists but new orders do not print alone', async () => {
    let printed = 0
    const print = printSpy()
    print.mockImplementation(() => {
      printed += 1
    })

    const fetchMock = stubOrdersFetch([], { profile: { comandaPrintingEnabled: true, comandaAutoPrint: false } })
    vi.stubGlobal('fetch', fetchMock)
    render(<Orders />)

    fetchMock.mockImplementation(
      stubOrdersFetch([FULL_ORDER], { profile: { comandaPrintingEnabled: true, comandaAutoPrint: false } }) as typeof fetch
    )
    act(() => {
      emitOrdersEvent()
    })
    await screen.findByText('#101')
    expect(screen.getByRole('button', { name: /Imprimir/ })).toBeTruthy()

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 700))
    })
    expect(printed).toBe(0)
  })
})

describe('orders feed — onNewOrder callback', () => {
  const mountAudio = (): void => {
    document.body.insertAdjacentHTML(
      'beforeend',
      '<audio id="newOrderSound" src="/ding.mp3" /><audio id="readySound" src="/ready.mp3" />'
    )
  }

  afterEach(() => {
    document.getElementById('newOrderSound')?.remove()
    document.getElementById('readySound')?.remove()
  })

  it('fires for a brand-new NEW order only, with the full order', async () => {
    mountAudio()
    const onNewOrder = vi.fn()
    const fetchMock = stubOrdersFetch([])
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useOrdersFeed('demo-pizzeria', true, onNewOrder))
    await waitFor(() => expect(result.current.lastUpdated).not.toBeNull())
    expect(onNewOrder).not.toHaveBeenCalled()

    fetchMock.mockImplementation(
      stubOrdersFetch([managementOrder({ orderId: 7, orderNumber: 7, status: 'NEW' })]) as typeof fetch
    )
    await act(async () => {
      await result.current.refresh()
    })

    expect(onNewOrder).toHaveBeenCalledTimes(1)
    expect(onNewOrder.mock.calls[0][0].orderId).toBe(7)

    // Transition to READY is not a new order.
    fetchMock.mockImplementation(
      stubOrdersFetch([managementOrder({ orderId: 7, orderNumber: 7, status: 'READY' })]) as typeof fetch
    )
    await act(async () => {
      await result.current.refresh()
    })
    expect(onNewOrder).toHaveBeenCalledTimes(1)
  })
})

/**
 * Double ticket jobs (delivery-ticket-copy S2): the auto-print and the
 * manual Imprimir print the kitchen comanda PLUS the delivery copy when the
 * owner enabled `comanda_delivery_copy` — one window.print() call, two
 * page-broken tickets. The manual "Entrega" button prints ONLY the delivery
 * copy (reprint when the original got lost or at dispatch).
 */
describe('orders panel — double ticket (kitchen + delivery copy)', () => {
  async function autoPrintWithProfile(profile: Record<string, unknown>): Promise<number> {
    let printed = 0
    const print = printSpy()
    print.mockImplementation(() => {
      printed += 1
    })

    // El perfil (settings) se lee UNA vez al montar: el primer stub ya debe
    // traer la configuración bajo test.
    const fetchMock = stubOrdersFetch([], { profile })
    vi.stubGlobal('fetch', fetchMock)
    render(<Orders />)

    await screen.findByText(/No hay pedidos/)

    // Snapshot 2: el pedido llega por el broadcast del WS.
    fetchMock.mockImplementation(stubOrdersFetch([FULL_ORDER], { profile }) as typeof fetch)
    act(() => {
      emitOrdersEvent()
    })
    await waitFor(() => expect(printed).toBe(1), { timeout: 3000 })
    return printed
  }

  it('auto-print with the copy ON renders BOTH tickets in one print call', async () => {
    await autoPrintWithProfile({
      comandaPrintingEnabled: true,
      comandaAutoPrint: true,
      comandaDeliveryCopy: true,
    })

    expect(screen.getByTestId('comanda')).toBeTruthy()
    expect(screen.getByTestId('delivery-ticket')).toBeTruthy()
  })

  it('auto-print with the copy OFF renders only the kitchen comanda', async () => {
    await autoPrintWithProfile({
      comandaPrintingEnabled: true,
      comandaAutoPrint: true,
      comandaDeliveryCopy: false,
    })

    expect(screen.getByTestId('comanda')).toBeTruthy()
    expect(screen.queryByTestId('delivery-ticket')).toBeNull()
  })

})
