-- WhatsApp por sucursal (multi-sucursal).
--
-- FEATURE: multi-sucursal fase 3 (odd/tasks/multi-sucursal.md, S3/T5).
-- La columna la crea Hibernate (ddl-auto:update, columna nueva en tabla
-- existente); esta migración cubre las DBs administradas a mano Y hace el
-- backfill en AMBOS tipos de DB.
--
-- Backfill limitado a la sucursal default (menor id, la "Casa central"
-- sembrada por 2026-10-24-branches-default.sql) A PROPOSITO: hasta hoy el
-- número de WhatsApp era UNO por negocio y la carta lo resolvía a nivel
-- negocio; la sucursal default es la que ya sirve la carta pública (T4),
-- así que es la única cuyo número "era" el del negocio. Cargar el mismo
-- número en TODAS las sucursales sería mentir (impediría al dueño dar a
-- cada una su propio WhatsApp: chocaría con la única global recién creada)
-- y obligaría a limpiar a mano; las demás sucursales arrancan SIN número
-- propio (NULL) y caen al del negocio, exactamente como lo hacían.
--
-- La columna queda NULLABLE a propósito (igual que la entidad): NULL
-- significa "sin WhatsApp propio" y es un estado legítimo y permanente.
--
-- Unicidad GLOBAL nombrada uq_branches_phone: el número identifica un
-- lugar — dos sucursales, propias o de negocios distintos, no pueden
-- compartirlo (S3/T5). Los NULLs se permiten y son distintos entre sí en
-- Postgres, así que varias sucursales sin número conviven. Nombre
-- explícito e idempotente vía DO block, misma política que
-- uq_businesses_phone y fk_orders_branch.
--
-- Idempotente: la segunda corrida es un no-op.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-29-branch-phone.sql

BEGIN;

ALTER TABLE branches ADD COLUMN IF NOT EXISTS phone VARCHAR(255);

-- Backfill: SÓLO la sucursal default (menor id por negocio) hereda el
-- número del negocio; ver el encabezado para el porqué del límite.
UPDATE branches
SET phone = (
    SELECT b.phone FROM businesses b WHERE b.id = branches.business_id
)
WHERE phone IS NULL
  AND id IN (SELECT MIN(id) FROM branches GROUP BY business_id);

-- Única global nombrada (idempotente vía DO block).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_branches_phone'
    ) THEN
        ALTER TABLE branches
            ADD CONSTRAINT uq_branches_phone UNIQUE (phone);
    END IF;
END $$;

COMMIT;
