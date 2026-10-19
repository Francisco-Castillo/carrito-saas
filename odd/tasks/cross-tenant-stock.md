# Feature: cross-tenant-stock

## Objective

Que ningún negocio pueda modificar el stock, el carrito ni la caja de otro negocio a través del canal público de pedidos.

## Problem

`POST /api/orders/menu/{slug}` es anónimo por diseño (`SecurityConfig.java:77`) y carga los productos del pedido **sin filtrar por negocio**. Cualquier persona, sin token, puede descontar stock ajeno. Los hechos de abajo están verificados por lectura del código, con la línea exacta, y el agujero fue **observado en ejecución** (ver Evidence).

### Hecho 1 — la carga de productos no tiene filtro de negocio

`repository/src/main/java/com/carrito/saas/repository/jpa/ProductRepository.java:42-44` (estado pre-fix)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id IN :ids")
List<Product> findAllByIdInForUpdate(@Param("ids") List<Long> ids);
```

No hay predicado de negocio. Los ids son numéricos y secuenciales, así que son adivinables.

### Hecho 2 — ninguna de las dos escrituras de stock tiene barrera de tenant

`ProductRepository.java:59-65` (decremento, del camino de crear pedido)

```java
UPDATE Product p
SET p.stock = p.stock - :quantity
WHERE p.id = :productId
AND p.stock >= :quantity
```

`ProductRepository.java:68-76` (reposición, del camino de cancelar pedido)

```java
UPDATE Product p
SET p.stock = p.stock + :quantity
WHERE p.id = :productId
```

Ninguna de las dos tiene predicado de negocio. La única guarda del decremento es el stock suficiente; la reposición no tiene ninguna guarda en absoluto.

Call sites (los tres únicos del repo, verificado por grep): `OrderServiceImpl.java:164` y `:220` (producto normal y componente de combo) para el decremento, y `OrderServiceImpl.java:333` para la reposición del cancelamiento.

**Esto no es una asimetría suelta: es el patrón del camino de pedidos.** Las tres operaciones de producto que ese camino usa —`findAllByIdInForUpdate`, `decrementStock`, `incrementStock`— son tenant-blind. Lo único scopeado por negocio son los combos (Hecho 3) y las listas del menú (`ProductRepository.java:23,93`, `findByCategory_Business_Id*`), que no participan de la escritura.

### Hecho 3 — el camino de combos sí está scopeado (la asimetría que delata el bug)

`OrderServiceImpl.java:143`

```java
combos = comboRepository.findFullMenuCombosByIds(comboIds, business.getId());
```

`ComboRepository.java:35,37` hace `AND cat.business.id = :businessId`. Es decir: **el autor sabía que había que scopear por negocio, y lo hizo en los combos, no en los productos.**

**Pero el scope de combos es incompleto** (lo encontró la verificación independiente, ver Hecho 7): la query filtra por la categoría *del combo*, y el `JOIN FETCH cp.product` **no tiene predicado sobre el producto componente**.

### Hecho 4 — nadie valida la pertenencia

`OrderServiceImpl.java:133` carga los productos y `:160-161` sólo verifica que existan:

```java
Product product = productMap.get(dto.getProductId());
if (product == null) {
    throw new RuntimeException("Producto no existe");
}
```

No hay comparación contra `business.getId()` en ningún punto del método. El comentario tampoco lo sugiere.

### Hecho 5 — el efecto es triple, y el precio también es ajeno

`OrderServiceImpl.java:174` toma el precio **del producto ajeno**:

```java
item.setPrice(product.getPrice());
```

Entonces un anónimo que hace `POST` a la carta de *su* negocio con el `productId` de *otro* logra:

1. **Descontar stock ajeno** (`:164`), que es la integridad de inventario de la víctima.
2. **Pagar el precio de la víctima** (`:174`). Si la víctima vende más barato, el atacante subpaga en su propio pedido.
3. **Ensuciar los pedidos y la analítica de su propio negocio**: el pedido se persiste con `business_id` del atacante pero con `productName`/`price` de la víctima. Dashboard, trends y predicciones de la víctima no lo ven — es data basura en el negocio equivocado.

Y de la víctima sólo se puede ver el efecto indirecto: su stock baja sin que exista un pedido suyo que lo explique. Eso es un descuadre de inventario sin trazabilidad.

### Hecho 6 — la suite modelaba un estado que la aplicación no puede producir (tres veces)

`ProductServiceImpl.java:190` exige categoría (`"Categoria requerida"`, `ErrorType.VALIDATION`) y `:127-128` obliga a que la fila de categoría exista. Es decir: **ningún producto sin categoría puede nacer por la API.**

Sin embargo **tres** fixtures siembran productos sin categoría, escribiendo directo por repositorio y salteando el servicio:

| Fixture | Qué siembra mal |
| --- | --- |
| `PublicMenuOrderHttpTests.anonymousBrowserOrderIsAcceptedAndPersistedWithoutTable` (`:124-130`) | El producto no tiene categoría. El negocio existe, pero el producto no está ligado a él |
| `PublicMenuOrderHttpTests.comboOrderIsResolvedAndDecomposedThroughPublicMenuEndpoint` | La categoría existe para el combo (`:195-198`) pero los componentes (`:201`, `:209`) no la tienen |
| **`CreateOrderWithoutTableTests.java:74-82`** | **El producto no tiene categoría.** Es el tercero, y **no se detectó en la implementación: lo encontró la verificación independiente, con la suite en rojo** |

Los tres pasaban porque la carga de productos era ciega **a la vez** al tenant y a la categoría. Un producto sin categoría no tiene negocio, así que el filtro lo deja fuera; y ya estaba fuera del menú público, cuya query (`findByCategory_Business_IdAndActiveTrueAndStockGreaterThan`) hace un inner join implícito sobre categoría.

**La conclusión es que la corrección es de los fixtures, no del filtro**: modelaban un estado que el servicio prohíbe y que el menú ya excluía. Si hubiera hecho falta cambiar una aserción, el cambio de comportamiento sería más grande que lo diagnosticado y habría que parar; no hizo falta en ninguno de los tres.

**Lección operativa:** el primer pase corrigió sólo los dos fixtures del archivo que la suite señaló. Buscar el patrón, en vez de apagar el incendio que se ve, habría encontrado el tercero. La verificación independiente lo hizo corriendo la suite completa, que es exactamente lo que el escritor tenía prohibido correr.

### Hecho 7 — el camino de combos es tenant-safe por suposición, no por construcción

Encontrado por la verificación independiente. `ComboRepository.java:35-40`:

```java
... WHERE c.id IN :ids
AND cat.business.id = :businessId        // la categoría DEL COMBO, sí
// JOIN FETCH cp.product                  // el producto COMPONENTE, sin predicado
```

Con un combo de A y un componente de B, la ejecución **llega hasta `OrderServiceImpl.java:223`** y sólo la frena el predicado nuevo de `decrementStock`. La verificación lo observó: `stockB = 7`, sin pedido persistido. Es decir: **el scope de combos por sí solo no alcanza; hoy lo sostiene la escritura.**

Escalar el predicado a la lectura sería peor que no hacerlo: filtrar componentes haría que un combo cargue **parcialmente**, con lo que se lo cobraría mal en silencio. El arreglo correcto es en la **creación** del combo, y ahí hay dos defectos (Hecho 8).

### Hecho 8 — `crearCombo` no setea la categoría ni valida los componentes

`service/src/main/java/com/carrito/saas/service/impl/ComboServiceImpl.java:38-61`:

```java
Combo nuevoCombo = new Combo();
nuevoCombo.setName(combo.getName());
nuevoCombo.setPrice(combo.getPrice());
nuevoCombo.setActive(true);          // ← nunca llama a setCategory
```

`Combo.java:33` declara `@JoinColumn(name = "category_id", nullable = false)`. Con `check_nullability: true` y `ddl-auto: update`, **un combo creado por la API no puede persistirse**. Consecuencia: los combos sólo existen por `INSERT` directo a la base, y por eso el Hecho 7 tiene hoy exposición nula — su estado sólo es alcanzable por fuera de la API.

Y en el mismo método:

```java
cp.setProduct(productRepository.findById(dto.getProductId()).orElseThrow());
```

**No valida que el producto componente sea del negocio del llamador.** Es el hermano en tiempo de creación del agujero de este slice: un autenticado puede armar un combo con el producto de otro negocio.

### Lo que sí funciona (verificado, no asumido)

- El decremento es atómico bajo lock pesimista (`decrementStock` con `WHERE stock >= :quantity`), así que no hay sobreventa por concurrencia. La verificación probó que el piso de stock está realmente enforced: quitarlo hace fallar la aserción de sobreventa.
- La ruta de combos está scopeada **por la categoría del combo**, así que un `comboId` ajeno es rechazado hoy (ver Hecho 7 para el límite de esa afirmación).
- Los precios y el costo se re-leen del servidor, nunca del cliente (`:174`), así que el ataque **no** es "mandar un precio arbitrario"; es elegir de qué negocio tomás el precio.
- El endpoint exige `customerName`, `orderType`, `paymentMethod` y `items` no vacíos (`OrderServiceImpl.java:76-93`), así que el ataque necesita un cuerpo bien formado. No es una barrera útil.
- **No hay fuga de datos ajenos al cliente por mensajes de error.** `GlobalExceptionHandler.handleGeneral` (`api/.../exception/GlobalExceptionHandler.java:55-70`) descarta `ex.getMessage()` y devuelve el literal `"Ocurrió un error inesperado"`. Verificado con cuatro sondas: el body siempre trae el literal. (El nombre de un producto ajeno **sí** aparece en el log del servidor por el camino de combos — ver pendiente 9.)

## Why

El sistema es multi-tenant y `businessId` es lo único que separa un negocio de otro. Ese `businessId` sale siempre del JWT para el lado de la cocina (`securityService.getCurrentBusinessId()`), pero el canal público no tiene JWT: su única ancla es el `slug` de la URL. Cuando ese canal acepta un id de recurso sin verificar que el recurso pertenezca al negocio del slug, el aislamiento multi-tenant deja de existir en el punto que escribe inventario.

Se arregla **antes** del slice de WhatsApp por un motivo concreto: el normalizador de texto libre va a emitir `productId` a partir de lo que el cliente escriba. Si el agujero sigue abierto, la feature nueva hereda el agujero y además lo hace más alcanzable, porque ya no hace falta conocer un id: alcanza con nombrar un plato que otro negocio venda.

## Scope

**In**

- `repository/src/main/java/com/carrito/saas/repository/jpa/ProductRepository.java` — carga, decremento y reposición scopeados por negocio; y eliminación de la query muerta tenant-blind.
- `service/src/main/java/com/carrito/saas/service/impl/OrderServiceImpl.java` — pasar el tenant a las cuatro llamadas: `business.getId()` en `:133`, `:164` y `:220`, y el `businessId` del token (`securityService.getCurrentBusinessId()`, `:317`) en `:333`, donde **no** existe la variable `business`. Es el mismo ancla que ya usa `findByIdAndBusinessId(orderId, businessId)` en `:319`.
- `api/src/test/java/com/carrito/saas/CrossTenantStockIsolationTests.java` — nuevo, el contrato de aislamiento observado por HTTP.
- `api/src/test/java/com/carrito/saas/ProductStockTenantScopeTests.java` — nuevo, la defensa en profundidad a nivel de las escrituras.
- `api/src/test/java/com/carrito/saas/TenantScopedQueryContractTests.java` — nuevo, el scope de las **lecturas** (lo que hace detectable T2 y AC5). Ver T4.
- `api/src/test/java/com/carrito/saas/PublicMenuOrderHttpTests.java` — corrección de dos fixtures que siembran productos sin categoría (Hecho 6). Sin cambios de aserciones.
- `api/src/test/java/com/carrito/saas/CreateOrderWithoutTableTests.java` — corrección del tercer fixture de la misma familia (Hecho 6). Sin cambios de aserciones.
**Out** (decisiones explícitas, cada una su motivo)

1. **No** se cambia el código HTTP del rechazo. `OrderServiceImpl` tira `RuntimeException` y `GlobalExceptionHandler` mapea todo lo que no es `BusinessException` a **500**. Un producto ajeno va a seguir dando 500, igual que un producto inexistente hoy. Arreglar el 500-como-400 es un slice propio que toca todo el intake.
2. **No** se agrega unicidad ni índice a `Business.phone`, ni el mapeo teléfono→negocio. Es del slice de WhatsApp.
3. **No** se cierra el canal anónimo ni se agrega autorización por rol. El canal anónimo es la feature, no el bug.
4. **No** se toca la lógica de combos. **La razón es concreta y está en el Hecho 7 y el Hecho 8**: filtrar los componentes en la lectura del combo haría que el combo cargue parcialmente y se cobre mal en silencio, así que el arreglo correcto es en la creación (categoría obligatoria + validación de pertenencia de componentes). Eso toca `ComboServiceImpl`, que no tiene relación con el canal anónimo, y va en su propio slice.
5. **No** se tocan las dos escrituras cross-tenant **autenticadas** que encontró la verificación (`PUT /api/{id}` y `POST /api/productos`, ver pendiente 7). Son de la misma familia pero no son anónimas, y el alcance de este slice es el canal público.
6. **No** se toca `OrderStatus` ni el broadcast al WebSocket.
7. **No** se audita el resto del repositorio query por query. Ver pendiente 2.

## Constraints

- **Strict TDD activado.** El test que falla se escribe y se corre **antes** del fix, con evidencia RED literal pegada en este documento.
- Runner completo, lo corre el padre, no el escritor: `mvn -B --no-transfer-progress verify`.
- **El escritor no corre la suite completa.** Lección de un slice anterior (el escritor que corre todo tarda y puede morir en silencio) — y ahora con una segunda lección medida: el escritor que sólo corre sus tests **no ve** la regresión que rompe en otro archivo (Hecho 6, tercer fixture). El padre tiene que correr la suite completa, sin excepción.
- Patrón de test obligatorio (es el del repo, no inventar otro): `@SpringBootTest` + `@Transactional` para rollback de fixtures, y MockMvc armado a mano con `MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain)`. `@AutoConfigureMockMvc` **no** está disponible (Spring Boot 4 lo movió a `spring-boot-webmvc-test`, que `spring-boot-starter-test` no trae). Ver `PublicMenuOrderHttpTests.java` y `AnonymousMenuOrderSecurityTests.java`.
- `businesses.id` se asigna a mano (sin `@GeneratedValue`), así que los fixtures usan ids fijos altos y no colisionan. Ver `PublicMenuOrderHttpTests.java` (`BUSINESS_ID = 987_654_322L`).
- La ruta a `business` desde un producto es `product.category.business.id`.
- Sin commits: la autorización de commit es una decisión aparte y explícita.

## Tasks

- [x] **T1 — Test RED del aislamiento cross-tenant.** `CrossTenantStockIsolationTests.java`: siembra **dos** negocios, cada uno con su categoría y su producto con stock conocido; hace un `POST` anónimo a `/api/orders/menu/{slug-del-negocio-A}` con el `productId` del negocio B; exige que el stock de B **no cambie**, que no se cree ningún pedido, y que el stock de A tampoco cambie. El test lee el stock por repositorio con el contexto de persistencia limpio, para que sea un `SELECT` real contra PostgreSQL. **Límite honesto de este test, medido por la verificación**: es un test de *no-efecto*, así que pasa si la request se rechaza por **cualquier** razón. Es sensible al bug completo (la mutación de las tres queries reprueba con el mensaje RED original), pero **no** aísla el predicado de la carga.
- [x] **T2 — Scopear la carga de productos por negocio.** Reemplazar `findAllByIdInForUpdate(ids)` por una variante que además filtre `AND p.category.business.id = :businessId`, y pasarla desde `OrderServiceImpl.java:133` con `business.getId()`. Efecto buscado: un producto ajeno **no entra al mapa**, así que el `product == null` de `:161` corta antes de cualquier decremento. **Sin T4 no tiene test que lo fije**: T3 bloquea la escritura por su cuenta y el test de T1 sólo mira el efecto.
- [x] **T3 — Defensa en profundidad en las escrituras de stock.** Scopear por negocio **las dos** escrituras: `decrementStock` y `incrementStock` (`AND p.category.business.id = :businessId`), con `ProductStockTenantScopeTests.java` propio: llamar a cada una con un id ajeno exige **0 filas afectadas** y stock ajeno intacto. Motivo: T2 hace el agujero inalcanzable desde el camino de pedidos, pero la escritura atómica es la última línea de defensa y no debería depender de que el llamador haya cargado el producto antes. **Se scopean las dos y no sólo el decremento a propósito**: dejar una escrita y la otra no es exactamente la asimetría que produjo este bug (Hecho 3).
- [ ] **T4 — Contrato de scope de las lecturas (el que hace detectable T2 y AC5).** `TenantScopedQueryContractTests.java`: a nivel repositorio, sin HTTP. (a) `findAllByIdInForUpdate(List.of(ownId, foreignId), businessAId)` devuelve el propio y **no** el ajeno, con control positivo de que el propio sí vuelve; (b) `findFullMenuCombosByIds(List.of(comboOfA), businessBId)` devuelve **vacío**, con control positivo de que con `businessAId` sí lo devuelve. Cada aserción debe **fallar al revertir su predicado** — eso es la aceptación del task, no que esté verde. Motivo: la verificación independiente probó que hoy **AC4 no tiene ninguna cobertura** (ningún test llama a `findAllByIdInForUpdate`) y que el test de AC5 no detecta que se quite el scope de combos. Sin T4, dos de las tareas de este slice no tienen evidencia propia.
- [ ] **T5 — Tercer fixture de la familia Hecho 6.** `CreateOrderWithoutTableTests.java:74-82` siembra un producto sin categoría, y por eso la suite quedó en rojo con este slice (`Producto no existe` en `OrderServiceImpl.java:161`). Crear una `Category` del negocio sembrado y ligarla con `product.setCategory(...)`. **Sin cambios de aserciones**; si alguna necesita cambiar, parar y reportar. Después de este task, hacer un grep de patrones (`new Product()` sin `setCategory` cercano) en vez de confiar en que no hay un cuarto.
- [ ] **T6 — Eliminar la query muerta tenant-blind.** `ProductRepository.java:34` declara `List<Product> findAllByIdIn(List<Long> ids)`, **sin llamadores** (verificado por grep) y sin predicado de negocio. Es exactamente el tipo de método que un futuro llamador usa sin pensar. Se elimina. Si el compilador revela un llamador, reportar en vez de adivinar.

## Acceptance criteria

| ID | Criterio |
| --- | --- |
| AC1 | `POST` anónimo a la carta del negocio A con un `productId` del negocio B → el stock de B queda **exactamente igual** |
| AC2 | El mismo `POST` no persiste ningún pedido, ni con `business_id` de A ni de B |
| AC3 | Un `productId` propio sigue funcionando sin regresión: pedido creado con `status = NEW`, stock propio decrementado, `total` correcto |
| AC4 | Existe un test **commiteado** que llama a `findAllByIdInForUpdate` con un id ajeno y exige que no lo devuelva, y que **falla si se revierte el predicado** — cumplido por T4, probado por mutación |
| AC5 | `findFullMenuCombosByIds` con el negocio equivocado devuelve vacío, y el test **falla si se revierte el predicado de combos** — cumplido por T4, probado por mutación |
| AC6 | `decrementStock` con un `productId` ajeno afecta **0 filas** y deja el stock ajeno igual (T3) |
| AC7 | `decrementStock` con un `productId` propio sigue decrementando y sigue respetando `stock >= :quantity` (sin sobreventa) |
| AC8 | `incrementStock` con un `productId` ajeno afecta **0 filas** y deja el stock ajeno igual (T3) |
| AC9 | `incrementStock` con un `productId` propio sigue reponiendo. **Límite declarado**: el repositorio está cubierto, pero **no** hay test de un `cancelOrder` exitoso que reponga stock (ver pendiente 10) |
| AC10 | `mvn -B --no-transfer-progress verify` verde, con **todos** los módulos del reactor y sin `SKIPPED` por falla previa |
| AC11 | `PublicMenuOrderHttpTests`, `CreateOrderWithoutTableTests` y `AnonymousMenuOrderSecurityTests` verdes. Los dos primeros con fixtures corregidos y **cero aserciones modificadas** |
| AC12 | Cada predicado de tenant agregado por este slice tiene al menos un test que **falla cuando ese predicado se revierte**, probado por mutación (no basta que el test esté verde) |
| AC13 | El camino de pedidos no tiene ninguna lectura de productos alcanzable sin predicado de negocio. **Cumplido en sustancia**: `findAllByIdIn` eliminada y sin llamadores rotos. **PARCIAL en la letra**: quedan `existsByNameAndCategory` (`ProductServiceImpl.java:134`) y el `findById` heredado (`:155`, y `ComboServiceImpl.java:51`) alcanzables sin predicado, pero en la superficie **autenticada** ya declarada fuera de alcance (Out #5) |

## Progress

| Task | Estado | Evidencia |
| --- | --- | --- |
| T1 | hecho | RED literal: `Tests run: 3, Failures: 1` — `expected: 7 but was: 5`. La mutación de las tres queries reproduce ese mensaje exacto |
| T2 | **hecho, sin test propio** | `Tests run: 3, Failures: 0` — pero la verificación probó que **revertir sólo este predicado deja los tests en verde**. La fila anterior de este documento atribuía ese verde a T2 y era **incorrecta**. Queda pendiente T4 |
| T3 | hecho | RED por error de compilación (6 sitios: la firma de 3 argumentos no existía) → `Tests run: 4, Failures: 0`. Mutación-sensible en los dos predicados |
| — | hecho | Dos fixtures de `PublicMenuOrderHttpTests` corregidos: RED observado `2 fallas / expected:<200> but was:<500>`, +12/−0, sólo seed |
| T4 | hecho | 4 tests. Mutación (a) —carga revertida en copia `/tmp`— → `Tests run: 4, Failures: 2` (aserciones 1 y 2). Mutación (b) —combos— → `Failures: 1` (aserción 3). Los dos controles positivos sobreviven |
| T5 | hecho | RED observado pre-fix en el repo real: `Tests run: 1, Errors: 1 — Producto no existe`. Corrección sólo del seed; grep del patrón sin cuarto caso |
| T6 | hecho | `findAllByIdIn` eliminada; la compilación no reveló llamadores |
| — | **PASS WITH FINDINGS** | Verificación 1: **FAIL**, dos bloqueantes (F1, F2), cerrados por T4/T5/T6. Verificación 2: `BUILD SUCCESS`, reactor 8/8 **sin `SKIPPED`**, `api: Tests run 44, Failures 0, Errors 0, Skipped 0`. Matriz de mutación completa: los cuatro predicados tienen test que los atrapa. Sin bloqueantes |

## Evidence

El escritor corrió **sólo sus propios tests**; la suite completa (`AC10`) la corrió el padre, vía verificación independiente.

### RED (literal)

**T1 — el agujero, observado** (`-Dtest=CrossTenantStockIsolationTests`)

```text
Tests run: 3, Failures: 1, Errors: 0
foreignProductOrderLeavesForeignBusinessUntouched:231 AssertionFailedError:
  [B's stock must be exactly unchanged when its productId is ordered through A's public menu]
  expected: 7 but was: 5
```

El pedido anónimo **descontó stock del negocio ajeno** (7 → 5). El control positivo y la sonda de `comboId` ajeno pasaron en la misma corrida, exactamente como predice el diagnóstico de asimetría: lo que estaba roto era el camino de productos, no el de combos.

**T3 — el contrato scopeado no existía** (`-Dtest=ProductStockTenantScopeTests`)

```text
COMPILATION ERROR (6 sitios)
method decrementStock ... required: java.lang.Long,java.lang.Integer
                          found: java.lang.Long,int,java.lang.Long
```

**Hecho 6 — los fixtures sin categoría** (`-Dtest=PublicMenuOrderHttpTests`, corrida **pre-fix**)

```text
Tests run: 2, Failures: 2, Errors: 0
anonymousBrowserOrderIsAcceptedAndPersistedWithoutTable:147 AssertionError:
  Status expected:<200> but was:<500>       (log: RuntimeException error=Producto no existe)
comboOrderIsResolvedAndDecomposedThroughPublicMenuEndpoint:253 AssertionError:
  Status expected:<200> but was:<500>       (log: RuntimeException error=Stock insuficiente en combo: Combo Test Burger)
```

Las dos fallas son las queries scopeadas rechazando fixtures inalcanzables. **El razonamiento del Hecho 6 quedó confirmado por ejecución, no por inferencia.**

### GREEN del escritor

| Corrida | Resultado |
| --- | --- |
| `CrossTenantStockIsolationTests` tras T2 | `Tests run: 3, Failures: 0, Errors: 0` — BUILD SUCCESS |
| `ProductStockTenantScopeTests` tras T3 | `Tests run: 4, Failures: 0, Errors: 0` — BUILD SUCCESS |
| `PublicMenuOrderHttpTests` tras corregir los fixtures | `Tests run: 2, Failures: 0, Errors: 0` — BUILD SUCCESS |

**Ninguna aserción cambió** para llegar al verde. El escritor tenía instrucción explícita de parar si alguna necesitaba cambiar. No hizo falta. El diff de `PublicMenuOrderHttpTests` es **+12/−0 y sólo de seed**.

### RED por mutación (T4) — la evidencia que faltaba

El test nuevo no puede ser RED contra el código actual: los predicados ya existen, así que pasa de entrada. Por eso la aceptación de T4 **no es estar verde** sino fallar al revertir el predicado. Las mutaciones se aplicaron sobre una copia en `/tmp`, nunca sobre el repo real:

| Mutación (en `/tmp`) | Aserciones | Resultado literal |
| --- | --- | --- |
| predicado de carga (`findAllByIdInForUpdate`) revertido | 1 y 2 | `Tests run: 4, Failures: 2` — `[a foreign-only load must return empty for business A] Expecting empty but was: [Product@...]`; `[A's own product must be returned under lock] Expecting actual: [305L, 306L] to contain exactly: [305L] but some elements were not expected: [306L]` |
| predicado de combos (`findFullMenuCombosByIds`) revertido | 3 | `Tests run: 4, Failures: 1` — `[a combo of A must not be resolvable through B's menu lookup] Expecting empty but was: [85L]` |
| los dos controles positivos | 4 | **sobreviven en las dos mutaciones**, que es lo que los hace controles y no relleno |

Con esto, las tres capas de defensa del slice tienen cada una un test que **falla cuando esa capa se quita**: carga (T4), decremento y reposición (T3). Antes de T4, la capa de carga no tenía ninguno, y el verde de las otras dos la tapaba.

**Gotcha registrado**: la primera corrida de la mutación (b) salió como `StackOverflowError`, no como falla de aserción. Causa: Lombok `@Data` genera `toString()` sobre `Combo` ↔ `ComboProduct`, y AssertJ renderiza el valor real al armar el mensaje de falla → recursión infinita. Se cambió la aserción a `.extracting(Combo::getId)`, que es **semánticamente equivalente para una aserción de vacío** y además sigue atrapando la mutación (el `[85L]` de arriba lo prueba). Queda anotado en el test porque le va a pasar a cualquier aserción futura que renderice entidades `Combo` completas.

### Cierre de los bloqueantes

| Task | Cómo se cerró |
| --- | --- |
| F1 (AC10 rojo) | T5: `CreateOrderWithoutTableTests` con el RED observado primero en el repo real (`Tests run: 1, Errors: 1 — Producto no existe`), después corrección sólo del seed. El grep del patrón encontró 8 `new Product()` en 4 archivos, todos con `setCategory` cerca. **La re-verificación corrigió el conteo: son 10 en 5 archivos, todos con categoría.** La conclusión no cambia (no hay un cuarto caso), pero el número del escritor era erróneo |
| F2 (T2 sin test) | T4: `TenantScopedQueryContractTests`, 4 tests, con la prueba de mutación de arriba |
| F3 (AC5 nominal) | T4: la aserción 3 sobre `findFullMenuCombosByIds`, mutación-sensible |
| F8 (query muerta) | T6: `findAllByIdIn` eliminada; la compilación no reveló llamadores |

Verde tras el cierre, sobre el repo real: `TenantScopedQueryContractTests` 4/4, `CrossTenantStockIsolationTests` 3/3, `ProductStockTenantScopeTests` 4/4, `CreateOrderWithoutTableTests` 1/1 — las cuatro juntas, 11/11, BUILD SUCCESS.

### Verificación independiente — **FAIL** (agente distinto del escritor, read-only)

La seguridad **está cerrada**, pero el slice **no es entregable**.

**1. AC10 en rojo, y la causa es este slice.** `mvn -B --no-transfer-progress verify` → `BUILD FAILURE`; `api: Tests run: 40, Failures: 0, Errors: 1, Skipped: 0`; `jobs` quedó en `SKIPPED` porque el reactor cortó.

```text
CreateOrderWithoutTableTests.createOrderWithoutTableIsCreatedWithStatusNew <<< ERROR!
java.lang.RuntimeException: Producto no existe
  at com.carrito.saas.service.impl.OrderServiceImpl.createOrder(OrderServiceImpl.java:161)
```

**Causalidad probada por mutación, no por lectura**: en una copia en `/tmp` con los tres predicados revertidos a la semántica pre-slice, ese mismo test queda `Failures: 0, Errors: 0` y los **únicos** 3 rojos son los tests nuevos. Es decir: el test estaba verde antes del slice y este slice lo rompió. Es el tercer fixture del Hecho 6.

**2. Sensibilidad por mutación — una de las tres pruebas pedidas no pasa.** Cada mutación aplicada sólo a la copia en `/tmp`, con los otros predicados intactos:

| Mutación | Predicado quitado | Debería atraparlo | Resultado literal |
| --- | --- | --- | --- |
| (a) | carga (`findAllByIdInForUpdate`) | `CrossTenantStockIsolationTests` | `Tests run: 3, Failures: 0 … BUILD SUCCESS` — **QUEDA VERDE** |
| (b) | `decrementStock` | `ProductStockTenantScopeTests` | `Failures: 1 … expected: 0 but was: 1` — correcto |
| (c) | `incrementStock` | `ProductStockTenantScopeTests` | `Failures: 1 … expected: 0 but was: 1` — correcto |
| M0 | las tres (estado pre-fix real) | ambas clases | `Failures: 3` con `expected: 7 but was: 5` — **el RED registrado, reproducido exacto** |
| M6 | scope de combos | la sonda de AC5 | `Failures: 0` — **QUEDA VERDE**, AC5 es nominal |
| M7 | `AND p.stock >= :quantity` | aserción de sobreventa | `Failures: 1` — el piso de stock está de verdad enforced |
| M8 | camino feliz (`businessId + 1`) | control positivo | `Failures: 1` — el control positivo es real, no vacuo |

La mutación se comprobó compilada, no salteada (la query mutante aparece en el `.class` y el mtime de la clase precede al del reporte).

**3. El borde, atacado con sondas propias.** Cuatro sondas HTTP: todas devuelven `message: "Ocurrió un error inesperado"`, así que **no hay fuga al cliente** (la premisa que yo había escrito era incorrecta: `GlobalExceptionHandler` descarta `ex.getMessage()`). Con combo de A y componente de B, la ejecución llega a `OrderServiceImpl.java:223` y la frena el predicado nuevo; `stockB = 7`, sin pedido persistido.

**4. Repositorio intacto**: `git status --porcelain` idéntico, `HEAD` idéntico, `git diff | sha256sum` idéntico antes y después. El único archivo nuevo es `api/logs/app.log` (gitignored, lo escribió la corrida autorizada de `mvn verify`).

**Hallazgos**

| ID | Severidad | Qué |
| --- | --- | --- |
| F1 | **BLOQUEANTE** | AC10 rojo: `CreateOrderWithoutTableTests` siembra un producto sin categoría — el tercer fixture del Hecho 6. → T5 |
| F2 | **BLOQUEANTE** | T2 y AC4 sin cobertura propia: revertir sólo el predicado de carga deja todo verde, y **ningún** test llama a `findAllByIdInForUpdate`. Evidencia sobrevendida en el Progress. → T4 |
| F3 | follow-up | La sonda de AC5 no detecta que se quite el scope de combos (M6). → T4 |
| F4 | follow-up | El camino de combos es tenant-safe por suposición, no por construcción (`JOIN FETCH cp.product` sin predicado). → Hecho 7, pendiente 8 |
| F5 | follow-up | Quedan dos escrituras cross-tenant **autenticadas**: `PUT /api/{id}` (`ProductServiceImpl.actualizarProducto`, `findById` sin scope + `setStock`) y `POST /api/productos` (`categoryId` del cliente sin validar). No son anónimas. → pendiente 7 |
| F6 | follow-up | La inferencia del pendiente 4 era **falsa**: un producto con `stock = null` **no** es pedible por id, lo rechaza el piso preexistente. Rectificado abajo |
| F7 | follow-up | El nombre de un producto ajeno llega al **log** del servidor por el camino de combos (`OrderServiceImpl.java:223` + `ex.printStackTrace()` en `GlobalExceptionHandler.java:61`). No llega al cliente |
| F8 | follow-up | Query muerta tenant-blind `findAllByIdIn` (`ProductRepository.java:34`), sin llamadores. → T6. Además, dos referencias de línea de este documento estaban corridas en 1: `:318`→`:317` y `:320-321`→`:319`; corregidas |

### Verificación independiente 2 — **PASS WITH FINDINGS** (agente distinto, read-only)

**AC10 verde.** Reactor completo `[1/8]`…`[8/8]`, **todos los módulos SUCCESS, sin `SKIPPED`** (el `jobs SKIPPED` de la corrida anterior desapareció). `api: Tests run: 44, Failures: 0, Errors: 0, Skipped: 0`. `CreateOrderWithoutTableTests` 1/1 (era `Errors: 1` ⇒ F1 cerrado). `[INFO] BUILD SUCCESS`.

**Matriz de mutación rehecha desde cero**, en una copia propia del verificador (ignoró explícitamente la del escritor):

| Mut | Predicado revertido | Lo atrapa | Resultado literal |
| --- | --- | --- | --- |
| M-a | carga (`findAllByIdInForUpdate`) | `TenantScopedQueryContractTests` | `Failures: 2` — `Expecting empty but was: [Product@...]` y `expected: [403L] · not expected: [404L]`. **El test HTTP quedó 3/3 verde**, reproduciendo exactamente la razón de F2 |
| M-b | `decrementStock` | `ProductStockTenantScopeTests` | `Failures: 1` — `expected: 0 but was: 1` |
| M-c | `incrementStock` | `ProductStockTenantScopeTests` | `Failures: 1` — `expected: 0 but was: 1` |
| M-d | combos (`findFullMenuCombosByIds`) | `TenantScopedQueryContractTests` | `Failures: 1` — `Expecting empty but was: [130L]` |
| M0 | los tres de producto | control | `expected: 7 but was: 5` — **el mensaje RED archivado, reproducido verbatim** |

Cada mutante se comprobó **compilado**, no salteado: la query mutante aparece en el `.class` y el mtime de la clase precede al del reporte.

**La aserción de combos NO fue debilitada** (el escritor lo afirmaba; el verificador lo decidió por su cuenta). `.extracting(Combo::getId).isEmpty()` es vacío **si y sólo si** `loaded` es vacío: el mapeo no descarta elementos (un `null` queda como elemento `null`), así que sigue siendo una aserción estricta de vacío. M-d lo prueba con `[130L]`.

**Los controles positivos son reales**: sobre-scopear o romper el camino feliz los hace fallar (mutaciones M8a/M8b/M8c). No son relleno.

**Los dos fixtures son sólo seed**: `git diff --numstat` → `12 0` y `12 0`; **cero líneas borradas**, y las líneas agregadas no contienen ningún `assert`/`expect`/`verify`.

**Ningún cuarto caso del estado imposible**, contado aparte: 10 `new Product()` en 5 archivos, **todos** con `setCategory`; 3 `new Combo()`, todos con categoría.

**El borde no empeoró.** Los cuatro hallazgos previos siguen como estaban: (i) sin fuga al cliente, confirmado; (ii) la fuga al log del servidor sigue; (iii) el producto componente del combo sigue sin predicado en el `JOIN FETCH`, sostenido sólo por la escritura — M-d lo confirma, porque con el scope de combos quitado el test de combo ajeno quedó verde; (iv) las dos escrituras cross-tenant **autenticadas** siguen ahí.

**Hallazgos de la segunda verificación: ninguno BLOQUEANTE.** Tres follow-up: AC13 parcial (pendiente 13), el `StackOverflowError` latente en los diagnósticos (pendiente 14), y el conteo erróneo del escritor, rectificado arriba.

## Open gaps and decisions

### Rectificaciones de los verificadores sobre este documento

- **El conteo de fixtures de T5 estaba mal**: decía 8 `new Product()` en 4 archivos; la re-verificación contó **10 en 5**. Todos con `setCategory`. La conclusión se sostiene, el número no.
- **La inferencia del pendiente 4 era falsa** y quedó rectificada abajo.
- **Dos referencias de línea estaban corridas en 1** (`:318`→`:317`, `:320-321`→`:319`) y quedaron corregidas en Scope In.
- **Un verde no prueba una tarea.** La fila `T2 | hecho` de una versión anterior de este documento atribuía a T2 el verde de tests que no lo aislaban. Es el error más caro que tuvo este slice, y el motivo por el que existe T4.

### Pendientes

1. **El rechazo sigue siendo 500, no 400/404.** Pre-existente y deliberadamente fuera de alcance (Out #1). Un test que espere 400 va a fallar. Lo que este slice garantiza es el **efecto**, no el status.
2. **Auditoría multi-tenant pendiente.** Este slice cierra el camino público de pedidos. No se revisó cada query del repositorio buscando el mismo patrón. La verificación encontró tres candidatos más (pendientes 7 y 8), así que el pendiente ya tiene contenido, no es especulativo.
3. **Sin unicidad de `Business.phone`.** Relevante para el slice siguiente: hoy dos negocios pueden compartir teléfono y nada lo impide.
4. **`stock = null` no es pedible por id — inferencia previa RECTIFICADA.** Este documento afirmaba que los productos con stock infinito podrían estar "ausentes del menú público pero seguir siendo pedibles por id". Es **falso**: una sonda de la verificación mostró `status=500` con el mensaje interno `Stock insuficiente: NullStockProduct`. El piso `p.stock >= :quantity` preexistente rechaza SQL `NULL`. El producto no está en el menú **ni** es pedible. Pre-existente, ajeno a este slice, pero la afirmación anterior no debía quedar sin corregir.
5. **Este slice lo descubrió el relevamiento del slice de WhatsApp**, no un test. El agujero sobrevivió a toda la batería existente, incluida la de seguridad. Vale preguntarse si falta un test de contrato multi-tenant de carácter general, más allá de este caso puntual.
6. **Cambio de comportamiento sobre un estado inválido (declarado, no un no-op).** Antes de este slice, un producto sin categoría —imposible de crear por la API e invisible en el menú público— **era pedible por id**. Ahora se rechaza, de forma consistente con el menú. Alcanzable sólo por `INSERT` directo… **y por fixtures de test, que es exactamente lo que pasó tres veces** (Hecho 6). En producción el riesgo es nulo (greenfield nunca desplegado), pero el tercer fixture demuestra que "alcanzable sólo por INSERT directo" incluye a la suite de tests.
7. **Dos escrituras cross-tenant autenticadas quedan abiertas** (F5): `PUT /api/{id}` puede poner en cero el stock de cualquier negocio, y `POST /api/productos` puede inyectar productos en la categoría de cualquier negocio. Requieren JWT, así que no son de este slice, pero son de la misma familia y merecen su propio slice. **No fueron verificadas en runtime** (haría falta un token): se verificaron por lectura de código.
8. **La creación de combos está rota y no valida pertenencia** (Hecho 8): `crearCombo` nunca llama a `setCategory` con `category_id NOT NULL`, así que los combos no se pueden crear por la API; y no valida que los productos componentes sean del negocio, que es el hermano en creación del agujero de este slice. Es el arreglo correcto del Hecho 7, en lugar de filtrar la lectura del combo.
9. **El nombre de un producto ajeno llega al log del servidor** (F7) por `OrderServiceImpl.java:223` y el `ex.printStackTrace()` de `GlobalExceptionHandler.java:61`. No llega al cliente. Severidad baja, pero el `printStackTrace` es deuda: es lo único que hace que el mensaje salga del proceso.
10. **`cancelOrder` exitoso no está cubierto.** `grep -rn cancelOrder` en tests sólo encuentra la aserción de que el endpoint exige autenticación (`AnonymousMenuOrderSecurityTests:97-101`). El pendiente 7 de la versión anterior —un pedido preexistente con ítem ajeno ahora falla con `"No se pudo devolver stock del producto"`— **sigue sin test**. Con el verde de la suite no alcanza: nadie ejercita ese camino.
11. **Tres fixtures modelaban un estado imposible y el primer pase buscó los que la suite señaló, no el patrón.** El tercero lo encontró la verificación independiente corriendo la suite completa. Regla para adelante: después de cambiar la semántica de una query, buscar el patrón en toda la suite antes de declarar el slice cerrado.
12. **La lección de T2 es la más cara de este slice.** Un test de *no-efecto* no aísla *qué* defensa funcionó. Con dos capas superpuestas (carga + escritura), la capa de arriba quedó sin evidencia y el documento la declaró hecha igual. La regla que sale de acá: **cada capa de defensa necesita un test que falle cuando esa capa se quita**, y eso se verifica por mutación, no por lectura del verde.
13. **AC13 no se cumple en la letra** (lo encontró la segunda verificación). `existsByNameAndCategory` (`ProductServiceImpl.java:134`, camino de creación autenticado) y el `findById` heredado (`ProductServiceImpl.java:155`, `ComboServiceImpl.java:51`) son lecturas alcanzables sin predicado de negocio. Es la misma familia que el pendiente 7, no el canal anónimo, y por eso queda como follow-up y no como bloqueante. Además quedan tres lecturas tenant-blind declaradas pero **sin llamadores** (`findByCategoryId`, `findByActiveTrue`, `findByCategoryIdAndActiveTrue`): código muerto de la misma clase que T6 eliminó.
14. **Riesgo latente de `StackOverflowError` en los diagnósticos.** `.singleElement()` sobre colecciones de `Combo`/`ComboProduct` (`TenantScopedQueryContractTests.java:186-197`) renderiza entidades al fallar, y el `toString()` bidireccional de Lombok `@Data` recursa infinito: el test falla igual (sigue siendo sensible), pero el mensaje puede degradarse a `StackOverflowError` en vez de mostrar el diff. Vale un patrón uniforme de aserción sobre ids en las entidades con ciclos.
