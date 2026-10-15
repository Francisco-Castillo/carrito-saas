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
in state reviewing. Captured and admitted: review-risk, review-readability,
review-reliability. review-resilience failed 3x deterministically at the pi
host relay (reviewer-empty-output, stopReason: length) — transport
limitation, not a candidate defect. Per user decision (2026-10-15) the
lineage stays open and the lens will be retried later: resume with bound
facade STATUS for this lineageId in the worktree
/home/rodrigo/work/carrito-saas-organic. No acknowledgement was burned.

## Notes

- Base branch: develop. Feature branch: feat/organic-login.
- Public menu URL contract (`/?restaurant=`) is explicitly NOT changed.
- Future work: multi-business selector in the login (backend already
  returns a clear error when a user has several memberships).
