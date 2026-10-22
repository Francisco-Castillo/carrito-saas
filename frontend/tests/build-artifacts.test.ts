/**
 * Build artifact contract (regression guard for the served bundle): every
 * legacy entry the SPA forwards to must reference the SAME built bundle, and
 * that bundle must carry the CURRENT role redirect chain — not a stale one
 * (odd/tasks/simplificacion-roles.md, S2: ADMIN → /platform first; no
 * PLATFORM_ADMIN left in the served code).
 *
 * Found by the independent verification of the roles feature: a failed
 * `build:spring` left the legacy entries pointing at the previous bundle,
 * whose redirect chain still sent ADMIN to /admin — invisible to unit tests
 * because they import the source, not the artifact.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const STATIC_DIR = join(__dirname, '../../api/src/main/resources/static')

const LEGACY_ENTRIES = [
  'index.html',
  'login/login.html',
  'dashboard/index.html',
  'kds/index.html',
  'admin/admin.html',
  'pedidos/index.html',
  'platform/index.html',
]

function bundlePath(entry: string): string {
  const html = readFileSync(join(STATIC_DIR, entry), 'utf8')
  const match = html.match(/\/assets\/(index-[A-Za-z0-9_-]+\.js)/)
  expect(match, `${entry} must reference /assets/index-*.js`).toBeTruthy()
  return join(STATIC_DIR, 'assets', match![1])
}

describe('served SPA entries reference the current bundle', () => {
  it('every legacy entry points at the same built bundle', () => {
    const hashes = new Set(LEGACY_ENTRIES.map((e) => bundlePath(e)))
    expect(hashes.size, 'stale entry pointing at an old bundle').toBe(1)
  })

  it('the served bundle carries the current role redirect (no PLATFORM_ADMIN)', () => {
    const code = readFileSync(bundlePath('platform/index.html'), 'utf8')
    expect(code, 'stale bundle: PLATFORM_ADMIN must be gone').not.toContain('PLATFORM_ADMIN')
    // S2: the ADMIN rule precedes OWNER in redirectPathForRole. The bundle
    // is minified (ternary; string quoting varies: "ADMIN" or `ADMIN`).
    expect(code).toMatch(/includes\(["'`]ADMIN["'`]\)\?`\/platform`/)
  })
})
