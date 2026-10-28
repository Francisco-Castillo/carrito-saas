-- Horarios y fechas especiales por sucursal (multi-sucursal).
--
-- FEATURE: multi-sucursal fase 2 (odd/tasks/multi-sucursal.md, S3/T4).
-- La columna la crea Hibernate (ddl-auto:update, columna nueva en tabla
-- existente); esta migración cubre las DBs administradas a mano Y hace el
-- backfill en AMBOS tipos de DB: cada fila de horario/excepción existente se
-- re-parenta a la sucursal default de su negocio (la de menor id, que es la
-- "Casa central" sembrada por 2026-10-24-branches-default.sql). Las demás
-- sucursales arrancan SIN filas propias: día sin filas = cerrado, y el dueño
-- les carga horario desde el perfil con la sucursal seleccionada.
--
-- La columna queda NULLABLE a propósito (igual que la entidad): la no-null
-- la garantiza el servicio (todo turno nuevo nacido de un perfil con
-- sucursal seleccionada setea branch), no el esquema. NULL significa "fila
-- legacy a nivel negocio": la escribió el perfil antes de las sucursales (o
-- el negocio todavía no tiene ninguna) y la heredan las sucursales que no
-- definieron las suyas — un ALTER NOT NULL rompería esas filas y los
-- negocios sin sucursales.
--
-- Swap de constraints únicas nombradas: la vieja (3 columnas, sin branch)
-- prohibiría a dos sucursales del mismo negocio tener turnos el mismo
-- día/posición; la nueva incluye branch_id. Se DROPea la vieja y se agrega la
-- nueva con nombre estable e idempotente (DO block), misma política que
-- fk_orders_branch en 2026-10-27-orders-branch.sql.
--
-- Ámbito NULL (fix CI run 40619938257): en Postgres dos NULL son distintos
-- entre sí, así que la única de 4 columnas NO rechaza dos filas legacy
-- (branch_id NULL) con el mismo día/posición o fecha/posición — en dev lo
-- escondía la vieja única de 3 columnas (que sobrevive a Hibernate) y en CI
-- (base nueva) entraban libres. Cada tabla lleva además un índice único
-- FUNCIONAL con el ámbito coalesceado a centinela (COALESCE(branch_id, -1)):
-- dos filas legacy comparten el centinela -1 y chocan, y las distintas
-- sucursales conservan ámbitos independientes — el idiom estándar de Postgres
-- para "NULLs iguales" (equivalente a NULLS NOT DISTINCT de PG 15+).
-- Es el mismo índice que declaran las entidades vía JPA @Index con la
-- expresión CASE (ddl-auto lo crea en bases nuevas); acá se deja escrito con
-- la MISMA expresión para las DBs administradas a mano.
--
-- Idempotente: la segunda corrida es un no-op.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-28-branch-hours.sql

BEGIN;

ALTER TABLE business_hours ADD COLUMN IF NOT EXISTS branch_id BIGINT;
ALTER TABLE business_date_exception ADD COLUMN IF NOT EXISTS branch_id BIGINT;

-- Backfill: filas sin sucursal → sucursal default (menor id) del negocio.
UPDATE business_hours bh
SET branch_id = (
    SELECT MIN(b.id) FROM branches b WHERE b.business_id = bh.business_id
)
WHERE bh.branch_id IS NULL;

UPDATE business_date_exception de
SET branch_id = (
    SELECT MIN(b.id) FROM branches b WHERE b.business_id = de.business_id
)
WHERE de.branch_id IS NULL;

-- Swap de las únicas nombradas + FKs (idempotentes vía DO block).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_business_hours_business_day_position'
    ) THEN
        ALTER TABLE business_hours
            DROP CONSTRAINT uq_business_hours_business_day_position;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_business_hours_business_branch_day_position'
    ) THEN
        ALTER TABLE business_hours
            ADD CONSTRAINT uq_business_hours_business_branch_day_position
            UNIQUE (business_id, branch_id, day_of_week, position);
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_business_date_exception_business_date_position'
    ) THEN
        ALTER TABLE business_date_exception
            DROP CONSTRAINT uq_business_date_exception_business_date_position;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_business_date_exception_business_branch_date_position'
    ) THEN
        ALTER TABLE business_date_exception
            ADD CONSTRAINT uq_business_date_exception_business_branch_date_position
            UNIQUE (business_id, branch_id, date, position);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes WHERE indexname = 'uq_business_hours_business_day_position_effective'
    ) THEN
        CREATE UNIQUE INDEX uq_business_hours_business_day_position_effective
            ON business_hours (business_id, day_of_week, position,
                CASE WHEN branch_id IS NULL THEN -1 ELSE branch_id END);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes WHERE indexname = 'uq_business_date_exception_business_date_position_effective'
    ) THEN
        CREATE UNIQUE INDEX uq_business_date_exception_business_date_position_effective
            ON business_date_exception (business_id, date, position,
                CASE WHEN branch_id IS NULL THEN -1 ELSE branch_id END);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_business_hours_branch'
    ) THEN
        ALTER TABLE business_hours
            ADD CONSTRAINT fk_business_hours_branch
            FOREIGN KEY (branch_id) REFERENCES branches (id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_business_date_exception_branch'
    ) THEN
        ALTER TABLE business_date_exception
            ADD CONSTRAINT fk_business_date_exception_branch
            FOREIGN KEY (branch_id) REFERENCES branches (id);
    END IF;
END $$;

COMMIT;
