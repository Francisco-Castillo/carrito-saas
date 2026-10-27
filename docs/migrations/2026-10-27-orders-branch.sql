-- orders: sucursal del pedido (multi-sucursal).
--
-- FEATURE: multi-sucursal fase 2 (odd/tasks/multi-sucursal.md, S3/T3).
-- La columna la crea Hibernate (ddl-auto:update, columna nueva en tabla
-- existente); esta migración cubre las DBs administradas a mano Y hace el
-- backfill en AMBOS tipos de DB: cada pedido existente se re-parenta a la
-- sucursal default de su negocio (la de menor id, que es la "Casa central"
-- sembrada por 2026-10-24-branches-default.sql).
--
-- La columna queda NULLABLE a propósito (igual que la entidad): la no-null
-- la garantiza el servicio (todo pedido nuevo setea sucursal), no el
-- esquema — un ALTER NOT NULL sobre pedidos históricos de una DB sin la
-- migración de sucursales rompería el arranque.
--
-- Idempotente: la segunda corrida es un no-op.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-27-orders-branch.sql

BEGIN;

ALTER TABLE orders ADD COLUMN IF NOT EXISTS branch_id BIGINT;

-- Backfill: pedidos sin sucursal → sucursal default (menor id) del negocio.
UPDATE orders o
SET branch_id = (
    SELECT MIN(b.id) FROM branches b WHERE b.business_id = o.business_id
)
WHERE o.branch_id IS NULL;

-- FK con nombre estable (idempotente vía DO block).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_orders_branch'
    ) THEN
        ALTER TABLE orders
            ADD CONSTRAINT fk_orders_branch
            FOREIGN KEY (branch_id) REFERENCES branches (id);
    END IF;
END $$;

COMMIT;
