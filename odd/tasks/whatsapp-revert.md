# Feature: revert-whatsapp-chat

## Goal

Remove the "WhatsApp auto-order chat" feature from `develop` so the product no longer
answers customer messages automatically, **without** touching unrelated work that
happened to land on the same branch (QR table links, KDS session guard, cross-tenant
stock fix, CI).

## Decision record

- 2026-10-13: user abandoned the WhatsApp investigation idea.
- Local branch `feat-whatsapp-confirm` was deleted; its in-progress T7b.2 work sits in
  `stash@{0}` ("abandonado: T7b.2 en curso (2026-10-13)").
- The feature is already merged and pushed on `develop`, so abandoning it requires an
  explicit revert, not a branch delete. The T7a/T7b proposal endpoints never reached
  `develop`.
- Approach: revert on a dedicated branch, reviewed through a PR against `develop`. No
  history rewrite of shared branches, no force push to `develop`.
- The single-commit revert (`a54e88e`, 6795 deletions) was **rejected by native review**
  with `lens_context_budget_exceeded`: the candidate's reviewer evidence exceeded the
  native context budget, no review authority was created, and retrying that exact
  candidate cannot succeed. Prescribed continuation: split it into a chained sequence of
  smaller reviewable commits, each under the budget.
- The chain below is that split. Granularity was chosen from measured line counts and the
  real reference graph, not from taste: a 3-way split leaves one 4564-line stage that
  would fail the same way.

## Scope boundary (verified 2026-10-13)

In scope — the feature being removed:

- `service/.../service/whatsapp/**` (15 files): inbound handler, Meta translator and
  payload, signature verifier, phone resolution (`BusinessPhoneResolver`, `PhoneMatching`,
  `PhoneNumbers`, `PhoneResolution`), text normalizer (`TextOrderNormalizer`,
  `TextNormalization`, `NormalizedLine`, `NormalizationResult`).
- `service/.../service/config/WhatsappProperties.java`.
- `api/.../api/WhatsappWebhookController.java`, `api/.../config/MetaWebhookJsonMapper.java`.
- Proposal model created for the feature: `OrderProposal`, `OrderProposalItem`,
  `ProposalStatus`, `ItemResolution`, `OrderProposalRepository`.
- Its 7 test classes and its tooling/docs (`odd/tasks/whatsapp-inbound.md`,
  `odd/tasks/tools/whatsapp-simulator.mjs`).

Explicitly OUT of scope — must survive untouched:

- The public menu's outgoing WhatsApp button and its `wa.me` deep link
  (`static/menu/app.js`, `static/menu/index.html`, `api/src/test/js/menu-app.test.mjs`).
  It predates the feature on `main` (`app.js:12,79,311`); `develop` only turned it into a
  fallback. A customer-initiated link is not a chat that answers by itself.
- `POST /api/orders/menu/{slug}` permitAll in `SecurityConfig.java` (public menu ordering).
- QR/public-menu work: `qr:` block in `application.yml`, `zxing` and `openpdf` in
  `service/pom.xml`, `QrProperties`.
- Stock work: `NullStockMeansInfinite` semantics, cross-tenant stock fix, stock test suites.
- KDS session guard, admin panel API-origin config, CI setup.
- The `businesses` UNIQUE/CHECK constraint annotations in `Business.java`. Rationale: the
  `phone` column predates the feature on `main`; the constraints are schema hygiene whose
  removal would need hand SQL on the existing database and would create drift between
  fresh and existing databases (no migrations in the repo — open gap 30). Follow-up, not
  silently reverted.
- `spring.jpa.hibernate.ddl-auto: update` and `check_nullability: true`.

## Constraints

- No new dependencies. Remove `libphonenumber` (feature-only), keep `zxing`/`openpdf`.
- **Every commit in the chain must compile and keep the suite green**, so the branch stays
  bisectable. The split follows the reference graph: `WhatsappInboundPersistenceTests` and
  `WhatsappIdempotencyRaceTests` reference the handler, translator, signature verifier,
  phone classes and proposal model simultaneously, so they leave with the first stage that
  removes any of those.
- `WhatsappProperties` cannot leave before its consumers: `BusinessPhoneResolver` and
  `WhatsappSignatureVerifier` import it.
- Push, PR creation and merge stay under user control.

## Stages

| stage | scope | lines | status |
|-------|-------|-------|--------|
| C0 | plan (this document) | — | pending |
| S1 | REST surface: webhook controller, Meta JSON mapper, `SecurityConfig` rule, webhook contract tests | 921 | pending |
| S2 | Ingest chain: signature verifier, Meta translator + payload, handler contract and `PersistingInboundMessageHandler`, their two integration/race test suites | 2056 | pending |
| S3 | Phone resolution: `PhoneNumbers`, `PhoneMatching`, `PhoneResolution`, resolver pair, `WhatsappProperties`, `application.yml` block, `libphonenumber`, `BusinessRepository` finder, three test suites | 1159 | pending |
| S4 | Text normalizer: `TextOrderNormalizer`, `TextNormalization`, `NormalizedLine`, `NormalizationResult`, `TextOrderNormalizerTests` | 1629 | pending |
| S5 | Proposal model (`OrderProposal*`, `ProposalStatus`, `ItemResolution`, `OrderProposalRepository`) plus the feature's task doc and simulator | 1044 | pending |
| C6 | evidence: CI results and review outcomes | — | pending |

Each stage is reviewed as its own candidate (base = previous stage) under the user-owned
RDD switch.

## Evidence

### T1 — inventory (verified against `origin/main` and `origin/develop`)

- Baseline: `origin/main` has 0 whatsapp files and no `OrderProposal*` model.
- `develop` is 42 commits ahead of `main`; 19 touch feature files. Three of them
  (`0291f06`, `d90e548`, `6f4f561`) also carry unrelated stock work, which is why whole-commit
  `git revert` was rejected in favour of surgical removal.
- Feature references outside their own files: `SecurityConfig.java:91`,
  `application.yml:47-50`, `service/pom.xml:63-73`, `BusinessRepository.java:61`, plus the 7
  feature test classes. No production code outside the feature references the model.
- Shared files verified to contain **zero** feature references, so their `develop` changes
  are unrelated and stay: `MenuServiceImpl`, `OrderServiceImpl`, `BusinessController`,
  `Order`, `ProductRepository`, `pom.xml`, `.gitignore`.
- `dto/BusinessDTO.java` and `BusinessMapperImpl.java` are not modified by the feature
  (empty diff vs `origin/main`); their `whatsappNumber`/`phone` alias predates it.

### Verification route

- **Local build is impossible on this host**: no JDK, no Maven, no wrapper, no container
  runtime; `sudo` needs an interactive password. Environment limit, not a skipped check.
- Authoritative gate: the repository's CI (`mvn -B --no-transfer-progress verify` plus
  three node contract scripts). `gh workflow run CI --ref <branch>` works even though
  `ci.yml` exists only on `develop`, so each stage can be verified on its own branch
  without opening a PR.

## Follow-ups (out of scope for this revert)

1. `Business.java:77,117` still cites the deleted
   `com.carrito.saas.service.whatsapp.PhoneNumbers#canonicalizeToE164` inside `{@code}`.
   Comment-only, compiles, but the reference is dead. Deliberately left: `Business.java` is
   a hard boundary of this revert.
2. Existing databases keep the orphan `order_proposals` / `order_proposal_items` tables
   created by `ddl-auto: update`. Dropping them needs an explicit DB decision.
3. The `businesses.phone` CHECK/UNIQUE constraints stay. Revisit only if the phone field
   itself is repurposed away from WhatsApp.
4. Module `service` has **zero** tests in CI (`No tests to run`), so the surviving pipeline
   is exercised only through the API layer.
