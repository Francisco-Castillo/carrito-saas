-- business_date_exception: columna yearly para fechas especiales que se
-- repiten todos los años.
--
-- TARGET SHAPE — business_date_exception gana `yearly boolean NOT NULL
-- DEFAULT false`. Con true la excepción rige el día y mes de `date` en todos
-- los años (Navidad es 25/12 todos los años); con false, sólo esa fecha
-- exacta. El default false conserva el significado de cada fila y de cada
-- payload viejo: sin la bandera, todo sigue siendo una excepción de fecha
-- exacta. El año de `date` queda sólo como referencia para la validación de
-- derrame, nunca parte del match anual.
--
-- Por qué un script y no confiar en ddl-auto: spring.jpa.hibernate.ddl-auto=update
-- SÍ agrega columnas que faltan (comprobado: agregó esta sola en carrito_db),
-- pero **no fija la forma objetivo**: la crea sin default, y no modifica ni
-- borra columnas ni reemplaza constraints. Este script fija la forma completa
-- —columna con DEFAULT false— por el mismo camino en una base vieja y en una
-- nueva. La lección previa de este repo estaba mal escrita: decía que update no
-- altera tablas existentes, y lo que no hace es MODIFICAR LAS EXISTENTES, no
-- agregar las que faltan.
--
-- Idempotencia: el ALTER está guardado por un chequeo contra
-- information_schema en un bloque DO — la segunda corrida no entra al IF y no
-- toca nada. Una sola declaración, sin columnas referenciadas por otros
-- statements (la lección de la migración de roles: un script "idempotente"
-- que no lo era porque una sentencia referenciaba una columna que otra
-- había borrado).
--
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-15-business-date-exception-yearly.sql

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'business_date_exception'
          AND column_name = 'yearly'
    ) THEN
        ALTER TABLE business_date_exception
            ADD COLUMN yearly boolean NOT NULL DEFAULT false;
    END IF;
END $$;

-- Mismo camino para una base que ya tenía la columna SIN default: la primera
-- guarda la saltea, así que el default hay que asegurarlo aparte. Idempotente:
-- si el default ya es false, el IF no entra.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'business_date_exception'
          AND column_name = 'yearly'
          AND column_default IS DISTINCT FROM 'false'
    ) THEN
        ALTER TABLE business_date_exception
            ALTER COLUMN yearly SET DEFAULT false;
    END IF;
END $$;

COMMIT;
