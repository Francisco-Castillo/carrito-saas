# Feature: review-findings-backlog

Atender los hallazgos no bloqueantes de la serie de reviews nativos del panel
(12 lineages approved, #44 y #46-#56). Los textos de los revisores no son
recuperables del store local del motor; cada finding está registrado verbatim
por id, lente, ubicación y severidad en los cierres de la sesión 2026-10-17
(espejo Engram `odd/native-review/pr44..pr56`). La clasificación below se hace
leyendo el código en cada ubicación, no el texto.

## Specs

S1. Los dos defectos objetivos de formato de imports se corrigen: el import
    duplicado de `ResponseEntity` en `api/src/main/java/com/carrito/saas/api/CategoryController.java`
    (finding `R3-001` de #46) y las dos sentencias de import pegadas en la
    línea 15 de `service/src/main/java/com/carrito/saas/service/impl/ComboServiceImpl.java`
    (finding `R3-004` de #44). Ninguno cambia comportamiento; lo cubre la suite.

S2. El prefill del editor de combos (`frontend/src/pages/admin/ComboModal.tsx`)
    deja de cruzar por nombre como fallback: desde PR #52 el backend manda
    siempre `productId` en `ComboItemDTO`, y el cruce por nombre puede elegir
    el producto equivocado cuando dos comparten nombre (hallazgo
    `R1-combo-prefill-data-loss` de #44, WARNING; reiterado en `R3-002` de #44
    y `R3-1` de #52). Un item sin `productId` resuelve como producto no
    seleccionado (fila vacía), nunca como un pick silencioso por nombre. El
    test "resolves the picker by productId when two products share a name"
    sigue pasando; se agrega un caso: item sin `productId` no genera pick
    por nombre.

S3. Los modales de confirmación de `CategoriesTable.tsx` y `CombosTable.tsx`
    liberan su promesa pendiente al desmontar: si el componente se desmonta
    con un confirm abierto, la promesa resuelve `false` en lugar de quedar
    colgada (hallazgos `R3-002` de #46 sobre `CategoriesTable.tsx:121` y
    `R3-005` de #46 sobre `CombosTable.tsx:88-90`). Cobertura: test de
    frontend que desmonta con confirm pendiente y no deja promesa colgada
    (resuelta `false`).

S4. Hallazgos restantes (10) quedan en no-acción con motivo registrada en la
    tabla de abajo: la ubicación muestra el patrón ya correcto y testeado, o
    son sugerencias de ubicación/javadoc/estilo cuyo cambio no mejoraría el
    comportamiento observable y sí el ruido del diff.

## Tareas

T1 (S1) fix: limpiar los dos defectos de imports — commit `fix: clean up duplicated and jammed imports flagged by native review`.
T2 (S2) fix+test: prefill de combos sólo por productId — commit `fix: the combo editor never pre-fills by product name`.
T3 (S3) fix+test: cleanup de promesas de confirm al desmontar — commit `fix: pending confirm dialogs resolve false on unmount`.
T4 (S4) docs: registrar las no-acciones — mismo commit que este documento.

## Tabla de no-acciones (S4)

| Finding | Ubicación | Motivo de no-acción |
| --- | --- | --- |
| #44 R3-001 (W) | ComboRepository.java:28-38 | texto no recuperable; la query y su javadoc están correctos hoy y fijados por `PublicMenuComboActiveFilterTests` (carta vs panel, ambos lados testeados) |
| #44 R3-003 (S) | CombosTable.tsx:32-39 | el tipo extensor `AdminComboRow` está documentado en el propio archivo como decisión de alcance de unidad; moverlo amplía diff sin cambio observable |
| #46 R3-003 (W) | CategoryServiceImpl.java:158-162 | el delete usa finder scopeado, 409 explícito antes de la FK, sin cascade; fijado por `deleteCategoryWithProductsReturns409AndBothSurvive` |
| #46 R3-004 (W) | ComboServiceImpl.java:205-211 | ídem: finder scopeado por JWT, sólo cambia el flag, items intactos; testeado |
| #46 R3-006 (S) | CategoryDeleteAndComboLifecycleEndpointTests.java:236-260 | sugerencia sobre helpers de test; el repo ya compartió seeders en `bfad2d2`; refactorizar acá es ruido sin ganancia |
| #47 R3-cov (S) | PublicMenuComboActiveFilterTests.java:141-155 | texto no recuperable; el par carta-oculta/panel-muestra está cubierto; agregar variantes sin saber cuál pidió el revisor sería cobertura decorativa |
| #47 R3-scope (S) | ComboRepository.java:36-44 | ídem; la query activa replica exactamente el precedente `findPublicMenuProducts` |
| #50 R3-intl (W) | frontend/src/format.ts:13-19 | el locale es explícito (`"es-AR"`), no se ejecuta en SSR, y el formato está fijado dorado por `menu-prices.test.tsx`; el warning parece teórico |
| #50 R3-import-complete (S) | dashboard/format.ts:1-7 | ya no exporta `formatARS`; los importers usan `src/format.ts` (verificado por grep); la sugerencia ya está resuelta por PR #50 |
| #51 R3-001 (S) | ComboServiceImpl.java:75-78 | duplicado por nombre dentro del negocio; el `trim()` repetido es cosmético |
| #51 R3-002 (S) | ComboServiceImpl.java:110-117 | la forma compartida crear/actualizar es el diseño elegido; sugerencia de estructura |
| #54 R3-001 (W) | ProductServiceImpl.java:305-310 | texto no recuperable; `validarPrecio` documenta el contrato del null y cada ruta lo rechaza antes con su propio 400, testeado |
| #54 R3-002 (S) / #56 R3-placement (S) | ProductServiceImpl.java:280-293 | sugerencias de ubicación de llamadas de validación; el orden actual es el que pinnean los tests |

## Log

L1. "si atendelos" (2026-10-17): el usuario pide atender los 21 hallazgos del
    backlog de la serie de reviews nativos.
L2. Inventario: 21 findings (4-5 por PR; #48, #49, #53, #55 sin hallazgos).
    El texto completo no es recuperable del store; clasificación por lectura
    de código en cada ubicación. Resultado: 3 fixes (S1, S2, S3) y 13
    ubicaciones en no-acción con motivo (S4), que cubren los 21 findings
    (S2 atiende tres, S3 atiende dos).
