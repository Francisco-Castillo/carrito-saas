# Feature: table-qr-links

**Branch**: `fix-qr-mesa-links` (from `fix-pedidos-carta-qr`)
**Status**: tracking created — 0/2 tasks done, no source write yet
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

- [ ] **T1 — Service layer: the table QR and the DTO `qrUrl` come from the builder.**
  Inject `IMenuUrlBuilder` into `TableServiceImpl`; replace both hardcoded constructions
  (`generateQr:65`, `buildResponse:232`). Remove the two stale comment blocks that still document
  the abandoned `QrProperties` wiring. RED-first test: `TableQrServiceLinkTests`.
- [ ] **T2 — The three PDF templates stop hardcoding `localhost:3030`.**
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
| T1 | pending | — | — |
| T2 | pending | — | — |

## Evidence

### RED logs (literal command output)

_pending — T1_

_pending — T2_

### GREEN runs

_pending_

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
