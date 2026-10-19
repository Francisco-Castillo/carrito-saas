-- ============================================================================
-- Analytics JDBC tables — schema.sql
--
-- OWNERSHIP BOUNDARY:
--   - Hibernate's ddl-auto: update owns the JPA entity tables
--     (products, businesses, orders, ...).
--   - THIS file owns the analytics tables read/written through plain JDBC
--     (ProductMetricsRepositoryImpl, ProductTrendRepositoryImpl,
--     PredictionWriteRepositoryImpl, ...). They have no JPA entity and no
--     Hibernate migration, so without this script they never exist and the
--     analytics layer fails at runtime (`relation "product_trends" does not
--     exist`).
--
-- WHY THERE ARE NO FOREIGN KEYS:
--   spring.sql.init runs BEFORE Hibernate creates the entity tables, so any
--   FK referencing products/businesses/orders would fail on a fresh database
--   and abort startup. The analytics tables reference entity rows by plain
--   BIGINT id on purpose.
--
-- Every statement is idempotent (IF NOT EXISTS): the script runs on every
-- application startup, not just on database creation.
-- ============================================================================

-- Written by the per-day upsert in ProductMetricsRepositoryImpl.
CREATE TABLE IF NOT EXISTS product_daily_sales (
  business_id   BIGINT NOT NULL,
  product_id    BIGINT NOT NULL,
  date          DATE   NOT NULL,
  quantity_sold INTEGER,
  revenue       NUMERIC(14,2),
  CONSTRAINT uq_product_daily_sales UNIQUE (business_id, product_id, date)
);

-- Read by the catalog trend enrichment and the top-up/top-down queries.
-- updated_at is read without a null check (ProductTrendRepositoryImpl.mapRow),
-- so it must be NOT NULL.
CREATE TABLE IF NOT EXISTS product_trends (
  product_id          BIGINT NOT NULL,
  business_id         BIGINT NOT NULL,
  trend_percentage    DOUBLE PRECISION,
  current_week_sales  INTEGER,
  previous_week_sales INTEGER,
  trend_direction     VARCHAR(10) NOT NULL,
  updated_at          TIMESTAMP   NOT NULL,
  CONSTRAINT uq_product_trends UNIQUE (product_id, business_id)
);
CREATE INDEX IF NOT EXISTS idx_product_trends_business ON product_trends (business_id);

-- Written by the delete-then-insert-per-date writer in
-- PredictionWriteRepositoryImpl.
CREATE TABLE IF NOT EXISTS product_predictions (
  business_id        BIGINT NOT NULL,
  product_id         BIGINT NOT NULL,
  date               DATE   NOT NULL,
  predicted_quantity INTEGER,
  CONSTRAINT uq_product_predictions UNIQUE (business_id, date, product_id)
);
CREATE INDEX IF NOT EXISTS idx_product_predictions_lookup ON product_predictions (business_id, product_id);

-- Written by the replace pattern in PredictionWriteRepositoryImpl and read by
-- the average-orders dashboard query. top_product_id stays nullable: it is
-- passed as null when a business has no orders yet.
CREATE TABLE IF NOT EXISTS daily_metrics (
  business_id    BIGINT NOT NULL,
  date           DATE   NOT NULL,
  total_orders   INTEGER,
  peak_hour      INTEGER,
  top_product_id BIGINT,
  CONSTRAINT uq_daily_metrics UNIQUE (business_id, date)
);

-- Write-only today (hourly order predictions).
CREATE TABLE IF NOT EXISTS hourly_predictions (
  business_id      BIGINT NOT NULL,
  date             DATE   NOT NULL,
  hour             INTEGER NOT NULL,
  predicted_orders INTEGER,
  CONSTRAINT uq_hourly_predictions UNIQUE (business_id, date, hour)
);

-- ---------------------------------------------------------------------------
-- SEQUENCES (Hibernate no crea secuencias: ddl-auto:update sólo toca tablas de
-- entidades, así que la secuencia que consume el JPA va acá, idempotente).

-- IDs de businesses creados por el panel de operador
-- (docs/migrations/2026-10-19-businesses-id-sequence.sql, documento hermano
-- para DBs administradas a mano). La entidad Business no tiene generador: sus
-- filas nacieron de seeds con id manual (990000001) y ddl-auto nunca puso un
-- default en la columna. La secuencia arranca lejos de los ids manuales y
-- convive con ellos.
CREATE SEQUENCE IF NOT EXISTS businesses_id_seq
    START WITH 1000000000
    INCREMENT BY 1;

-- Baja lógica de locales (docs/migrations/2026-10-19-businesses-active.sql,
-- documento hermano; odd/tasks/plataforma-editar-baja.md S3/S5). ddl-auto
--:update NO agregó la columna a la DB existente al arrancar (verificado:
-- "column b1_0.active does not exist"), así que el ALTER idempotente va
-- acá, como la secuencia: corre en cada startup, antes de Hibernate.
-- Baja = active=false; REVERSIBLE; el historial no se toca.
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
