-- businesses: columna primary_color, el color de marca del local.
--
-- TARGET SHAPE — businesses gana `primary_color varchar(7)`. El dueño lo
-- carga desde el perfil de storefront (PUT /api/business/profile) y la carta
-- pública lo recibe como business.primaryColor para teñirse con el color del
-- local. NULL = el local no cargó color: la carta usa su paleta por defecto.
-- La validación del formato (#RRGGBB) vive en BusinessProfileServiceImpl, no
-- en la base: la columna guarda lo ya validado.
--
-- Por qué un script y no confiar en ddl-auto: update agrega la columna que
-- falta, pero no fija la forma objetivo (largo, comentario, orden). Este
-- script fija la forma completa por el mismo camino en una base vieja y en
-- una nueva.
--
-- Idempotencia: el ALTER está guardado por un chequeo contra
-- information_schema en un bloque DO — la segunda corrida no entra al IF y
-- no toca nada.
--
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-18-business-primary-color.sql

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'businesses'
          AND column_name = 'primary_color'
    ) THEN
        ALTER TABLE businesses
            ADD COLUMN primary_color varchar(7);
    END IF;
END $$;

COMMIT;
