# Limpieza de la API: hallazgos menores

**Estado:** cerrado salvo el pendiente de schema (2026-10-14)
**Fecha inicio:** 2026-10-14
**Objetivo:** Cerrar los tres hallazgos menores documentados al terminar el aislamiento por negocio, más el que apareció al diagnosticarlos.

## Hallazgos

1. **`IComboMapperImpl.toListDTO` es un TODO que devuelve `null`** (`service/.../mapper/impl/IComboMapperImpl.java:43-46`). `ComboServiceImpl.findAllByBusinessId` lo esquiva mapeando ítem por ítem; cualquier otro llamador obtendría `null` y un NPE aguas abajo.
2. **`actualizarProducto` hace `dto.getName().toString()`**: un body con `name: null` produce un **500 por NPE** en vez de un 400 honesto. Lo mismo aplica a `price`/`cost`, que son `NOT NULL` en la base (hoy serían una violación de constraint disfrazada de 500).
3. **Catálogo de productos roto por schema faltante**: `GET /api/restaurants/slug/{slug}/products` devuelve **500** porque el enriquecimiento con tendencias consulta la tabla JDBC `product_trends`, que **no existe** — `spring.jpa.hibernate.ddl-auto: update` sólo crea las tablas de entidades, y estas tablas JDBC no tienen entidad ni migración.
   - **Alcance real del hueco de schema (más amplio que la ruta)**: el código consulta además `product_daily_sales`, `product_predictions`, `daily_metrics`, `hourly_predictions` y `sales`, y **ninguna existe** en la base. O sea: tendencias, insights, métricas diarias y predicciones están no funcionales de punta a punta (los jobs que las escriben también fallarían).
4. **`GET /api/restaurants/{restaurantId}/products` es un stub muerto**: hace `return null` con la llamada al service comentada, así que responde **200 con cuerpo vacío** para cualquier id. No filtra datos, pero es un contrato mentiroso.

## Decisiones de diseño

- **Catálogo resiliente, no tabla nueva**: el catálogo de productos es funcionalidad central; las tendencias son un enriquecimiento opcional. Un schema de analítica ausente **no** debe romper la lista de productos: se degrada sin datos de tendencia **con warning en el log** (observabilidad, no silencio). Provisionar las tablas es una decisión aparte (ver pendientes).
- **Stub por id**: se implementa de forma honesta y scopeada (el id del path debe ser el negocio del token; si no, 404) en vez de devolver `null`. Alternativa considerada y descartada: borrar la ruta (el catch-all del `GlobalExceptionHandler` convierte cualquier ruta inexistente en 500, así que borrar empeora el síntoma).
- **Validación, no NPE**: campos obligatorios ausentes → 400 `VALIDATION` con mensaje claro, siguiendo el estilo de `BusinessException`.

## Tareas

- [x] 1. Implementar `IComboMapperImpl.toListDTO` y usarlo desde `findAllByBusinessId`. Tests: lista de 2+ combos mapea con items y `categoryId` no nulo.
- [x] 2. `actualizarProducto`: validar `name`/`price`/`cost` → 400 en vez de 500 por NPE o por constraint. Tests: `name` nulo → 400, `price` nulo → 400, y la fila intacta.
- [x] 3. `GET /api/restaurants/{id}/products`: implementarlo scopeado al negocio del token (ajeno/inexistente → 404, propio → 200 con la lista). Test: id propio 200, id ajeno 404, sin token 403.
- [x] 4. Catálogo por slug: degradar sin tendencias cuando la tabla falta (warning en log, 200 con productos). Test: la suite corre a propósito en una base sin `product_trends`, así que un test que espere 200 + productos es exactamente la prueba del camino degradado.

## Pendientes que quedan fuera (para decisión del usuario)

- Provisionar las tablas JDBC de analítica (`product_trends`, `product_daily_sales`, `product_predictions`, `daily_metrics`, `hourly_predictions`, `sales`): sin ellas, tendencias/insights/predicciones y los jobs que las alimentan no funcionan. Requiere decidir dueño del schema (migración, `schema.sql` o creación manual) y quién las puebla.
- `GlobalExceptionHandler` convierte rutas inexistentes (404) en 500: cualquier URL desconocida bajo un matcher autenticado responde 500.

## Evidencia / commits

- (se completa al avanzar cada tarea)

## Evidencia / commits (2026-10-14)

Cuatro work units, cada uno con su clase de test:

- `d8f0fec` *fix(api): answer 400 instead of 500 when a product update lacks required fields* — `ProductUpdateValidationTests` (5). RED: `name: null` daba 500 y `blank`/`price: null`/`cost: null` daban 200 silencioso (la fila se sobrescribía con nulls); ahora 400 con el campo nombrado y la fila intacta.
- `d6841c0` *fix(api): degrade the product catalog instead of failing when analytics is missing* — `ProductCatalogTrendDegradationTests` (2). RED: 500 `relation "product_trends" does not exist`. Degrada tendencias **y** predicciones (las dos tablas faltan) con `log.warn` y mapa vacío; el catálogo sale con `trendPercentage 0.0`, `trendDirection STABLE`, `productInsight NEUTRAL`.
- `497e391` *fix(api): implement the combo list mapper instead of returning null* — `ComboListMappingTests` (2). RED: "toListDTO must not return null".
- `d6b2949` *fix(api): give the product catalog by id an honest, tenant-scoped contract* — `ProductCatalogByIdTests` (4). RED: id ajeno e inexistente daban 200 vacío.

### Verificación (orquestador)

- Suite completa en base descartable `carrito_ci`: **91/91**, BUILD SUCCESS (corrida dos veces y verificada tras el arreglo de abajo).
- En vivo: `GET /api/restaurants/slug/demo-e2e/products` → **200** con productos y tendencias neutrales (antes 500); por id: propio **200** con 7 productos, ajeno (el negocio del usuario) **404**, sin token **403**; `PUT /api/990051` con `name: null` → **400 "El nombre es obligatorio"**; `GET /api/combos` → con `categoryId`.
- E2E de navegador: 5 pantallas **5/5** y admin **4/4** visibles, sin errores nuevos.

### Defecto de test encontrado al verificar (importante)

La primera corrida de la suite dio **91 con 1 fallo** aunque el worker había reportado 91/91. La causa era una aserción sin sentido en `ProductCatalogByIdTests`: `doesNotContain(categoryB.getId())` comparaba **ids de producto contra un id de categoría**, y fallaba o pasaba según cómo cayeran las secuencias compartidas de la base (el "verde" del worker fue casualidad de secuencia). Se corrigió para comparar contra el id del producto ajeno y se agregó la aserción que el test quería hacer (que ninguna fila devuelta traiga la categoría de otro negocio). Lección: la verificación del orquestador sobre una base fresca es la que vale, no el reporte del ejecutor.

### Nota de operación

Al levantar el backend no hay que heredar `SPRING_DATASOURCE_URL` (variable usada para correr la suite contra `carrito_ci`): si queda exportada, el backend intenta conectar a una base inexistente y muere con `Unable to determine Dialect without JDBC metadata`. Me pasó en esta sesión.
