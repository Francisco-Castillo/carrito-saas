# Schema de analítica: darle dueño a las tablas JDBC

**Estado:** schema provisionado; pipeline pendiente de decisión
**Fecha inicio:** 2026-10-14
**Objetivo:** Provisionar las tablas de analítica que hoy no existen (`product_trends`, `product_daily_sales`, `product_predictions`, `daily_metrics`, `hourly_predictions`, `sales`), decidir qué mecanismo es dueño de ese schema y verificar que las features vuelvan a funcionar.

## Problema medido

- `ddl-auto: update` sólo crea tablas de **entidades JPA**. Las de analítica son **JDBC puro**: no tienen entidad, no tienen migración y **no existen** en la base.
- Consecuencia verificada: `GET /api/restaurants/slug/{slug}/products` daba 500 (`relation "product_trends" does not exist`). Ese caso ya quedó mitigado degradando el enriquecimiento (commit `d6841c0`), pero tendencias, insights, métricas diarias y predicciones siguen **no funcionales**, y otros lectores (por ejemplo `TrendController`) seguirían dando 500.
- Los `jobs` que deberían poblar esas tablas probablemente también fallan.

## Decisión del usuario (2026-10-14)

**(a) `schema.sql` con `spring.sql.init`**: DDL idempotente ejecutado por Spring en cada arranque, sin dependencias nuevas y sin tocar lo que ya maneja Hibernate. Frontera documentada: **Hibernate es dueño de las tablas de entidades; `schema.sql` de las tablas JDBC de analítica**.

## Opciones evaluadas

Mecanismo dueño del schema:

- (a) **`schema.sql` con `spring.sql.init.mode=always`** + DDL idempotente (`CREATE TABLE IF NOT EXISTS`): sin dependencias nuevas, se ejecuta en cada arranque. Contra: no es un sistema de migraciones versionadas.
- (b) **Flyway** (dependencia nueva + `db/migration/V1__*.sql`): migraciones versionadas y auditables. Contra: agrega dependencia y convive con `ddl-auto: update` (hay que definir la frontera entre lo que maneja Hibernate y lo que maneja Flyway).
- (c) **Script manual documentado** (`docs/analytics-schema.sql`) que corre el operador: cero riesgo de que la app toque el schema en runtime. Contra: un paso manual más que se puede olvidar (y hoy nadie lo documentó, por eso faltan las tablas).

## Tareas

- [x] 1. Derivar el DDL exacto desde el uso real (queries JDBC) y validarlo contra la base.
- [x] 2. `schema.sql` idempotente + `spring.sql.init.mode=always` (decisión tomada).
- [x] 2b. Revisar la interacción con los tests: `ProductCatalogTrendDegradationTests` hoy prueba la degradación **porque la tabla no existe**; con el schema provisionado hay que mantener cubierto ese camino de otra forma (por ejemplo con el service de tendencias fallando), o el test deja de proteger lo que dice proteger.
- [x] 3. Verificar que las features de analítica vuelven a responder (y qué sigue roto por otras razones).
- [x] 4. Decidir la convivencia con el entorno de tests (base descartable creada vacía).

## Evidencia / commits

- (se completa al avanzar cada tarea)

## Lo que quedó hecho (2026-10-14)

Commit `cb1ae4c` *feat(api): give the analytics tables an owner with an idempotent schema.sql*:

- `api/src/main/resources/schema.sql` con las 5 tablas (`product_daily_sales`, `product_trends`, `product_predictions`, `daily_metrics`, `hourly_predictions`) + 2 índices, todo `IF NOT EXISTS`, **sin foreign keys** y con la frontera de propiedad documentada en el encabezado.
- `spring.sql.init.mode: always` en `application.yml` (el default `embedded` se salta PostgreSQL). `ddl-auto` intacto.
- Tests: `AnalyticsSchemaProvisioningTests` (2: las 5 tablas existen en base fresca —el test que habría atrapado el hueco original— y el script es idempotente al re-ejecutarse), `AnalyticsRoutesOnEmptyAnalyticsSchemaTests` (3: top-up/top-down/average-orders en 200 con resultado vacío), y `ProductCatalogTrendDegradationTests` reescrito para **inyectar** el fallo con `@MockitoBean` en vez de depender de que la tabla falte.
- Suite completa: **96/96** en base descartable (verificada por el orquestador, dos veces incluyendo una corrida posterior al commit).

### Verificación en vivo

- Al arrancar el backend, el script se aplicó a `carrito_db`: las 5 tablas existen.
- `GET /api/trends/top-up`, `/api/trends/top-down`, `/api/restaurants/dashboard/average-orders` y el catálogo por slug: **200** (antes 500 / degradado).
- E2E de navegador: 5 pantallas **5/5**, sin errores.

## El pipeline sigue sin poblar nada (hallazgo, decisión pendiente)

Medido en vivo después de provisionar: **las 5 tablas tienen 0 filas**. Dos causas independientes, ninguna relacionada con el schema:

1. **No existe `@EnableScheduling` en ningún lado del proyecto.** `ProductTrendScheduler` (cada 10 min), `PredictionScheduler` (4 AM) y `ProductMetricsScheduler` (cada 5 min) son **inertes**: `@Scheduled` sin esa anotación no corre.
2. **`AggregateProductMetricsUseCaseImpl.execute()` es un TODO vacío**, y `ProductMetricsRepositoryImpl.aggregateToday()` (el único INSERT sobre `product_daily_sales`) no tiene llamador. Como `product_trends` se calcula **a partir de** `product_daily_sales`, las tendencias no se pueden poblar por ningún camino vivo.

O sea: el schema ya no miente (no hay 500), pero para que las features tengan datos hay que habilitar scheduling e implementar la agregación — trabajo de feature, no de schema. Alternativa: disparar a mano con `POST /api/trends/run` y `POST /api/predictions/run`.

### Nota de rigor

El worker detectó por su cuenta un defecto de su propio test de idempotencia (el primer diseño dejaba un `CREATE TABLE` real filtrado en la base compartida, y por eso dos tests "pasaban" en RED) y lo corrigió antes de dar el verde. Vale registrarlo: es la misma clase de trampa que la aserción flaky de la sesión anterior.
