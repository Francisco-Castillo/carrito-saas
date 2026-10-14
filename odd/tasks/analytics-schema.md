# Schema de analítica: darle dueño a las tablas JDBC

**Estado:** activo — mecanismo decidido
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

- [ ] 1. Derivar el DDL exacto desde el uso real (queries JDBC) y validarlo contra la base.
- [ ] 2. `schema.sql` idempotente + `spring.sql.init.mode=always` (decisión tomada).
- [ ] 2b. Revisar la interacción con los tests: `ProductCatalogTrendDegradationTests` hoy prueba la degradación **porque la tabla no existe**; con el schema provisionado hay que mantener cubierto ese camino de otra forma (por ejemplo con el service de tendencias fallando), o el test deja de proteger lo que dice proteger.
- [ ] 3. Verificar que las features de analítica vuelven a responder (y qué sigue roto por otras razones).
- [ ] 4. Decidir la convivencia con el entorno de tests (base descartable creada vacía).

## Evidencia / commits

- (se completa al avanzar cada tarea)
