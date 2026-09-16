# Feature: table-qr-links

**Branch**: `fix-qr-mesa-links` (from `fix-pedidos-carta-qr`)
**Status**: 2/2 tasks done and green; pending independent verification
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
  pasted into the Evidence section of this document before the fix is written. Filesystem mtime is
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
| T2 | done | — | RED `5/5` failures (literal below); GREEN `5/5`; full suite 29/29; JS 4/4 |
| T2 | pending | — | — |

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

_pending_

## Open gaps and decisions

1. **Per-table identity is dropped from the QR.** Accepted consequence of the authorized scope:
   two tables share a byte-identical QR. If dine-in table attribution is wanted later, it is a
   separate slice that must also decide whether an anonymous customer may present an arbitrary
   table token, since the order endpoint is public.
2. **`TableResponseDTO.qrUrl` has no known consumer.** No static file references it. It is fixed
   anyway because it is part of the same dead-URL defect, but nothing user-visible depends on it.
3. **`qrToken` remains a real, unique column** and is still returned by the DTO. It is simply no
   longer part of any printed URL. Not removed, to keep this slice reversible.
4. **NEW, verified pre-existing defect found while doing T2 — `Grid3Template` drops incomplete rows.**
   `Grid3Template` lays every cell into a 3-column `PdfPTable`. When `tables.size() % 3 != 0`, the final
   incomplete row never renders; with 1 or 2 tables the table renders zero rows and `document.close()`
   throws `ExceptionConverter: java.io.IOException: The document has no pages.` Consequence:
   `GET /api/tables/qr/pdf?template=GRID_3X3` with 1 or 2 tables returns a broken/empty PDF for reasons
   **independent of the URL defect**. Observed literally: the first T2 RED run failed with that
   `RuntimeException` instead of the URL assertion, which is how it was found. Out of scope for this
   slice (fixing it means changing document logic), disclosed as follow-up material. The T2 tests use 3
   tables (one complete row) so they exercise the URL contract instead of tripping on this quirk.
