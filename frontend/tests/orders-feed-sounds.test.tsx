/**
 * Orders console sound rules: the management feed must reuse the KDS audio —
 * the new-order ding only for a brand-new order entering as NEW, and the
 * ready chime only when an existing order TRANSITIONS to READY. The first
 * snapshot of the day must stay silent (opening the console with a full day
 * of orders must not blast a ding for every row), and a new order born READY
 * (POS-created) must not ding either.
 */
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useOrdersFeed } from '../src/pages/orders/useOrdersFeed'
import { managementOrder } from './helpers/ordersFixtures'

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    activate(): void {}
    deactivate(): void {}
    subscribe(): void {}
    publish(): void {}
  },
}))

function stubOrdersFetch(orders: unknown[]) {
  return vi.fn(async (input: RequestInfo | URL) => {
    if (String(input).startsWith('/api/business/orders')) {
      return { ok: true, status: 200, json: async () => orders }
    }
    return { ok: false, status: 500, json: async () => ({}) }
  })
}

/** The <audio> pair Orders.tsx renders; injected here so the hook finds them. */
function mountAudioElements(): { ding: HTMLAudioElement; ready: HTMLAudioElement } {
  document.body.insertAdjacentHTML(
    'beforeend',
    '<audio id="newOrderSound" src="/ding.mp3" /><audio id="readySound" src="/ready.mp3" />'
  )
  const ding = document.getElementById('newOrderSound') as HTMLAudioElement
  const ready = document.getElementById('readySound') as HTMLAudioElement
  return { ding, ready }
}

function stubPlay(el: HTMLAudioElement): ReturnType<typeof vi.fn> {
  return vi.spyOn(el, 'play').mockResolvedValue(undefined)
}

function audioPair(): { ding: HTMLAudioElement; ready: HTMLAudioElement } {
  const ding = document.getElementById('newOrderSound') as HTMLAudioElement
  const ready = document.getElementById('readySound') as HTMLAudioElement
  return { ding, ready }
}

let dingPlay: ReturnType<typeof vi.fn>
let readyPlay: ReturnType<typeof vi.fn>
let fetchMock: ReturnType<typeof stubOrdersFetch>

beforeEach(() => {
  const { ding, ready } = mountAudioElements()
  dingPlay = stubPlay(ding)
  readyPlay = stubPlay(ready)
  fetchMock = stubOrdersFetch([])
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  document.getElementById('newOrderSound')?.remove()
  document.getElementById('readySound')?.remove()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('orders feed sounds', () => {
  it('the first snapshot of the day stays silent', async () => {
    fetchMock.mockImplementation(
      stubOrdersFetch([managementOrder({ orderId: 7, status: 'NEW' })]) as typeof fetch
    )
    const { result } = renderHook(() => useOrdersFeed('demo-pizzeria', true))

    await waitFor(() => expect(result.current.orders).toHaveLength(1))

    expect(dingPlay).not.toHaveBeenCalled()
    expect(readyPlay).not.toHaveBeenCalled()
  })

  it('a brand-new NEW order dings; a transition to READY chimes', async () => {
    const { result } = renderHook(() => useOrdersFeed('demo-pizzeria', true))
    await waitFor(() => expect(result.current.lastUpdated).not.toBeNull())
    const { ding, ready } = audioPair()
    const dingAfterSetup = vi.spyOn(ding, 'play').mockResolvedValue(undefined)
    const readyAfterSetup = vi.spyOn(ready, 'play').mockResolvedValue(undefined)

    // Snapshot 2: a new NEW order arrives → ding.
    fetchMock.mockImplementation(
      stubOrdersFetch([managementOrder({ orderId: 7, orderNumber: 7, status: 'NEW' })]) as typeof fetch
    )
    await act(async () => {
      await result.current.refresh()
    })
    expect(result.current.orders).toHaveLength(1)
    expect(dingAfterSetup).toHaveBeenCalledTimes(1)
    expect(readyAfterSetup).not.toHaveBeenCalled()

    // Snapshot 3: the kitchen marks it READY → ready chime.
    fetchMock.mockImplementation(
      stubOrdersFetch([managementOrder({ orderId: 7, orderNumber: 7, status: 'READY' })]) as typeof fetch
    )
    await act(async () => {
      await result.current.refresh()
    })
    expect(readyAfterSetup).toHaveBeenCalledTimes(1)

    // Snapshot 4: still READY on the next refresh → no repeat.
    await act(async () => {
      await result.current.refresh()
    })
    expect(readyAfterSetup).toHaveBeenCalledTimes(1)
  })

  it('a new order born READY (POS-created) does not ding', async () => {
    const { result } = renderHook(() => useOrdersFeed('demo-pizzeria', true))
    await waitFor(() => expect(result.current.lastUpdated).not.toBeNull())

    fetchMock.mockImplementation(
      stubOrdersFetch([managementOrder({ orderId: 9, orderNumber: 9, status: 'READY' })]) as typeof fetch
    )
    await act(async () => {
      await result.current.refresh()
    })

    const { ding, ready } = audioPair()
    expect(ding.getAttribute('id')).toBe('newOrderSound')
    expect(dingPlay).not.toHaveBeenCalled()
    expect(readyPlay).not.toHaveBeenCalled()
  })
})
