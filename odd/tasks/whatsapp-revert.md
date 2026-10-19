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
  smaller reviewable commits, each under the budget. Granularity came from measured line
  counts and the real reference graph.
- **Stage order was corrected after a reviewer finding.** The original chain removed the
  phone-resolution classes before the text normalizer, but `NormalizationResult` and
  `TextNormalization` link to `PhoneResolution` and `PhoneMatching` with `{@link}`. That
  left dangling javadoc links in an intermediate commit. The reviewer reported
  `R3-broken-dependents-on-deleted-resolver` with unknown causality and the lineage
  escalated (terminal). Fixed by swapping the two stages so the normalizer leaves first,
  while its link targets still exist, and by cleaning the dead javadoc references in
  `Business.java`. Both changes are in this chain; the escalated lineage
  `review-b9064b4348fbfb23` (candidate `8e4a8e2`) is superseded and kept only as record.
- This document is updated inside the last stage on purpose: a reviewer looking only at
  that candidate's diff must be able to see what the removal leaves behind and why, so a
  scope question cannot look like an incomplete removal.

## Stages

Each stage is its own review candidate (base = previous stage) under the user-owned RDD
switch. Line counts are the candidate's deletion count.

| stage | commit | scope | lines | CI | native review |
|-------|--------|-------|-------|----|---------------|
| C0 | `5849a92` | plan (doc-first) | — | n/a (docs) | n/a (docs-only) |
| S1 | `17b4646` | webhook controller, Meta JSON mapper, `SecurityConfig` rule, and the three suites that import and mount the controller | 2310 | run 38412535014 success | `review-7bea1ec6c8c43035` approved + burned |
| S2 | `4b3a519` | signature verifier, Meta translator + payload, handler contract, `PersistingInboundMessageHandler` | 667 | run 38412548109 success | `review-f0f0b2db768940a8` approved + burned |
| S3 | `c401d25` | text normalizer (`TextOrderNormalizer`, `TextNormalization`, `NormalizedLine`, `NormalizationResult`) + its suite | 1629 | run 38418499893 success (76 tests / 20 classes) | `review-97e851467fc6dc5a` approved + burned |
| S4 | `3698882` | phone resolution, `WhatsappProperties`, `application.yml` block, `libphonenumber`, `BusinessRepository` finder, three suites, plus the `Business.java` javadoc cleanup | 1197 | run 38418507375 success (53 tests / 17 classes) | `review-ee65ac40f4a8cf9a` approved + burned |
| S5 | `d16d37a` | proposal model (`OrderProposal`, `OrderProposalItem`, `ProposalStatus`, `ItemResolution`, `OrderProposalRepository`), the feature's task doc and simulator, and this plan update | 1208 | runs 38423746484 and 38423751665 success (53 tests / 17 classes) | `review-d8d6b43ee956c427` approved + burned |

All five candidates were approved and their receipts confirmed (`authority: burned` for each).
The first version of S5 (`f119747`, lineage `review-638941ae97871071`) escalated with
`R3-partial-feature-removal`; it was superseded by the version above, which carries the scope
boundary inside the diff. The reviewer then read that boundary and raised only advisory
suggestions, so the intent is legible from the candidate alone.

Per-stage CI is a real gate, not a formality: the host has no JDK and no Maven, so each
stage was pushed to a temporary branch and built with
`gh workflow run CI --ref <branch>` (the workflow fires on `workflow_dispatch` even though
`ci.yml` exists only on `develop`). Test counts decrease monotonically and exactly as the
deleted suites predict: 106 → 106 → 76 → 53 → 53.

## What this removal deletes

The whole inbound auto-order path: the Meta webhook endpoint and its signature
verification, the payload translator, the handler that resolved a business by phone,
normalized free text and persisted an order proposal, the phone-to-business resolution
(E.164 canonicalization and pure matching rule), the pure text normalizer, and the
proposal data model that existed only for this flow (it doubled as the inbound record and
the idempotency table). Its tests, task doc and simulator go with it.

## What it deliberately leaves in place, and why

A reviewer must be able to tell these survivors from an incomplete removal:

1. **The public menu's WhatsApp button and `wa.me` deep link**
   (`static/menu/app.js`, `index.html`, `style.css`, and its JS contract test). It predates
   the feature on `main` (`app.js:12,79,311`); `develop` only turned it into a fallback
   when API submission fails. A customer-initiated link that opens the WhatsApp client is
   not a chat that answers messages by itself, so it is out of scope by the user's own
   definition of what to abandon.
2. **`BusinessDTO.whatsappNumber` and `BusinessMapperImpl`.** Pre-existing on `main`; the
   feature modified neither (empty diff against `origin/main`).
3. **The `businesses.phone` column and its CHECK/UNIQUE constraints** in `Business.java`.
   The column predates the feature. The constraints are schema hygiene: dropping them
   would require hand SQL on the existing database and would create drift between fresh
   and existing databases, since the repository has no migrations. Only the javadoc was
   cleaned here, to drop references to the classes this chain deletes.
4. **`spring.jpa.hibernate.ddl-auto: update`, `check_nullability`, the `qr:` block,
   `zxing`/`openpdf`, the KDS guard, the cross-tenant stock fix and CI.** Unrelated work
   that shares the branch.
5. **`odd/tasks/cross-tenant-stock.md`** mentions WhatsApp in passing; it is another
   feature's record and is left untouched.

## Follow-ups (out of scope for this revert)

1. **Closed (2026-10-13) — nothing to drop.** The feature never reached production, and the
   only database that ever held these tables was a development database that no longer exists
   or lives on a different machine. A fresh database cannot recreate them either: no entity
   maps `order_proposals` / `order_proposal_items` any more, so `ddl-auto` never emits them
   and no code reads them. The statement below is kept as a **rescue note** in case that old
   database ever reappears, not as pending work. Dropping `order_proposals` also drops its
   UNIQUE `uq_order_proposals_message_id`. In dependency order:

   ```sql
   -- 0. Confirm these are the orphans and see what is inside them before touching anything.
   SELECT 'order_proposals' AS tabla, count(*) FROM order_proposals
   UNION ALL
   SELECT 'order_proposal_items', count(*) FROM order_proposal_items;

   -- 1. order_proposal_items holds the FK (proposal_id) into order_proposals(id),
   --    so it goes first. Deliberately no CASCADE: if anything else depends on them,
   --    you want to hear about it rather than lose it silently.
   BEGIN;
   DROP TABLE order_proposal_items;
   DROP TABLE order_proposals;
   COMMIT;
   ```

   Never run by the agent, and no longer required: this host has no PostgreSQL client and
   nothing listening on port 5432, and the development database lives outside this
   environment. Kept only so that whoever meets that old schema has the exact statement,
   without having to rediscover the FK order.
2. The `businesses.phone` CHECK/UNIQUE constraints stay, and their missing tests were
   closed separately: `BusinessPhoneIntegrityTests` pins both, with sensitivity proven by
   mutation (see `odd/tasks/phone-constraint-tests.md`, PR #3). Revisit the constraints
   only if the phone field itself is repurposed away from WhatsApp.
3. Module `service` has **zero** tests in CI (`No tests to run`), so the surviving pipeline
   is exercised only through the API layer.
4. Advisory non-blocking findings raised along the chain: coverage lost by deleting the
   three webhook suites in S1 (`R3-coverage-loss-for-unchanged-service-code`), deletion
   warnings in S2, and doc-drift notes in S4. None opened a correction; the review receipts
   stand.

## Verification route

- **Local build is impossible on this host**: no JDK, no Maven, no wrapper, no container
  runtime; `sudo` needs an interactive password. Environment limit, not a skipped check.
- Authoritative gate: the repository's CI (`mvn -B --no-transfer-progress verify` plus three
  node contract scripts), green on every stage including the pre-chain baseline for
  `develop` (run 35472397760).
- Invariant used to prove the split is semantically identical to the original single
  commit: `git diff --stat a54e88e <tip>` must show only `odd/tasks/whatsapp-revert.md`
  and the `Business.java` javadoc — the two intentional deviations.
