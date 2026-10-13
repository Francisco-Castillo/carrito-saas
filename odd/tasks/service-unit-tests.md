# Feature: service-unit-tests

## Goal

Give the `service` module its first test sources, starting with the pure decision logic of
`ProductInsightService`, so the module stops being a black box that only gets exercised
through slow `api` integration tests.

## Why this target

- `service` has **zero** test sources, which is what makes CI report `No tests to run` for it.
- `ProductInsightService.calculate(Double, Double)` (`ProductInsightService.java:9-36`) is a
  **fully pure** four-quadrant decision table: no Spring context, no database, no clock, no
  I/O. Instantiable with `new`.
- Its only caller is `ProductServiceImpl` (`ProductServiceImpl.java:42,46`), reached through
  a `@SpringBootTest` path that does not assert the insight values at all: **the decision
  table is completely unpinned today**.
- It has real branching and boundaries (null guard, zero on both axes, the sign convention),
  unlike the mapper boilerplate that makes up most of the module.

## Scope

One plain JUnit 5 + AssertJ test class, `service/src/test/java/com/carrito/saas/service/impl/ProductInsightServiceTest.java`,
pinning:

| property | expected |
|---|---|
| `trend == null` or `prediction == null` | `NEUTRAL` |
| `trend < 0 && prediction < 0` | `CRITICAL` |
| `trend < 0 && prediction == 0` | `CRITICAL` — "growing" means strictly `prediction > 0` |
| `trend < 0 && prediction > 0` | `OPPORTUNITY` |
| `trend > 0 && prediction > 0` | `BOOST` |
| `trend > 0 && prediction == 0` | `UNSTABLE` |
| `trend > 0 && prediction < 0` | `UNSTABLE` |
| `trend == 0`, any prediction | `NEUTRAL` — a flat trend swallows the prediction |
| `trend == -0.0` | `NEUTRAL` — `-0.0 < 0` is false in Java |

## Non-goals

- **Not changing `ProductInsightService`.** This is a characterization test: it pins the
  behavior that ships today. If a boundary turns out to be undesired, that is a separate
  product decision with its own change.
- Not pinning `Double.NaN` / infinite inputs: today every comparison is false for `NaN`, so
  it returns `NEUTRAL` by accident rather than by decision. Reported as a gap, not locked in.
- Not migrating existing tests from `api`, and not adding tests for trivial mappers.

## Constraints

- **No `service/pom.xml` change is needed**: the root `pom.xml:38-45` declares
  `spring-boot-starter-test` (scope `test`) in a module-level `<dependencies>` block, which
  every child module inherits, with versions managed by `spring-boot-starter-parent` 4.0.3.
- House style, taken from the representative unit test `api/src/test/java/com/carrito/saas/MenuUrlBuilderTests.java`:
  plain JUnit 5, AssertJ `assertThat`, package-private class, no Spring, no Mockito.
- Local build is impossible here (no JDK, no Maven), so verification is the repository's CI,
  and sensitivity is proven by mutation, as with the phone constraints.

## Tasks

| id | task | status |
|----|------|--------|
| T1 | Choose the target and prove it is pure and uncovered | done |
| T2 | Write `ProductInsightServiceTest` | pending |
| T3 | CI green on the feature branch | pending |
| T4 | Sensitivity: a mutation on a branch condition must fail its test | pending |
| T5 | Native review and PR decision | pending |

## Evidence

### T1 — exploration

- `service/pom.xml` declares no test dependencies; the root `pom.xml:38-45` supplies
  `spring-boot-starter-test` to every module, which explains the missing-sources diagnosis
  rather than a missing classpath.
- `grep ProductInsightService api/src/test` → no matches: zero coverage, direct or indirect.
- The class is `@Component` with an implicit no-arg constructor and no injected fields, so a
  plain unit test needs no context.
- Reviewed candidates and why they ranked lower: `generateKey(List<Long>)` is pure but tiny
  and duplicated in `ProductPredictionServiceImpl.java:52` and `ProductTrendServiceImpl.java:88`
  (a test cannot stop that drift); `ProductSpecification.hasStock()` needs Mockito mocks of
  the JPA criteria API, so it is not a first unit test; `MenuUrlBuilder.buildMenuUrl` is
  already unit-tested from `api`.
