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
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const REPO_ROOT = join(__dirname, '../..')
const STATIC_DIR_REL = 'api/src/main/resources/static'
const STATIC_DIR = join(REPO_ROOT, STATIC_DIR_REL)

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

/**
 * Every entry `build:spring` writes, plus the hashed bundle and public assets.
 * `favicon.ico` is deliberately absent: it is checked-in source, not output.
 */
const GENERATED_PATHS = [
  'index.html',
  'menu/index.html',
  'login/login.html',
  'dashboard/index.html',
  'kds/index.html',
  'admin/admin.html',
  'pedidos/index.html',
  'platform/index.html',
  'assets/',
  'favicon.svg',
  'ding.mp3',
  'ready.mp3',
  'dev-media/',
]

describe('generated static output is not versioned', () => {
  // The bundle hash changes with the sources AND with the locked dependency
  // tree, so a versioned copy is stale from the moment it is committed: the
  // entry keeps pointing at assets that are not in the repo, and every local
  // `build:spring` dirties the worktree. Versioning six of the eight entries
  // (this is how `pedidos/` and `platform/` slipped in) reads as intent and
  // hides the mistake, so the guard pins the whole set.
  it('every generated artifact is gitignored', () => {
    for (const path of GENERATED_PATHS) {
      expect(
        () => execFileSync('git', ['check-ignore', '-q', join(STATIC_DIR, path)], { cwd: REPO_ROOT }),
        `${path} is build output and must be gitignored`,
      ).not.toThrow()
    }
  })

  it('no generated artifact is tracked', () => {
    // `git ls-files` prints paths relative to the cwd (the repo root), never
    // absolute, so the prefix to strip is STATIC_DIR_REL. Slicing the absolute
    // STATIC_DIR here compares against "" and makes this assertion pass
    // whatever is versioned.
    const tracked = execFileSync('git', ['ls-files', '--', STATIC_DIR_REL], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
    })
      .split('\n')
      .filter(Boolean)
      .map((file) => file.slice(STATIC_DIR_REL.length + 1))

    const offenders = tracked.filter((file) =>
      GENERATED_PATHS.some((path) => file === path || file.startsWith(path)),
    )
    expect(offenders, 'generated build output must not be versioned').toEqual([])
  })
})
