# Feature: Organic Login (slug out of the URL)

## Goal

Remove the `?restaurant=<slug>` dependency from the authenticated flow.
Login asks only for username + password; the backend resolves the business
from the user's membership. Panels obtain the slug from the session, not
the URL. The public storefront menu (`/?restaurant=<slug>`, QR links) is
out of scope and keeps its URL contract.

## Decisions (user-confirmed)

- Users are assumed to belong to exactly one business. If a user has zero
  memberships -> 403. If more than one -> clear backend error (no selector
  in this iteration; a selector is future work).
- Slug sent explicitly in the login request stays supported (backward
  compatible, useful for the multi-business future).
- Scope: full removal, phased. Phase 1 = backend + login page.
  Phase 2 = panels read slug from session.
- Work executed in dedicated worktree /home/rodrigo/work/carrito-saas-organic
  (the main worktree had a concurrent session on feat/admin-shell).

## Tasks

- [x] 1. Backend: membership-based login without required slug
      Commit: a5609e9 feat(auth): resolve the login business from user membership
      (RED 21/4 failures observed, GREEN 22/0, full reactor 228/0 OK; tests run
      against disposable DB carrito_ci — carrito_db has a pre-existing seed
      collision with uq_roles_name unrelated to this change.)
- [x] 2. Frontend Phase 1: login without URL slug
      Commit: 5676490 feat(login): allow organic login without a URL restaurant slug
      (RED 4/1 -> GREEN 5/0 in login.test.tsx; full vitest suite 139/0;
      `tsc -b` exit 0. NOTE: the commit briefly landed on feat/admin-shell —
      a concurrent session checked that branch out in the shared worktree —
      and was cherry-picked back to feat/organic-login, restoring
      feat/admin-shell to 6675323.)
- [x] 3. Frontend Phase 2: panels read slug from session
      Commit: 113d6ed feat(panels): resolve the restaurant slug from the session, not the URL
      (RED 14/11 -> GREEN 25/25 focused; full suite 147/147; `tsc -b` 0;
      admin-panel.test.tsx pinned logout contract updated per parent approval.)
- [x] 4. Final verification: frontend vitest suite + backend affected module tests.
      Backend: full reactor 228/0 at a5609e9 (worker evidence, carrito_ci DB).
      Frontend: full suite 147/147 + `tsc -b` 0 at the Phase 2 tree (identical
      bytes to 113d6ed; worker evidence). Storefront/menu contracts unmodified
      and green.

## Review status (RDD native review) — CLOSED

Lineage review-9e7ca42128ada755 (high tier, 13 files, base 6675323) is
**approved and acknowledged**; authority burned
(`gentle-ai.review-acknowledged/v1`, consumed revision
`sha256:693f700fa4c9b07456d6fb871f8bd4312d7438c6a6887593b2530a846ccd2676`,
target `sha256:a8db0324…`). An approval carries no delivery authority: commit,
push, PR, and release stay under ordinary repository policy.

Admitted lenses: review-risk (0 findings), review-resilience (1 CRITICAL),
review-readability, review-reliability.

### The correction

`R4-session-slug-silent-empty-panels` (resilience, CRITICAL, deterministic,
behavior-activated): an authenticated session without the session
`restaurantSlug` key (every session created before this change, whose slug
came from the URL query this candidate removed, or a link carrying only that
query) built its restaurant API base with no slug, so dashboard and KDS kept
sending restaurant-less requests while the token passed the session guard —
empty panels with no redirect, retry, or visible error.

Plan declared 120 diff lines (provider budget 200); applied in commit
**2d7496e** with **53** diff lines:

- `frontend/src/pages/dashboard/auth.ts`: new
  `sessionRestaurantSlugOrRedirect()` — resolves the session slug and, when
  there is none, routes through the existing `enforceSession()` guard to the
  bare `/login/login.html`; never throws.
- `frontend/src/pages/dashboard/api.ts` and `frontend/src/pages/kds/api.ts`:
  the slug comes from that helper, so the old `console.error("Restaurant slug
  missing in session")` branch is gone and a slug-less session is redirected.
- `frontend/tests/dashboard-api-auth.test.ts`: the missing-slug contract now
  asserts the bare-login redirect; the header and slug-target contracts stay.

`hasUsableSession()` was deliberately NOT changed. Requiring the slug there
would also redirect Admin sessions and break tests outside the frozen review
scope (`admin-business-profile.test.tsx` and `admin-products-combos.test.tsx`
render `<Admin />` with a token and no slug), which the provider forbids as
scope expansion.

Targeted validation (provider role, host-relay): `original_criteria.passed =
true`, `correction_regression.passed = true`, `regressions: []`.

### Tooling skew found and fixed (for the record)

The correction route was blocked twice by tooling, not by the candidate:

1. `review-resilience` produced empty output on 4 attempts (`stopReason:
   length`, ~607 s each) because its reviewer model was
   `nan/mimo-v2.6-flash`. Switching that lens to `nan/glm5.3-flash` in
   `~/.gentle-shell/agent/subagents.json` (backup
   `subagents.json.bak-20261015153351`) produced the CRITICAL finding
   immediately.
2. `capture-correction-plan` refused with `managed_assets_outdated`: Pi 4.0.0
   mirrored provider bundle 1.2.0 while CLI 4.1.1 requires 1.3.0. Pi 4.0.0's
   facade also could not submit the plan at all — `extensions/gentle-ai.ts:7358`
   compares the plan binding's `--target` (authority target) against
   `status.targetIdentity` (the newer snapshot identity), with the escape
   hatch wired only for the targeted validator. Upgraded gentle-pi to 4.2.0
   (mirrors bundle 1.3.0; its installer pins CLI 4.1.1, already installed).
3. After the Pi restart, the facade lost the in-memory lineage binding (its
   STATUS answered `unrelated`/`start`), so the correction plan and the
   validator ran through the native CLI with the exact provider-issued tokens.

An auto-saved defect report for the unknown-outcome capture sits at
`.git/gentle-ai/defect-reports/operation-outcome-unknown-502550653d6b.md`.

### Advisory findings (non-blocking, later work)

- `R2-001` readability, `frontend/src/pages/Login.tsx:227` (WARNING).
- `R2-002` readability, `api/src/test/java/com/carrito/saas/PanelErrorStatusHttpTests.java:154` (WARNING).
- `R3-logout-does-not-clear-session-slug` reliability,
  `frontend/src/pages/Login.tsx:181-189` (WARNING): logout leaves
  `restaurantSlug` in localStorage.
- `R3-null-slug-api-base` reliability,
  `frontend/src/pages/dashboard/api.ts:49` (SUGGESTION): after requesting the
  redirect the module still builds `API_BASE` with a null slug (harmless while
  the page is being replaced, but worth tightening).
- `R3-organic-login-contract-covered` reliability,
  `security/src/main/java/com/carrito/saas/security/SecurityService.java:66-93`
  (SUGGESTION).

### Integration (now unblocked)

`develop` moved ahead while the lineage was open and already changed two files
of the frozen manifest: `frontend/src/pages/Admin.tsx` (admin shell, +163) and
`frontend/tests/admin-panel.test.tsx` (+71). Integrating develop had to wait
for the review to close — it now has.

Agreed conflict resolutions with the sibling admin session:
`admin-panel.test.tsx` keeps OUR point 1 (bare `/login/login.html`, no
`?restaurant=`) plus THEIR points 3 and 4 (sidebar brand from
`GET /api/business/profile`; view title is the Spanish label) — never a plain
"take mine" or "take theirs"; `Admin.tsx` keeps THEIR shell (brand fetch, SVG
icons, VIEW_LABELS, separator, logout icon on the button) with OUR logout block
(bare redirect, button keeps its icon).

### Post-rebase state and verification

Integrated `develop` (abdbf27, which carries the sibling session's PRs #38..#41)
by REBASING this branch, so develop can fast-forward to it. Nine commits on top
of abdbf27, head `7b75617`. The rebase rewrote ids; the map is:

| Before (reviewed) | After (rebased) |
| --- | --- |
| a5609e9 feat(auth) | 3ef9c3f |
| 5676490 feat(login) | 4d93d85 |
| 113d6ed feat(panels) | 7316d12 |
| 35d5270 docs(odd) track | c771e2f |
| 041898b docs(odd) lineage | 1351689 |
| b6f4a9f docs(odd) skew | 1207630 |
| 67cd066 docs(odd) ordering | d7ec8d0 |
| 2d7496e fix(panels) | 7e6ce06 |
| 93d7556 docs(odd) reconcile | 7b75617 |

Conflicts were resolved exactly as agreed with the sibling session (see
"Integration" above): `Admin.tsx` kept their shell with our bare logout
redirect, and `admin-panel.test.tsx` kept our point 1 plus their points 3 and 4.

Verification after integration: frontend vitest **18 files / 161 tests
passed**, `npx tsc -b` exit 0; Java reactor **230 tests, 0 failures, BUILD
SUCCESS** against the disposable `carrito_ci` base (44 s).

Caveat worth remembering: the review lineage was approved and its authority
burned BEFORE this rebase, so the reviewed candidate tree was the pre-rebase
one. The rebase changed content only in the two conflict-resolved files (plus
the documentation commits); the reviewed code of commits 1-4 is otherwise
identical. No new review is owed for a mechanical rebase, but a fresh review
would be the honest call if the resolutions are ever revised.

## Chained follow-up slice: feat/login-ui-cleanup

Child branch of `feat/organic-login` (PR #42), which stays untouched and green.
Scope of this slice — the two advisory findings that touch the login:

- Commits: 60630c6 refactor(login) drops the restaurant chip and its slug-name
  fetch/cache (`R2-001`, the nested ternary at `Login.tsx:227` disappears with
  it); 765e416 fix(session) clears `restaurantSlug` on every logout path
  (`auth.logout()`, `kds.logoutKds()`, the admin logout loop) per
  `R3-logout-does-not-clear-session-slug`.
- Verified: frontend vitest 18 files / 163 tests passed, `npx tsc -b` exit 0
  (RED evidence per unit in the worker run: chip test, then the three
  teardown paths).
- Unchanged by design: the URL slug read, the conditional `restaurantSlug` in
  the POST body, the session write/clear of the response slug, remember-me,
  the role-based redirect, and the bare logout redirect.

Out of scope for this slice: the multi-business selector, `R3-null-slug-api-base`
(`dashboard/api.ts` still builds its base before the redirect fires) and
`R2-002` (`PanelErrorStatusHttpTests.java:154`, backend).

## Notes

- Base branch: develop. Feature branch: feat/organic-login (unpushed).
- Public menu URL contract (`/?restaurant=`) is explicitly NOT changed.
- Future work: multi-business selector in the login (backend already
  returns a clear error when a user has several memberships).
