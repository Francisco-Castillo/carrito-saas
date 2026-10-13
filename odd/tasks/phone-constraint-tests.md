# Feature: phone-constraint-tests

## Goal

Pin the `businesses.phone` schema constraints with tests: the CHECK on the stored shape and
the UNIQUE that enforces "each location has its own number". This closes the advisory
finding `R3-untested-schema-constraints` raised by the native review of the WhatsApp revert
(PR #2) and the third follow-up recorded in `odd/tasks/whatsapp-revert.md`.

## Why this is worth doing

Verified by exploration: **no test in the repository ever sets a phone.** `grep -rn
"setPhone" api/src/test service/src/test` returns nothing, and the only production touch is
`BusinessMapperImpl` mapping `whatsappNumber` to `phone`. The constraints live only in the
entity annotations (`@Table(uniqueConstraints = uq_businesses_phone)` and
`@Column(check = @CheckConstraint("businesses_phone_shape" ...))`), there are no migrations,
and the schema comes from `ddl-auto`. So both constraints can vanish without a single test
failing: nothing pins them.

## Scope

- One new test class in `api/src/test/java/com/carrito/saas/`, mirroring the existing
  `BusinessSlugIntegrityTests` (same manual-id style, same root-cause `SQLException` +
  SQLState assertions, same `@SpringBootTest @Transactional`).
- Properties to pin:
  1. UNIQUE on `phone`: a second business reusing a live canonical phone is rejected by the
     database with PostgreSQL `unique_violation` (SQLState 23505).
  2. CHECK on the stored shape: a non-canonical value — the decorated local form
     `011 2233-4455`, exactly the string the `Business.phone` javadoc says must not be
     stored — is rejected with `check_violation` (SQLState 23514).
  3. NULL distinctness: two businesses without a phone both persist, i.e. the UNIQUE does
     not turn a nullable column into an effective NOT NULL.
  4. Positive control: a canonical phone (`5491122334455`) is accepted and read back
     unchanged, so the test cannot pass vacuously.

## Non-goals

- Not changing the constraints themselves or the `phone` column: they stay as they are.
- Not introducing migrations or a schema tool.
- Not touching the WhatsApp revert, which is already merged in `develop` (PR #2).

## Constraints

- No new dependencies.
- Local build is impossible on this host (no JDK, no Maven), so verification is the
  repository's CI, dispatched per branch with `gh workflow run CI --ref <branch>`.
- **Sensitivity must be observed, not argued**: the project rule is that every layer has a
  test that fails when the layer is removed, so the test must be proven to fail on a branch
  where each constraint is dropped (mutation runs), mirroring how the earlier suite proved
  its sensitivity.

## Tasks

| id | task | status |
|----|------|--------|
| T1 | Pin the contract and its evidence (exploration) | done |
| T2 | Write `BusinessPhoneIntegrityTests` | pending |
| T3 | CI green on the feature branch | pending |
| T4 | Mutation runs: dropping each constraint must fail its test | pending |
| T5 | Report, then decide about a PR | pending |

## Evidence

### T1 — exploration

- `grep -rn "setPhone" api/src/test service/src/test` → no matches: nothing exercises the
  column today.
- Only production reference: `BusinessMapperImpl.setPhone(dto.getWhatsappNumber())` and its
  reverse.
- No SQL, Flyway or Liquibase artifacts in the repository: the schema is `ddl-auto` output.
- Constraints: `Business.java:36-37` (named UNIQUE on `slug` and `phone`) and
  `Business.java:113-114` (`businesses_phone_shape` CHECK).
- Reference pattern: `api/src/test/java/com/carrito/saas/BusinessSlugIntegrityTests.java`
  (106 lines) pins the analogous `slug` contract, and its log evidence in CI shows
  `duplicate key value violates unique constraint "uq_businesses_slug"`, proving the named
  UNIQUE constraints do exist in the CI database.
