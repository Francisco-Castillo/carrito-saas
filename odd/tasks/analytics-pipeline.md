# Pipeline de analítica: de tablas vacías a datos reales

**Estado:** cerrado (2026-10-14)
**Fecha inicio:** 2026-10-14
**Objetivo:** Que el pipeline de analítica (métricas → tendencias → predicciones) produzca datos de verdad, no sólo que las tablas existan.

## Diagnóstico medido (posterior a provisionar el schema)

Con las 5 tablas creadas, siguen con **0 filas**. Tres causas, en capas:

1. **El módulo `jobs` no está en la aplicación.** `api/pom.xml` no declara la dependencia de `jobs`, y el fat jar **no contiene** las clases de los schedulers (verificado: `unzip -l` → 0 coincidencias de `Scheduler`). O sea: `ProductMetricsScheduler`, `ProductTrendScheduler` y `PredictionScheduler` son código muerto que **nunca se carga**. Habilitar `@EnableScheduling` sin esto no cambia nada.
2. **No existe `@EnableScheduling` en ningún lado del proyecto** (grep: 0 hits). Sin esa anotación, `@Scheduled` no corre aunque las clases estén en el contexto.
3. **`AggregateProductMetricsUseCaseImpl.execute()` es un TODO vacío**, y el único INSERT sobre `product_daily_sales` (`ProductMetricsRepositoryImpl.aggregateToday()`) no tiene llamador. Como `product_trends` se calcula **a partir de** `product_daily_sales`, las tendencias no se pueden poblar por ningún camino vivo.

Crons existentes (intención del autor, se respetan): métricas cada 5 min (`fixedRate=300000`), tendencias cada 10 min (`0 */10 * * * *`), predicciones 4 AM (`${app.predictions.cron:0 0 4 * * *}`).

## Decisiones de diseño

- **Hacer los schedulers alcanzables** agregando la dependencia `jobs` a `api` (alternativa: mover las clases a `api`; más churn para el mismo efecto). Verificar que no genere ciclo (`jobs` depende de `service`).
- **`@EnableScheduling`** explícito y en un lugar visible (clase de configuración de la app), con comentario.
- **Backfill, no sólo "hoy"**: `execute()` agrega una **ventana configurable** (default 30 días) desde `orders`/`order_items`, no sólo el día en curso. Razón: las tendencias comparan semana actual contra anterior (ventanas de 14 días), así que agregar sólo hoy dejaría la feature vacía durante dos semanas. El upsert `ON CONFLICT` ya existente hace que **re-ejecutar sea idempotente**, así que el backfill es seguro.
- **Sin cambios de cron**: los del autor se mantienen. Nota: métricas cada 5 min re-agregando 30 días es más trabajo del que el código original imaginaba (agregaba sólo hoy); se documenta, no se cambia por decisión propia.

## Tareas

- [x] 1. `jobs` como dependencia de `api` + `@EnableScheduling`, con un test que pruebe que el scheduling está activo (no sólo que compile).
- [x] 2. Implementar la agregación con ventana configurable (propiedad en `application.yml`) reemplazando/generalizando `aggregateToday()`, idempotente.
- [x] 3. Tests: la agregación escribe `product_daily_sales` desde pedidos reales; re-ejecutar no duplica ni infla; las tendencias se calculan a partir de esa tabla; el endpoint de tendencias devuelve datos.
- [x] 4. Verificación en vivo: correr los pipelines y comprobar filas reales en las 5 tablas; verificar que el scheduler de métricas se dispara **solo** (sin llamar a ningún endpoint).
- [x] 5. Reportar el costo/beneficio del cron de 5 minutos con ventana de 30 días.

## Evidencia / commits

- (se completa al avanzar cada tarea)

## Cierre y verificación en vivo (2026-10-14)

Commits:
- `0abc623` *fix(api): select updated_at in findTopUp so the trends endpoint stops 500ing* — el bug preexistente que el worker encontró y pidió ampliar superficie para arreglar: `findTopUp` no seleccionaba `pt.updated_at` mientras el `mapRow()` compartido lo lee sin chequeo de null, así que el endpoint daba 500 desde que se escribió ese mapper (sólo el top-up: `findTopDown` sí lo seleccionaba). Una línea.
- `687731f` *feat(api): bring the analytics pipeline to life* — `jobs` como dependencia de `api`, `@EnableScheduling`, ventana configurable (`app.metrics.window-days: 30`), `aggregateToday()` → `aggregateRange(start, end)` con upsert idempotente, y `AggregateProductMetricsUseCaseImpl.execute()` implementado. Crons intactos.

### La prueba que importa: el scheduler corriendo solo

Sin llamar **ningún endpoint**:

| Hora | Evidencia |
| --- | --- |
| 12:05:23 | el job de métricas se dispara solo, 10 s después del arranque (`Aggregating product daily sales`) y llena `product_daily_sales` para los dos negocios |
| 12:06:11 | creo un pedido de 3 unidades |
| 12:10:00 | el `ProductTrendScheduler` se dispara solo en su cron (`Updating product trends… finish`) |
| 12:10:23 | segundo ciclo de métricas: la fila pasa de 8/$76.000 a **11/$104.500** (exactamente las 3 unidades nuevas) y la fila del día anterior queda idéntica (upsert idempotente) |

Estado final de las 5 tablas: `product_daily_sales` 7 filas, `product_trends` 1, `product_predictions` 1 (disparo manual de `POST /api/predictions/run`; su cron es 4 AM), `daily_metrics` 2, `hourly_predictions` 4.

Rutas que estaban en 500 y ahora responden con datos: `GET /api/trends/top-up` → 200 con `trendPercentage 100.0 / UP`; `GET /api/restaurants/dashboard/average-orders` → 200 con `averageOrders 10.0`.

Suite: **101/101** en base descartable (verificada por el orquestador).

### Costo a revisar (no cambiado por decisión propia)

Métricas cada **5 minutos** re-agregando **30 días** es bastante más trabajo que el diseño original (que agregaba sólo el día en curso). Es correcto y barato en esta escala, pero si el volumen crece conviene subir `fixedRate` o bajar `app.metrics.window-days` y hacer un backfill único. Queda anotado, no modificado: los crons son del autor.
