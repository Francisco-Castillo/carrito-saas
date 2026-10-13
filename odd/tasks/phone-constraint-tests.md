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
| T2 | Write `BusinessPhoneIntegrityTests` | done |
| T3 | CI green on the feature branch | done |
| T4 | Mutation runs: dropping each constraint must fail its test | done |
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

### T2–T3 — the test and its green run (`f04998f`)

- One new class, `BusinessPhoneIntegrityTests`, four tests: duplicate phone rejected with
  SQLState 23505, decorated local form rejected with 23514, two NULL phones both persist,
  and a canonical phone accepted and read back unchanged.
- CI run `38427730278` on `test-phone-constraints`: **success**. `Tests run: 57, Failures: 0,
  Errors: 0, Skipped: 0` — 53 + 4 tests, 18 classes — with the two constraints observed
  firing in the log: `violates check constraint "businesses_phone_shape"` (SQLState 23514)
  and `duplicate key value violates unique constraint "uq_businesses_phone"` (SQLState 23505).
- Useful side effect: the green run **proves the CHECK is emitted by `ddl-auto` when the
  table is created**, which previously rested on the hand-applied SQL of an existing
  database. So a fresh environment does start with the constraint in place.

### T4 — sensitivity by mutation (observed, not argued)

Two throwaway branches, each with exactly one constraint removed, were pushed and built:

| mutation branch | removed from `Business.java` | CI run | result | attribution |
|---|---|---|---|---|
| `mutation-no-check` | `@Column(check = @CheckConstraint("businesses_phone_shape" ...))` | `38427803997` | failure | **only** `databaseRejectsNonCanonicalPhoneShape` fails (1 of 57); no 23514 observed, the insert succeeded |
| `mutation-no-unique-phone` | `@UniqueConstraint(name = "uq_businesses_phone" ...)` | `38431999375` | failure | **only** `databaseRejectsDuplicateBusinessPhone` fails (1 of 57); no `uq_businesses_phone` violation observed |

So each constraint is pinned by its own test, with no collateral damage: the two mutations
left `nullPhonesDoNotCollide`, `canonicalPhoneIsAccepted` and the other 17 classes green.
Both mutation branches are throwaway and were deleted.

### Not proven

- The CHECK regex is pinned for the decorated-local-form case and the canonical case, not
  against an exhaustive phone-format corpus: a value that satisfies
  `^[1-9][0-9]{7,14}$` but is not a real subscriber number still passes.
- The constraints' presence in an **existing** production database still depends on the
  hand-applied SQL documented in `Business.phone`; the repository has no migrations.
