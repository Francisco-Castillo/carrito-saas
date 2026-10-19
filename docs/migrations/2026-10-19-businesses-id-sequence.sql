-- businesses: secuencia de IDs para los altas del operador de plataforma.
--
-- FEATURE: panel de operador (odd/tasks/plataforma-altas.md, T2). La entidad
-- Business NO tiene generador (@Id sin @GeneratedValue): los rows nacieron
-- del seed y de migraciones con id manual, y ddl-auto nunca creó un default
-- para la columna. POST /api/platform/clients necesita un id nuevo por alta,
-- así que el servicio lo toma de ESTA secuencia con un nextval nativo
-- (BusinessRepository.nextBusinessId) y lo asigna explícito.
--
-- Por qué secuencia y no @GeneratedValue(IDENTITY): alterar la entidad
-- compartida no cambia las columnas existentes (ddl-auto: update no agrega
-- el default a la columna ya creada) y los inserts con id manual de seeds y
-- tests dejarían de ser garantía. La secuencia convive con ids manuales: los
-- seeds usan ids altos (990000001) y la secuencia arranca lejos de ahí.
--
-- Idempotente: la segunda corrida es un no-op.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-19-businesses-id-sequence.sql

BEGIN;

CREATE SEQUENCE IF NOT EXISTS businesses_id_seq
    START WITH 1000000000
    INCREMENT BY 1;

COMMIT;
