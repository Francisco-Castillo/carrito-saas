-- business_hours: one row per shift, ordered by position inside the day.
--
-- TARGET SHAPE — apply together with step 2 of the multi-shift change
-- (odd/tasks/opening-hours-shifts.md). The application still maps the
-- `closed` column until step 2 lands, so running this script early BREAKS
-- the current build. Back up (or accept the loss of closed-day rows) before
-- running; the DELETE below is irreversible.
--
-- Idempotent: safe to run more than once (all statements are guarded with
-- IF EXISTS / re-runnable forms). Run inside one transaction:
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-15-business-hours-shifts.sql

BEGIN;

-- Un día cerrado no aporta ningún turno: hoy es una fila sin horarios.
-- Guardado con DO: en una segunda corrida la columna closed ya no existe y
-- la sentencia tal cual fallaría al analizarse (Postgres resuelve columnas
-- en el parseo, un AND en tiempo de ejecución no la salva).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_name = 'business_hours' AND column_name = 'closed') THEN
    DELETE FROM business_hours WHERE closed IS TRUE OR opens_at IS NULL OR closes_at IS NULL;
  END IF;
END $$;

-- Un turno por (negocio, día) deja de ser único; pasa a ser el turno 0.
ALTER TABLE business_hours DROP CONSTRAINT IF EXISTS uq_business_hours_business_day;

ALTER TABLE business_hours ADD COLUMN IF NOT EXISTS position integer;

UPDATE business_hours SET position = 0 WHERE position IS NULL;

ALTER TABLE business_hours ALTER COLUMN position SET NOT NULL;

ALTER TABLE business_hours DROP COLUMN IF EXISTS closed;

ALTER TABLE business_hours DROP CONSTRAINT IF EXISTS uq_business_hours_business_day_position;
ALTER TABLE business_hours ADD CONSTRAINT uq_business_hours_business_day_position
  UNIQUE (business_id, day_of_week, position);

COMMIT;
