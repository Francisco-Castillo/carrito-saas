-- users: baja lógica de usuarios (columna active).
--
-- FEATURE: gestión de usuarios del local desde el panel de operador
-- (odd/tasks/plataforma-usuarios.md, S4). Baja = active=false: el usuario
-- deja de poder loguearse (CustomUserDetailsService lo marca disabled —
-- el error es indistinguible de una clave incorrecta) y el historial de
-- pedidos queda intacto. Prohibido el borrado físico.
--
-- El campo vive en la entidad User (@Column nullable=false, default TRUE):
-- ddl-auto lo crea en DBs FRESCAS (CI) ya con la columna — NADA de este
-- ALTER va en schema.sql (spring.sql.init corre ANTES de Hibernate y el
-- parser no soporta DO $$; el CI de #66 lo probó). Las DBs administradas
-- a mano aplican esta migración una vez (idempotente). La DB local
-- compartida se provisionó out-of-band con el mismo ALTER (JDBC).
--
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-19-users-active.sql

BEGIN;

ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

COMMIT;
