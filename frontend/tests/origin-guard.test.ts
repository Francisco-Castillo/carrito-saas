/**
 * Origin guard, ported from api/src/test/js/frontend-origin.test.mjs.
 *
 * The legacy guard scanned the shipped static files for a hardcoded
 * application origin; this port scans the new React sources instead.
 *
 * Why (same rationale as the legacy guard): the application's origin (scheme,
 * host, port) is deployment data, and duplicating it in a client makes the
 * client lie about where the backend lives. The server port has already
 * flipped twice — 9090 -> 8080 -> 9090 — and an absolute base is the wrong
 * shape, not just the wrong value: it breaks the LAN-IP pilot
 * (http://192.168.1.5:9090/...) and the real public origin. API calls must
 * stay relative ("/api/...").
 *
 * Scope:
 *   - Every .ts/.tsx/.js/.jsx/.html file under frontend/src/ is scanned. This
 *     directory is exactly what the production build ships (vite bundles only
 *     the index.html -> src/main.tsx entry graph).
 *   - vite.config.ts (and any other build/config file outside src/) is
 *     deliberately NOT scanned: it legitimately contains the dev-server proxy
 *     target http://localhost:9090, but it is Node build tooling that is
 *     never shipped to the browser.
 *   - Test files under frontend/tests/ are not shipped client code either;
 *     they may name the forbidden patterns to assert this guard's behavior.
 *   - Third-party origins (the Unsplash combo fallback image, wa.me deep
 *     links, w3.org SVG namespaces, font CDNs) are not the application's own
 *     origin and stay allowed, as in the legacy guard.
 */
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, extname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// NOTE: resolved via path helpers — a two-argument `new URL(x, import.meta.url)`
// here would be rewritten by Vite's asset-URL transform and break at runtime.
const TEST_DIR = dirname(fileURLToPath(import.meta.url))
const SRC_ROOT = resolve(TEST_DIR, '../src')
const FRONTEND_ROOT = resolve(TEST_DIR, '..')

const SCANNED_EXTENSIONS = ['.ts', '.tsx', '.js', '.jsx', '.html', '.htm']

/** The application's own origin, hard-coded: the deployment lie. */
const APPLICATION_ORIGIN = /localhost|127\.0\.0\.1/i

/** An absolute http(s) URL used as an API base (any absolute URL whose path
 * carries /api, or an absolute URL passed straight to fetch()). */
const ABSOLUTE_API_BASE = /["'`]https?:\/\/[^\s"'`]*\/api/i
const ABSOLUTE_FETCH = /fetch\(\s*[`"']https?:\/\//i

/** First-argument literals of fetch() calls (template or plain string). */
const FETCH_LITERAL = /fetch\(\s*(["'`])([^"'`]*)\1/

function collectFiles(dir: string): string[] {
  const files: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true }).sort((a, b) =>
    a.name.localeCompare(b.name)
  )) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) {
      files.push(...collectFiles(path))
    } else if (entry.isFile() && SCANNED_EXTENSIONS.includes(extname(entry.name).toLowerCase())) {
      files.push(path)
    }
  }
  return files
}

function offenseFor(line: string): string | null {
  if (APPLICATION_ORIGIN.test(line)) {
    return 'hardcoded application origin (localhost / 127.0.0.1)'
  }
  if (ABSOLUTE_API_BASE.test(line) || ABSOLUTE_FETCH.test(line)) {
    return 'absolute http(s) API base — API calls must stay relative ("/api/...")'
  }
  return null
}

describe('frontend origin guard (port of api/src/test/js/frontend-origin.test.mjs)', () => {
  const files = collectFiles(SRC_ROOT)

  it('source scan found files to guard (never passes silently on an empty scan)', () => {
    expect(
      files.length,
      `no scannable source files found under ${relative(FRONTEND_ROOT, SRC_ROOT)}; the guard must never pass silently on an empty scan`
    ).toBeGreaterThan(0)
  })

  it('no file under src/ hardcodes the application origin or an absolute API base', () => {
    const problems: string[] = []
    for (const file of files) {
      readFileSync(file, 'utf8')
        .split(/\r?\n/)
        .forEach((line, index) => {
          const offense = offenseFor(line)
          if (offense) {
            problems.push(
              `${relative(FRONTEND_ROOT, file)}:${index + 1}: ${offense} — ${line.trim()}`
            )
          }
        })
    }
    expect(problems).toEqual([])
  })

  it('every fetch() string-literal argument in src/ is relative (never an absolute URL)', () => {
    const problems: string[] = []
    for (const file of files) {
      readFileSync(file, 'utf8')
        .split(/\r?\n/)
        .forEach((line, index) => {
          const match = line.match(FETCH_LITERAL)
          if (match && /^https?:\/\//i.test(match[2])) {
            problems.push(
              `${relative(FRONTEND_ROOT, file)}:${index + 1}: fetch URL literal must be relative — ${line.trim()}`
            )
          }
        })
    }
    expect(problems).toEqual([])
  })
})
