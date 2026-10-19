-- businesses: baja lógica de locales (columna active).
--
-- FEATURE: panel de operador — edición y baja de clientes
-- (odd/tasks/plataforma-editar-baja.md, S3/S5). Baja = active=false:
-- REVERSIBLE (reactivar es volver a true); el historial (pedidos, ventas)
-- no se toca. Prohibido el borrado físico: decisiones del usuario.
--
-- Los lookups PÚBLICOS por slug tratan active=false como no encontrado
-- (404); los scopes internos del panel del dueño quedan intactos.
--
-- El campo vive en la entidad Business (@Column nullable=false, default
-- TRUE): ddl-auto:update lo crea en DBs administradas por Hibernate; esta
-- migración cubre las DBs administradas a mano (y hace el default explícito
-- para que las filas viejas nazcan activas).
--
-- Idempotente: la segunda corrida es un no-op.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-19-businesses-active.sql

BEGIN;

ALTER TABLE businesses ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

COMMIT;
