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

## Review status (RDD native review)

Lineage review-9e7ca42128ada755 (high tier, 13 files, base 6675323) is OPEN
in state `correction_required` — revision `sha256:0ca880899743be10e0bd77725b1b97269852f3abdcf468b6730d76d9a68c1cd6`,
snapshot `sha256:4651fe88…`, correction budget 200 logical / plan declared at
120 diff lines. Nothing burned; the lock is released.

Captured and admitted: review-risk, review-readability, review-reliability,
review-resilience (the last one only after switching that lens' reviewer model
from `nan/mimo-v2.6-flash` to `nan/glm5.3-flash` in
`~/.gentle-shell/agent/subagents.json`; the old model returned empty output
with `stopReason: length` on 4 attempts, ~607s each — backup at
`subagents.json.bak-20261015153351`).

### Open finding to correct

`R4-session-slug-silent-empty-panels` (resilience, CRITICAL, deterministic,
behavior-activated): an authenticated session without the session
`restaurantSlug` key (e.g. every session created before this change, whose
slug came from the URL query this candidate removed) builds its restaurant
API base with no slug, so dashboard/KDS render empty data silently — no
redirect, retry, or visible error.

Planned fix (already designed, not yet applied): make `hasUsableSession()` in
`frontend/src/pages/dashboard/auth.ts` require
`getSessionRestaurantSlug() !== null`, so a slug-less session is not usable
and the existing guard redirects to the bare login (where the login writes the
slug). All three panels gate on that function, so no call-site change is
needed; `frontend/tests/session-guard.test.ts` and the panel tests that seed a
token must seed `restaurantSlug` too.

### Blocked, then unblocked (Pi/tooling skew)

`gentle-ai review capture-correction-plan` refused with
`managed_assets_outdated`: Pi 4.0.0 mirrored provider bundle 1.2.0 while CLI
4.1.1 requires 1.3.0. The Pi facade also cannot submit the plan at all in
4.0.0 — `extensions/gentle-ai.ts:7358` compares the plan binding's `--target`
(authority target `4651fe88…`) against `status.targetIdentity` (the newer
snapshot identity `8019eedd…`); the escape hatch exists only for the targeted
validator.

Resolved by upgrading gentle-pi 4.0.0 -> 4.2.0 (mirrors bundle 1.3.0; the
distribution's installer pins CLI 4.1.1, already installed). An auto-saved
defect report for the unknown-outcome capture is at
`.git/gentle-ai/defect-reports/operation-outcome-unknown-502550653d6b.md`.

### Resume steps (next session, after restarting Pi so 4.2.0 is loaded)

1. Bound facade STATUS for lineage review-9e7ca42128ada755 in this worktree.
2. Capture the provider-issued correction plan with `correctionLines: 120`
   (or the CLI `gentle-ai review capture-correction-plan` with the exact
   provider tokens if the facade still rejects the slot).
3. Apply the planned fix above plus its test updates.
4. Follow the provider-issued validator and final verification to `approved`,
   then the exact `review.acknowledge-approved` continuation.

### Integration ordering (do not rebase while the lineage is open)

`develop` is ahead of this branch and already changed two manifest files:
`frontend/src/pages/Admin.tsx` (+163, the admin shell) and
`frontend/tests/admin-panel.test.tsx` (+71). Merging develop into
feat/organic-login changes the candidate content, which invalidates the open
frozen lineage — so integrate develop only AFTER the review closes, not before.

Agreed conflict resolutions with the sibling session (admin units):
`admin-panel.test.tsx` keeps OUR point 1 (bare `/login/login.html`, no
`?restaurant=`) plus THEIR points 3 and 4 (sidebar brand from
`GET /api/business/profile`; view title is the Spanish label) — never a plain
"take mine" or "take theirs"; `Admin.tsx` keeps THEIR shell (brand fetch, SVG
icons, VIEW_LABELS, separator, logout icon on the button) with OUR logout block
(bare redirect, button keeps its icon).

## Notes

- Base branch: develop. Feature branch: feat/organic-login.
- Public menu URL contract (`/?restaurant=`) is explicitly NOT changed.
- Future work: multi-business selector in the login (backend already
  returns a clear error when a user has several memberships).
