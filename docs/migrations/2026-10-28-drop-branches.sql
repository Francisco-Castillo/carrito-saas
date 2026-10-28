-- branches: baja de todo lo que dejó la feature multi-sucursal (revertida).
--
-- FEATURE: revert del merge #92 (PR feat/multi-sucursal). El código se
-- revirtió en f707a55, pero el esquema queda tocado en las DBs donde
-- corrieron las migraciones 2026-10-24/27/28/29 (la DB de desarrollo las
-- corrió: ver 6d1f0a9) y donde Hibernate (ddl-auto:update) creó la tabla.
-- ddl-auto:update nunca borra, así que esta migración baja a mano:
--
--   1. índices funcionales de business_hours y business_date_exception
--   2. FKs a branches (orders, business_hours, business_date_exception)
--   3. columnas branch_id
--   4. constraints únicas originales de 3 columnas (las que la feature
--      dropeó para meter branch_id en la clave)
--   5. tabla branches (con sus únicas uq_branches_business_name y
--      uq_branches_phone, que caen con la tabla)
--
-- Los datos de branches se pierden: era la sucursal default ("Casa central")
-- sembrada por la feature y nada de producción dependía de ella. Los
-- pedidos/horarios/excepciones quedan como estaban antes de la feature.
--
-- Idempotente: segura sobre una DB que nunca corrió la feature (todo es
-- IF EXISTS / IF NOT EXISTS) y re-ejecutable.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-28-drop-branches.sql

BEGIN;

-- 1. Índices funcionales creados por 2026-10-28-branch-hours.sql.
DROP INDEX IF EXISTS uq_business_hours_business_day_position_effective;
DROP INDEX IF EXISTS uq_business_date_exception_business_date_position_effective;

-- 2. FKs hacia branches.
ALTER TABLE orders DROP CONSTRAINT IF EXISTS fk_orders_branch;
ALTER TABLE business_hours DROP CONSTRAINT IF EXISTS fk_business_hours_branch;
ALTER TABLE business_date_exception DROP CONSTRAINT IF EXISTS fk_business_date_exception_branch;

-- 3. Columnas branch_id (sus datos apuntaban a la sucursal default
--    sembrada; se pierden con la columna, como el resto de la feature).
ALTER TABLE orders DROP COLUMN IF EXISTS branch_id;
ALTER TABLE business_hours DROP COLUMN IF EXISTS branch_id;
ALTER TABLE business_date_exception DROP COLUMN IF EXISTS branch_id;

-- 4. Restaurar las únicas de 3 columnas que 2026-10-28 dropeó. Seguro
--    re-crearlas: el backfill de la feature re-parentó todo a UNA sucursal
--    por negocio, así que (business_id, day/date, position) sigue único.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_business_hours_business_day_position'
    ) THEN
        ALTER TABLE business_hours
            ADD CONSTRAINT uq_business_hours_business_day_position
            UNIQUE (business_id, day_of_week, position);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_business_date_exception_business_date_position'
    ) THEN
        ALTER TABLE business_date_exception
            ADD CONSTRAINT uq_business_date_exception_business_date_position
            UNIQUE (business_id, date, position);
    END IF;
END $$;

-- 5. La tabla, al final (con las únicas uq_branches_business_name y
--    uq_branches_phone, que caen con ella).
DROP TABLE IF EXISTS branches;

COMMIT;
