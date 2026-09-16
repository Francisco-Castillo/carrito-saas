# Feature: table-qr-links

**Branch**: `fix-qr-mesa-links` (from `fix-pedidos-carta-qr`)
**Status**: 2/2 tasks done and green; independent verification passed with no blocking findings
**Commits**: one per task, no push

## Objective

Make every per-table QR the API produces encode the real, reachable public menu URL, so a
printed or scanned table code lands on the customer menu instead of a host that does not exist.

## Problem

Three independent, mutually inconsistent and dead URL writers exist for tables, and none of them
matches the application's own origin nor the only URL shape the menu page accepts.

| Site | Encodes today |
| --- | --- |
| `service/.../strategies/impl/SingleTemplate.java:84` | `http://localhost:3030/<qrToken>` |
| `service/.../strategies/impl/Grid3Template.java:68` | `http://localhost:3030/<qrToken>` |
| `service/.../strategies/impl/AcrylicTemplate.java:61` | `http://localhost:3030/<qrToken>` |
| `service/.../service/impl/TableServiceImpl.java:65` (`generateQr`) | `/<qrToken>` — relative, no host at all |
| `service/.../service/impl/TableServiceImpl.java:232` (`buildResponse`) | `http://localhost:8080/<qrToken>` |

The application serves on port **9090**. A working primitive already exists: `MenuUrlBuilder`
(`service/.../service/impl/MenuUrlBuilder.java`) builds `<base>/menu/index.html?restaurant=<slug>`
from `QrProperties.publicMenuUrl`, bound to `qr.public-menu-url: ${QR_PUBLIC_MENU_URL:http://localhost:9090}`.
That is the exact URL shape `GET /api/business/qr` produces, and the one the pilot rehearsal
proved scannable (`http://192.168.1.5:9090/menu/index.html?restaurant=demo-pizzeria`).

## Why

A table QR that decodes to `http://localhost:3030/<uuid>` is useless on any device but the machine
that printed it, and it is not merely a host typo. Fixing the host alone would still produce a dead
link, because **nothing consumes a per-table token anywhere in the system** (all verified by
grep, not inferred):

- no Spring MVC route serves `/{qrToken}` — `IPublicMenuController.getMenu(@PathVariable String token)`
  has no annotated implementation;
- `TableRepository.findByQrToken` exists and is never called;
- `setRestaurantTable` has zero occurrences in production code, so `Order.restaurantTable` is
  never set and `PublicMenuOrderHttpTests` asserts orders persist with `table_id IS NULL`;
- `api/src/main/resources/static/menu/app.js` reads only `?restaurant=<slug>`; `OrderRequestDTO`
  carries no table field;
- no file under `static/` references `/api/tables`, `qrUrl`, or any QR endpoint at all (grep returns
  zero hits), so this surface is API-only today.

Therefore the honest repair is: point every table QR at the business menu URL that actually works,
and drop the per-table token from the payload. Per-table attribution is recorded below as an
explicit non-goal, not silently abandoned.

## Scope

**In**

- `service/src/main/java/com/carrito/saas/service/impl/TableServiceImpl.java` — `generateQr`, `buildResponse`
- `service/src/main/java/com/carrito/saas/service/strategies/impl/SingleTemplate.java`
- `service/src/main/java/com/carrito/saas/service/strategies/impl/Grid3Template.java`
- `service/src/main/java/com/carrito/saas/service/strategies/impl/AcrylicTemplate.java`
- `api/src/test/java/com/carrito/saas/TableQrServiceLinkTests.java` (new)
- `api/src/test/java/com/carrito/saas/QrPdfTemplateUrlTests.java` (new)

**Out (explicit non-goals, each verified as real work)**

- **Per-table attribution.** `?table=<token>` read by the menu page, a `table` field on
  `OrderRequestDTO`, resolving the token via `findByQrToken`, and setting `Order.restaurantTable`.
  Consequence accepted: every table of a business now encodes the same URL.
- A route serving `/{qrToken}`.
- `QrTemplate.GRID_4X4`: present in the enum with no strategy implementation, so `PdfTemplateFactory.get`
  throws. Pre-existing.
- `BusinessController.iBusinessService` unassigned → NPE on `GET /api/business/slug/{slug}`. Pre-existing.
- `static/admin/admin.js` API base `http://localhost:8080` and the `SecurityConfig` CORS origin —
  real, but not QR.

## Constraints

- **Strict TDD: ON.** Every task begins with an executed, failing run. The literal RED output is
  pasted into the Evidence section of this document. Note, because independent verification refuted the
  stronger wording used earlier: the evidence is *observed* before the fix, but the document edit is
  committed **inside the same commit as the fix**, so git alone cannot prove that ordering. It is
  corroborated by the untracked runtime log (see Independent verification). Filesystem mtime is
  not accepted as proof that a failing run was observed.
- **Runner**: `mvn -B --no-transfer-progress verify` for the full suite; `mvn -Dtest=<Class> test`
  for the focused RED/GREEN loop; `node api/src/test/js/menu-app.test.mjs` for the JS contract.
- **Test style**: follow the existing classes — `@SpringBootTest @Transactional`, manual MockMvc
  (`MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build()`),
  JWT minted by `JwtUtil` over `BusinessAuthSeed.seedOwner(...)`, real PostgreSQL on `localhost:5432`.
  Plain unit tests without Spring are acceptable for pure collaborators, as `MenuUrlBuilderTests` does.
- Do not commit anything except through the per-task commits.

## Tasks

- [x] **T1 — Service layer: the table QR and the DTO `qrUrl` come from the builder.**
  Inject `IMenuUrlBuilder` into `TableServiceImpl`; replace both hardcoded constructions
  (`generateQr:65`, `buildResponse:232`). Remove the two stale comment blocks that still document
  the abandoned `QrProperties` wiring. RED-first test: `TableQrServiceLinkTests`.
- [x] **T2 — The three PDF templates stop hardcoding `localhost:3030`.**
  Inject `IMenuUrlBuilder` into `SingleTemplate`, `Grid3Template` and `AcrylicTemplate`; replace the
  three hardcoded strings. RED-first test: `QrPdfTemplateUrlTests`.

`PdfTemplateFactory` needs no change: it receives `List<PdfTemplateStrategy>` by Spring type
injection and never constructs the templates, so adding a constructor dependency is additive.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC1 | `GET /api/tables/{id}/qr` returns a PNG that ZXing decodes to exactly `<base>/menu/index.html?restaurant=<slug>` |
| AC2 | `POST /api/tables` returns a `qrUrl` equal to that same URL |
| AC3 | each of the three templates hands `IQrCodeService.generateQr` the URL produced by `MenuUrlBuilder`, for a **non-default** base, and `localhost:3030` appears in none of them |
| AC4 | `GET /api/tables/{id}/qr/pdf?template=SINGLE&showUrl=true` renders a PDF whose extracted text contains the real menu URL |
| AC5 | no table QR encodes `localhost:3030`, `localhost:8080` or a bare `/<token>`; the base reaches the payload from `QrProperties` only |
| AC6 | `mvn -B --no-transfer-progress verify` green and `node api/src/test/js/menu-app.test.mjs` green |

## Progress

| Task | Status | Commit | Evidence |
| --- | --- | --- | --- |
| T1 | done | `2e457f6` | RED `2/2` failures (literal below); GREEN `2/2`; full suite 24/24; JS 4/4 |
| T2 | done | `10b72f0` | RED `5/5` failures (literal below); GREEN `5/5`; full suite 29/29; JS 4/4 |

## Evidence

### RED logs (literal command output)

**T1** — `mvn -B --no-transfer-progress -Dtest=TableQrServiceLinkTests -Dsurefire.failIfNoSpecifiedTests=false test`
(before any production change; `-Dsurefire.failIfNoSpecifiedTests=false` is required or the 8-module reactor
fails at the first module without a matching test, i.e. `common`)

```text
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 9.907 s <<< FAILURE! -- in com.carrito.saas.TableQrServiceLinkTests
[ERROR] com.carrito.saas.TableQrServiceLinkTests.createdTableReturnsThePublicMenuUrlAsQrUrl -- Time elapsed: 0.942 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
[the created table's qrUrl must equal menuUrlBuilder.buildMenuUrl(slug)]
expected: "http://localhost:9090/menu/index.html?restaurant=table-qr-link-business"
 but was: "http://localhost:8080/0b3df586-a852-49af-8ec8-b79cd4da752c"

[ERROR] com.carrito.saas.TableQrServiceLinkTests.tableQrPngEncodesThePublicMenuUrl -- Time elapsed: 0.174 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
[the table QR PNG must encode menuUrlBuilder.buildMenuUrl(slug)]
expected: "http://localhost:9090/menu/index.html?restaurant=table-qr-link-business"
 but was: "/06205d49-d9c1-4fa4-9ed8-a22b99ac89fa"

[INFO] Results:
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

The two failures reproduce exactly the two defects: `generateQr` encodes a bare `/<qrToken>`, and
`buildResponse` emits `http://localhost:8080/<qrToken>`.

**T2** — same focused command, `-Dtest=QrPdfTemplateUrlTests -Dsurefire.failIfNoSpecifiedTests=false test`,
with `@SpringBootTest(properties = "qr.public-menu-url=https://qr.example.test/")`. The override makes any
hardcoded host structurally unable to satisfy the assertion.

```text
[ERROR] Tests run: 5, Failures: 5, Errors: 0, Skipped: 0, Time elapsed: 10.42 s <<< FAILURE! -- in com.carrito.saas.QrPdfTemplateUrlTests

[ERROR] QrPdfTemplateUrlTests.singleTemplateHandsTheConfiguredPublicMenuUrlToTheQrEncoder
[the SINGLE template must hand menuUrlBuilder.buildMenuUrl(slug) to the QR encoder, not a hardcoded host
 (actual: http://localhost:3030/1d0a34b2-82e3-4b33-95ca-e840a766d29a)]
expected: "https://qr.example.test/menu/index.html?restaurant=qr-pdf-url-business"
 but was: "http://localhost:3030/1d0a34b2-82e3-4b33-95ca-e840a766d29a"

[ERROR] QrPdfTemplateUrlTests.acrylicTemplateHandsTheConfiguredPublicMenuUrlToTheQrEncoder
 (actual: http://localhost:3030/48b0dcac-a08e-4f5d-b7e8-99462cc8518c)

[ERROR] QrPdfTemplateUrlTests.grid3TemplateHandsTheConfiguredPublicMenuUrlToTheQrEncoder
 (actual: [http://localhost:3030/2c65fbde-828c-4a90-a446-b4411bad2740, http://localhost:3030/80333649-b7c3-4994-bff1-46fe979301c6, ...])

[ERROR] QrPdfTemplateUrlTests.everyTableOfTheBusinessReceivesTheSamePublicMenuUrl
 (actual: [http://localhost:3030/d310e302-685d-424a-8cf3-8e25f1f3ce61, http://localhost:3030/b84c7f18-cdc2-4d66-a90f-27dbfa4e2b26, ...])

[ERROR] QrPdfTemplateUrlTests.printedSingleTablePdfContainsTheConfiguredPublicMenuUrl
[the printed PDF must show the configured public menu URL, not a dead host
 (actual: Mesa3MesaN°3http://localhost:3030/3f041216-d665-44fd-ac23-d383439603b5)]
Expecting actual: "Mesa3MesaN°3http://localhost:3030/3f041216-d665-44fd-ac23-d383439603b5"
to contain: "https://qr.example.test/menu/index.html?restaurant=qr-pdf-url-business"
```

The last failure is the strongest evidence in this document: the text extracted from a **real rendered
PDF** shows the dead host, so the defect was proven on the printed artifact, not only on an argument.

### GREEN runs

**T1** — focused: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
Full suite after the fix: `Tests run: 24, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS
(baseline was 22; the 2 new tests are this class). JS: `4 test(s) passed`.

**T2** — focused: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.
Full suite after the fix: `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (22 baseline
+ 2 from T1 + 5 from T2). JS: `4 test(s) passed`.

### Notes carried forward from T1

1. **The controller contract lives on the interface, not the impl.** `ITableController.create` declares
   `@Valid @RequestBody CreateTableRequestDTO`, so `POST /api/tables` requires a JSON body; posting query
   params yields `HttpMessageNotReadableException` → HTTP 500. The earlier assumption in this document,
   based on reading only `TableController`, was wrong.
2. **No `ObjectMapper` bean in this context**; tests instantiate `new ObjectMapper()`, as
   `PublicMenuOrderHttpTests` already does.
3. `RestaurantTableMapperImpl.toDTO` hardcodes `setQrUrl("")` and `setStatus(AVAILABLE)`, so the DTO's
   `qrUrl` is service-owned — which is why this repair belongs in `TableServiceImpl`.

### Notes carried forward from T2

4. **`@MockitoBean IQrCodeService` must return a real PNG.** `Image.getInstance(qr)` throws on a null or
   empty array (and `Grid3Template` throws its own `IllegalStateException`), so the test stubs the mock with
   `new ZxingQrCodeServiceImpl().generateQr("stub", 180)` — a real encoder with no dependencies.
5. **OpenPDF 2.0.3 text extraction**: use `com.lowagie.text.pdf.parser.PdfTextExtractor.getTextFromPage(int)`;
   there is no no-arg `getText()`. Strip `\s+` from both sides before comparing so PDF line wrapping
   cannot make the assertion flaky.

### Independent verification

Performed by `gentle-ai-verify` over the frozen range `3526f13..10b72f0`, read-only.
**Frozen revision unmodified: all 7 touched files byte-identical before and after, `HEAD` unchanged,
`git diff --stat` empty.**

Verdicts: **AC1 MET, AC2 MET, AC3 MET, AC4 MET, AC5 MET, AC6 MET.** No blocking findings.

What the verification *independently established* (not accepted from this document):

- **Mutation proof, run in a `/tmp` copy with the base blobs restored** — `TableQrServiceLinkTests` goes
  from green to `2/2` failures and `QrPdfTemplateUrlTests` to `5/5` failures, with the observed values
  being the old hardcoded `http://localhost:8080/<token>` and `http://localhost:3030/<token>`. Crucially
  the mutated `qrUrl` failure showed the old hardcoded URL and **not** the empty string that
  `RestaurantTableMapperImpl.toDTO` sets, which rules out the one vacuous-pass hypothesis.
- **No shared-wrong expectation:** `qr.example.test` exists only in the test and this document, so the
  `@SpringBootTest(properties = ...)` override cannot be satisfied by any literal in production.
- **Repo-wide grep:** the only surviving `3030`/`8080` occurrences are `SecurityConfig`'s CORS origin and
  `admin.js`'s API base — neither is a QR payload. Exactly five payload producers remain, all through
  `MenuUrlBuilder`.
- **Lazy-loading containment:** every call path reaching `buildResponse`/`generateQr`/the templates is
  covered by `TableServiceImpl`'s class-level `@Transactional` (including `findAll` and `findById`, which
  have no HTTP mapping at all), with `spring.jpa.open-in-view=true` as an accidental second net. No
  `LazyInitializationException` is reachable today. A test does **not** pin this.
- **Ordering corroboration:** the untracked `api/logs/app.log` shows the T1 RED run at 13:09 calling
  `TableServiceImpl.create` **without** a nested `MenuUrlBuilder.buildMenuUrl`, and the same run after the
  13:12 fix calling it nested — evidence git cannot provide because the document edit rides inside the fix
  commits.
- **`Grid3Template` finding reproduced independently** with a scratch OpenPDF program, and it is **worse**
  than first recorded (see gap 4).
- **Why the pre-existing PDF test was not a safety net:** `TableQrPdfContractTests` asserts only
  `length > 100` and the `%PDF-` magic bytes, so a PDF whose QR encodes a dead host is perfectly valid to it.

Refuted claims (all non-technical, all now corrected here):

1. This document's Progress table recorded no commit for T2 and carried a stale duplicate `T2 | pending`
   row. Fixed.
2. The stronger claim that the document *proves* the RED preceded the fix is unprovable from the artifact.
   Reworded in Constraints, with the log-based corroboration recorded above.
3. `CreateOrderWithoutTableTests`' javadoc still says "Currently RED" while the test passes, because
   `Order.restaurantTable` no longer has `nullable = false`. Pre-existing, outside this range: follow-up.

Not determinable: whether `businesses.slug NOT NULL` is enforced at the database level or only by
Hibernate (`check_nullability: true`); whether the `GRID_3X3` and `ACRYLIC` templates resolve the lazy
business over real HTTP (reasoned yes, only `SINGLE` was observed over HTTP).

## Open gaps and decisions

1. **Per-table identity is dropped from the QR.** Accepted consequence of the authorized scope:
   two tables share a byte-identical QR. If dine-in table attribution is wanted later, it is a
   separate slice that must also decide whether an anonymous customer may present an arbitrary
   table token, since the order endpoint is public.
2. **`TableResponseDTO.qrUrl` has no known consumer.** No static file references it. It is fixed
   anyway because it is part of the same dead-URL defect, but nothing user-visible depends on it.
3. **`qrToken` remains a real, unique column** and is still returned by the DTO. It is simply no
   longer part of any printed URL. Not removed, to keep this slice reversible.
4. **NEW, verified pre-existing defect found while doing T2 — `Grid3Template` drops rows.**
   `Grid3Template` lays every cell into a 3-column `PdfPTable`. Independently reproduced with a scratch
   OpenPDF 2.0.3 program: **1 or 2 tables → zero rows render and `document.close()` throws
   `ExceptionConverter: java.io.IOException: The document has no pages.`** (so
   `GET /api/tables/qr/pdf?template=GRID_3X3` fails with a 500); **4, 5, 7, 8 tables → the trailing partial
   row is silently dropped**, producing a byte-identical page to the complete-row case, while
   `generateQr` is still called for the dropped tables. The second half is the worse one: a printed sheet
   can silently be missing tables with no error at all. Three tables renders correctly (one complete
   row), which is why the T2 tests use 3. Independent of the URL defect; fixing it means changing document
   logic and is out of scope.
5. **New coupling not pinned by any test.** `buildResponse` and the three templates now touch the lazy
   `RestaurantTable.business` association; they are safe only because `TableServiceImpl` carries a
   class-level `@Transactional` (plus the `open-in-view` default as a second net). Removing that annotation
   or calling these paths outside a transaction would break them at runtime with no test failing first.
6. **Redundant coverage.** `everyTableOfTheBusinessReceivesTheSamePublicMenuUrl` restates the `GRID_3X3`
   captor test with fewer assertions, so it adds no independent coverage. Kept because it names the accepted
   trade-off in executable form.
7. **Pre-existing, out of scope, each worth its own slice:** `static/admin/admin.js:2` points its API base at
   `http://localhost:8080/api` while the app serves 9090; `CreateOrderWithoutTableTests`' javadoc claims
   "Currently RED" while it passes; `BusinessDTO.slug` accepts a blank string (degrades gracefully in
   `menu/app.js`); `BusinessController.iBusinessService` is never assigned.
