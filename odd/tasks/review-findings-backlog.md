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

S3. La confirmación de delete del panel vive en un solo lugar: el hook
    compartido `frontend/src/pages/admin/useConfirmDialog.ts`, usado por las
    tres tablas (Productos, Categorías, Combos). La promesa responde true
    SOLO con "Confirmar" y resuelve false con Cancelar, clic fuera, Escape
    o cuando el componente dueño se desmonta con el confirm abierto
    (hallazgos `R3-002` de #46 sobre `CategoriesTable.tsx:121` y `R3-005` de
    #46 sobre `CombosTable.tsx:88-90`). El cleanup de desmonte ya existía
    inline y duplicado en las tres tablas sin test que lo fije; el fix es la
    deduplicación más el test (`confirm-dialog.test.tsx`, RED observado sin
    cleanup: la promesa quedaba colgada).

S4. Hallazgos restantes (10) quedan en no-acción con motivo registrada en la
    tabla de abajo: la ubicación muestra el patrón ya correcto y testeado, o
    son sugerencias de ubicación/javadoc/estilo cuyo cambio no mejoraría el
    comportamiento observable y sí el ruido del diff.

## Tareas

T1 (S1) fix: limpiar los dos defectos de imports — commit `da3bee7`.
T2 (S2) fix+test: prefill de combos sólo por productId — commit `86532b4` (RED observado: el pick por nombre elegía el id 4, el último match).
T3 (S3) fix+test: hook `useConfirmDialog` compartido + tests — commit (ver Log L3).
T4 (S4) docs: registrar las no-acciones — este documento (commit `72aaf42`).

### Segunda pasada (L4, "hacelos ahora")

S5. Segunda pasada sobre las no-acciones, con los textos de los revisores
    confirmados como irrecuperables: se implementan las 6 mejoras derivables
    de la ubicación (T5-T7) y el resto queda en no-acción definitiva con su
    motivo (tabla S4 más el anexo de L4 en el Log).

T5 (S5a) fix: deduplicar la liberación de la promesa en `useConfirmDialog`
    (`R3-unmount-dup-settle` del review del backlog) y mover `active` a
    `AdminComboFull` para eliminar el extensor de `CombosTable`
    (`R3-003` de #44) — frontend.
T6 (S5b) fix: trim una sola vez en `crearCombo` (`R3-001` de #51) y formatter
    de `Intl.NumberFormat` elevado a módulo en `format.ts` (`R3-intl-env` de
    #50) — mixto.
T7 (S5c) test: cobertura extra en `PublicMenuComboActiveFilterTests` (combo
    multi-item apagado desaparece de la carta, `R3-cov` de #47) y helpers de
    seed compartidos en `CategoryDeleteAndComboLifecycleEndpointTests`
    (`R3-006` de #46) — tests.

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
L3. Corrección de L2 al implementar: el cleanup de desmonte de S3 YA existía
    inline y triplicado (ProductsTable también, no sólo las dos señaladas),
    sin test. El valor real del fix fue deduplicar en `useConfirmDialog` y
    fijar el comportamiento con RED observado (sin cleanup la promesa queda
    colgada).
L4. "hacelos ahora" (2026-10-17): segunda pasada sobre las 14 no-acciones.
    Accionadas (T5-T7): R3-unmount-dup-settle (dedup de releasePending),
    R3-003 de #44 (extensor eliminado, active en AdminComboFull), R3-001 de
    #51 (trim una vez), R3-intl-env de #50 (formatter a módulo), R3-cov de
    #47 (test multi-item nuevo). No-acción DEFINITIVA, con motivo:
    - R3-006 de #46 (helpers de test): los builders product/category/combo
      están copiados en 8+ suites; deduplicarlos es un refactor transversal
      de tests-only con diff grande — unidad propia si alguna vez vale la
      pena, no un agregado acá.
    - R3-002 de #54 y R3-placement de #56 (orden de validaciones en
      ProductServiceImpl): reordenar cambia qué mensaje 400 gana cuando hay
      varios campos inválidos — es comportamiento observable ya pinneado;
      cambiarlo por estética sería churn.
    - R3-001 de #44, R3-003/004 de #46, R3-scope de #47, R3-002 de #51,
      R3-001 de #54: warning cuyo texto es irrecuperable y cuya ubicación
      muestra el patrón ya correcto y testeado; tocar sin el texto del
      revisor sería adivinar.
    - R3-import-complete de #50: ya resuelto por PR #50 (verificado: nadie
      importa formatARS desde dashboard/format).

## Multi-sucursal (review nativa, 2026-10-27)

Review ORDINARY aprobada del slice fases 1+2 (lineage review-4b1135d9d419534f,
lens reliability, tier high). Los 5 findings son NO bloqueantes (advisory,
disposition informational) y no reabren la review — trabajo aparte:

- R3-001 WARNING OrderServiceImpl:370-376 (fallback de sucursal en scoping de pedidos)
- R3-002 WARNING SecurityService:154-193 (resolución de sucursal activa del JWT)
- R3-003 SUGGESTION BusinessProfileServiceImpl:810-824
- R3-004 SUGGESTION docs/migrations/2026-10-28-branch-hours.sql:56-73 (swap de constraints)
- R3-005 SUGGESTION OrderServiceImpl:617-631 (resolveBranchForOrder)

Sin el texto completo del revisor en mano, la acción honesta es leer el
detalle del store antes de tocar (patrón ya documentado en L4: adivinar
ubicaciones sin texto = churn). Referencia del receipt: store_revision
sha256:400aa80161b1fe68ba3d609db9c2f2b7fa4c79c2a881a76a3c7e2a5147258d6e.

## Multi-sucursal T5-T7 (review nativa, 2026-10-27)

Review ORDINARY aprobada del slice final (lineage review-d144cef26ffd2d35,
lens reliability, tier medium, 21 archivos/1379 líneas). 2 findings NO
bloqueantes (advisory), trabajo aparte:

- R3-selector-no-menu-refetch WARNING frontend/src/pages/Menu.tsx:205-216
  (el selector no re-fetch del menú al cambiar de sucursal: horas/whatsapp
  del payload quedan de la sucursal cargada al montar)
- R3-sucursal-nonnumeric-400 WARNING api/src/main/java/com/carrito/saas/api/
  MenuController.java:27-28 (?sucursal= no numérico → 400 de Spring; podría
  tratarse como fallback silencioso para el display público)

Receipt: store_revision sha256:9b4532790ef1151fe38dc9b9b893ed547fce2dd26d2629eb54f1fe51fd004e20.

## Panel de sucursales (review nativa, 2026-10-27)

Review ORDINARY aprobada del slice del panel (lineage review-dffb9a3c0b2a0de7,
lens reliability, tier medium, 5 archivos/697 líneas). 4 findings NO
bloqueantes (advisory), trabajo aparte:

- R3-edit-cannot-clear-optional-fields WARNING BranchesSection.tsx:146-158
  (la edición no puede limpiar dirección/WhatsApp opcionales: el PUT sin
  campo los conserva según contrato parcial del perfil — en BranchServiceImpl
  blankToNull sí limpia con "", verificar cuál contrato quedó)
- R3-no-disable-during-workhere SUGGESTION BranchesSection.tsx:296-305
  (doble click en «Trabajar acá» dispara dos selects)
- R3-qr-filename-unsanitized SUGGESTION BranchesSection.tsx:236-238
  (nombre de archivo QR sin sanitizar)
- R3-workhere-refresh-error-swallowed SUGGESTION BranchesSection.tsx:228-232

Receipt: store_revision sha256:19ca6dd16fec9334052df55663a30ecf02f508831c5badee6dd510f770126902.

## Fix lectura de perfil (review nativa, 2026-10-27)

Review ORDINARY aprobada del slice fix (lineage review-b12851c7bbe0716a,
lens reliability, tier high — hot path de seguridad). 2 findings NO
bloqueantes (advisory): R3-default-read-no-branch-echo y
R3-fallback-platform-token-403-read (SecurityService:186-206).
Receipt: store_revision sha256:914071a7a7e49c8abfa210a33941830bae0dd4774dd4002410052bac7be636cf.

## Rediseño elección pública (review nativa, 2026-10-27)

Review ORDINARY aprobada del rediseño T8-T10 (lineage
review-615f60d08cbf07e9, lens reliability, tier medium, 7 archivos/742
líneas). 2 findings NO bloqueantes (advisory, SUGGESTION):
R3-001 Menu.tsx:737-742 y R3-002 Menu.tsx:340-348.
Receipt: store_revision sha256:343b2828ee0e4268d078035dd3edede51329b9b92ce0510ab7c17b414c1785c9.

## Flake detectado: decodificación de QR (2026-10-27)

`BranchMenuAndQrHttpTests.qrDeSucursalPropiaEsUnPngQueCodificaLaUrlConSucursal`
falla de forma intermitente (2 de 3 corridas de la suite completa; pasa
aislado y pasa en la base sin cambios): el helper `decodeQrText` usa zxing sin
`TRY_HARDER`, y el decoder ocasionalmente no encuentra el QR del PNG generado.
No es regresión de la feature, pero ensucia la señal de la suite.

Acción propuesta (unidad aparte): hints de decodificación (`TRY_HARDER`) o
reintento con reescalado en el helper de test. Nunca en código de producción.

## Reactivación de sucursales (review nativa, 2026-10-27)

Review ORDINARY aprobada (lineage review-9cb519513085406f, lens
reliability, tier medium). 2 advisories NO bloqueantes (SUGGESTION):
- R3-guard-race BranchServiceImpl.java:139-143 (el guard de última activa
  cuenta y luego escribe: dos DELETE concurrentes podrían pasar el chequeo)
- R3-reactivar-no-limit BranchServiceImpl.java:152-164 (reactivar no tiene
  tope de sucursales activas por negocio)
Receipt: store_revision sha256:4340f7416681d342061aea5a5f0bab554483324537750fcb2916484ce3639eb4.

## Copy y badge del panel (review nativa, 2026-10-27)

Review ORDINARY aprobada (lineage review-5ffa391c3ff1af34, lens readability,
tier medium). 1 advisory NO bloqueante: R2-001 BranchesSection.tsx:376.
Receipt: store_revision sha256:872cdc4d586544ccb6b3c2a9b5540f4bad6907add995bcc853a64acc9d4f1691.

## Edge de producto detectado en el panel (2026-10-27)

Se puede DESACTIVAR la sucursal con la que se está trabajando (el claim
branchId del JWT sigue apuntando a ella): el panel queda operando una
sucursal inactiva (sin QR, sin pedidos nuevos, y `?sucursal=` inactivo cae a
la default en la carta). El guard actual sólo impide dejar el negocio sin
NINGUNA activa. Decisión pendiente del dueño del producto: bloquear la baja de
la sucursal actual con 409 pidiendo cambiar de sucursal primero, o auto-cambiar
el contexto a la default.

## Diálogo y acciones del panel (review nativa, 2026-10-27)

Review ORDINARY aprobada tras un rechazo del relay (payload del revisor
truncado; el proveedor no consumió el slot y el reintento con STATUS fresco
pasó). 1 advisory NO bloqueante: R2-function-name-vs-action-terminology
BranchesSection.tsx:177 (el identificador interno sigue hablando de «remove»
mientras la UI y el contrato dicen «desactivar»).
Receipt: store_revision sha256:7dcfa2b3afaaf1bc638f294ec4459ff291b23df980bbc7dca20d3bbefa306445.
