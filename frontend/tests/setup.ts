/**
 * Vitest setup: registers the jest-dom matchers on expect() and resets the
 * jsdom sandbox between tests so ported contract tests stay independent.
 */
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  window.localStorage.clear()
})
