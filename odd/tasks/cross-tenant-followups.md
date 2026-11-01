# Feature: cross-tenant-followups

Cerrar los follow-ups de la familia multi-tenant que **siguen vivos** en
`develop`, y dejar de mentir en el documento que los registró.

`odd/tasks/cross-tenant-stock.md` dejó 14 pendientes. Verificado contra
`develop` (b3fbf20) antes de escribir código: **los pendientes 7 y 8 ya están
cerrados** (`33ebc56`, `2a65bdd`, `CrossTenantProductWriteIsolationTests`), y
del 13 quedó sólo la parte muerta. Este slice NO reabre lo cerrado: toma lo
que quedó abierto de verdad.

## Specs

S1. Las dos lecturas tenant-blind **sin llamadores** salen de
    `ProductRepository`: `findByActiveTrue` y `findByCategoryIdAndActiveTrue`.
    Con ellas se van los restos comentados de la misma familia
    (`//findByBusinessId`, `//findByBusinessIdAndActiveTrueAndStockGreaterThan`,
    `//findByActiveTrue`). Son exactamente la clase de método que el pendiente 13
    describe: código muerto que un futuro llamador usa sin pensar. La prueba de
    que no queda ningún llamador es el compilador, no un grep.

S2. `findByCategoryId` deja de existir como trampa tenant-blind. Su único uso
    son dos aserciones de `CrossTenantProductWriteIsolationTests` que comprueban
    que no se creó fila en la categoría ajena; pasan a un finder acotado al
    negocio **sin perder poder de detección**: si el `POST` creara la fila, la
    aserción sigue fallando. Se verifica por mutación, no por lectura del verde.

S3. `cancelOrder` exitoso **ya estaba cubierto** y este slice no agrega un test
    redundante: `CancelOrderEndpointTests` (`e014be4`, 6 tests) fija que cancelar
    un pedido `NEW` propio repone el stock exactamente y persiste `CANCELLED` con
    motivo, nota y `cancelledAt`; que un pedido ajeno da 404 con la fila ajena
    intacta; que `READY` da 409 sin mover stock ni estado; que un motivo
    inexistente da 400 sin efectos; y que un pedido de combo repone los
    componentes y saltea el root. El pendiente 10 se había declarado abierto con
    un `grep` por el nombre del método, que no ve un test que ejercita el
    endpoint. La verificación reemplaza al trabajo: no se escribe segunda
    cobertura.

S4. El control positivo de `TenantScopedQueryContractTests`
    (`findFullMenuCombosByIdsWithOwnBusinessReturnsComboWithComponents`) deja de
    renderizar `Combo`/`ComboProduct` completos al fallar: la aserción pasa a
    hacerse sobre ids, como ya hace el caso negativo. Motivo: el `toString()`
    bidireccional de Lombok `@Data` recursa infinito, así que un fallo se degrada
    a `StackOverflowError` en vez de mostrar el diff. Probado por mutación: con
    el predicado revertido el fallo tiene que ser legible.

S5. `odd/tasks/cross-tenant-stock.md` deja de describir un estado anterior a los
    PRs que lo cerraron: T4, T5 y T6 se tachan, y los pendientes 7, 8, 10, 13 y
    14 se cierran o se acotan con su evidencia (commit y clase de test). Se
    agrega arriba de la lista el estado verificado, porque una lista de
    pendientes sin fecha de verificación envejece como cualquier afirmación.

## Out

1. **No** se re-implementa lo ya cerrado: pendientes 7 (escrituras
   autenticadas) y 8 (`crearCombo`) se verificaron en código y en tests, y se
   documentan. Escribir ese slice de nuevo sería trabajo duplicado sobre código
   que ya está scopeado.
2. **No** se toca `existsByNameAndCategory`: resuelve la categoría por un finder
   ya acotado al negocio antes de llamarla, así que no es un agujero.
3. **No** se toca `cancellationReasonRepository.findById`: `CancellationReason`
   es global por diseño (no tiene `businessId`), así que no es cross-tenant.
4. **No** se cambia el comportamiento de `cancelOrder` hacia pedidos históricos
   con ítems ajenos (pendiente 7 de la versión previa). Si S3 revela que ese
   camino falla, se reporta y se decide aparte: es política de datos, no
   limpieza.

## Tasks

- [x] T1 (S1) chore: borrar las dos lecturas muertas tenant-blind + restos comentados.
- [x] T2 (S2) test: `findByCategoryId` reemplazada por finder acotado; mutación verificada.
- [x] T3 (S3) — sin trabajo: la cobertura ya existía (`CancelOrderEndpointTests`). Se verifica y se documenta, no se reescribe.
- [x] T4 (S4) test: control positivo de combos sobre ids, mutación legible.
- [x] T5 (S5) docs: corregir `cross-tenant-stock.md` con la evidencia real.

## Delivery

Base del slice: `develop` @ `b3fbf20`, rama `fix/cross-tenant-authenticated-writes`.
El aviso de tamaño del harness compara contra `origin/main`, que está muy atrás
de `develop`; contra la base real el slice son **3 commits** y ~50 líneas
autoradas, sin necesidad de partirse:

1. `refactor(repository)`: las tres lecturas tenant-blind sin llamadores + la
   aserción que las usaba.
2. `test(contract)`: el control positivo de combos sobre ids.
3. `docs(odd)`: este documento y la corrección de `cross-tenant-stock.md`.

Sin push ni PR: son decisiones del usuario.

Actualización: el usuario autorizó la entrega → **PR #98**
(`https://github.com/Francisco-Castillo/carrito-saas/pull/98`) contra `develop`,
label `type:chore`. Sin merge: sigue siendo decisión del usuario.

## Log

L1: Pedido del usuario (verbatim): "vamos a trabajar en paralelo mientras la
    otra sesion activa trabaja en lo suyo". La otra sesión tiene el worktree
    principal sucio con `lint-clean-develop` (frontend), así que este slice va
    en worktree aparte, rama `fix/cross-tenant-authenticated-writes`, y sólo
    toca Java + docs.

L2: Pedido del usuario (verbatim): "segui con tu recomendacion". La
    recomendación era "pendiente 7 + 13 en un slice propio". Al verificar
    contra `develop` el pendiente 7 (y el 8) ya estaban cerrados; se informó y el
    alcance se corrigió a lo que quedó abierto. La corrección se registra acá y
    no se hace en silencio.

L3: Segunda corrección de alcance, durante la ejecución: S3 pedía un test de
    `cancelOrder` porque el pendiente 10 lo daba por ausente. No lo estaba:
    `CancelOrderEndpointTests.java` existe con 6 tests desde `e014be4`. El
    pendiente se había escrito con un `grep` por nombre de método, que no ve un
    test que pega al endpoint. S3 se reescribe como verificación en vez de
    trabajo nuevo; no se escribe cobertura duplicada.

L4: Evidencia de verde en foco (post-edición, antes del reactor completo):
    `mvn -pl api -am -Dtest='TenantScopedQueryContractTests,CrossTenantProductWriteIsolationTests'`
    → `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`, reactor 8/8 `SUCCESS`
    en 22.8 s.

L5: Evidencia final.

    Reactor completo `mvn -B --no-transfer-progress verify` → `BUILD SUCCESS`,
    los 8 módulos `SUCCESS`, `api: Tests run: 499, Failures: 0, Errors: 0,
    Skipped: 4` (los 4 skips son preexistentes, ninguno de este slice).

    Matriz de mutación, cada mutante en una copia throwaway en `/tmp` (el árbol
    real nunca se tocó):

    | Mut | Qué se mutiló | Lo que se esperaba | Resultado literal |
    | --- | --- | --- | --- |
    | MA | `hasSize(1)` → `hasSize(0)` en la aserción de categoría ajena | que la aserción no sea vacua | `Expected size: 0 but was: 1 in: [Product@...]` — el finder acotado **sí** ve la fila de B en la categoría de B |
    | MB | el nombre esperado del control positivo propio | que la aserción vea la fila creada | `Expecting any element of: [Product@1f5cb417, Product@7463eb08] ... expected: "Producto Inexistente" but was: "Cross Tenant Write Own"` |
    | MC | `containsExactly(ownCombo.getId())` → `+ 1` | fallo **legible** | `Expecting actual: [4456L] to contain exactly ... but some elements were not found: [4457L]`, y **cero** ocurrencias de `StackOverflowError` en el log |
    | MD | `findByIdAndBusinessId` → `findById` en `crearProducto` | sensibilidad de comportamiento completa | `Status expected:<404> but was:<200>` |

    Los cuatro mutantes mueren. MA y MB prueban que la sustitución de
    `findByCategoryId` no perdió poder de detección (que era la única duda real
    de S2); MC prueba S4; MD prueba que la suite sigue atrapando el agujero de
    comportamiento, no sólo la forma de la aserción. El conteo de tests de este
    L5 queda corregido en L6.

L6: **Corrección de la evidencia de conteo (2026-11-01, post-PR).** L5 registra
    `api: 499 tests`. Ese número es real pero **está limitado por el entorno**, y
    presentarlo sin la salvedad era el mismo defecto que este slice critica en
    otros documentos.

    El CI de GitHub Actions sobre este HEAD da `api: Tests run: 516, Failures: 0,
    Errors: 0, Skipped: 0` (y `service: 6`). La diferencia se midió clase por
    clase contra los reportes surefire locales:

    | Clase | CI | Local |
    | --- | --- | --- |
    | `SpaRoutingHttpTests` | 21 tests, 0 skipped | 4 tests, 4 skipped |

    Causa: la clase está anotada `@EnabledIf("spaEntryPresent")`, que chequea
    `static/admin/admin.html`. Ese bundle **no se commitea** (`git ls-tree
    b3fbf20 -- api/src/main/resources/static` sólo trae `favicon.ico`) pero el CI
    lo construye con el perfil `frontend` de `api/pom.xml`. En un worktree nuevo
    el bundle no existe, la clase queda deshabilitada, surefire registra los **4
    métodos** como skipped y **no expande los 17 casos `@ParameterizedTest`**.
    De ahí 516 − 499 = 17.

    **Regla para adelante:** un conteo de tests en este repo es una medición de
    entorno, no de código. Sin el bundle del frontend, `mvn verify` da 499 y no
    es una regresión: es una clase deshabilitada con su expansión perdida. El
    número canónico es el del CI, o un `mvn clean verify` sobre un checkout con
    el bundle construido.
