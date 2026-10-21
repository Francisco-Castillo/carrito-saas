-- businesses: columna menu_font para la tipografía de la carta.
--
-- FEATURE: personalización de la carta (odd/tasks/personalizacion-carta.md,
-- T1). El dueño elige la fuente del menú público desde la pestaña
-- "Personalización" del panel; el backend valida contra una lista blanca
-- cerrada (MenuFonts de BusinessProfileServiceImpl) y la carta carga la
-- fuente on demand desde Google Fonts.
--
-- Por qué un doc de migración si ddl-auto:update agrega la columna: este
-- archivo es el documento hermano para bases administradas a mano (el mismo
-- patrón de 2026-10-19-businesses-id-sequence.sql) — sin él, una DB creada
-- a mano quedaría sin la columna y el perfil fallaría al persistir.
--
-- Valores: slug de fuente de la lista blanca (ej: "poppins", "playfair-display")
-- o NULL cuando el dueño no eligió ninguna (la carta usa Inter, el tema
-- actual). Nunca llega a la base un valor fuera de la lista: el servicio
-- valida antes de mutar.
--
-- Idempotente: la segunda corrida es un no-op.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-21-menu-font.sql

BEGIN;

ALTER TABLE businesses ADD COLUMN IF NOT EXISTS menu_font VARCHAR(40);

COMMIT;
