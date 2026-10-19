-- roles: alta del rol de operador de plataforma.
--
-- FEATURE: panel de operador para dar de alta clientes y sus locales
-- (odd/tasks/plataforma-altas.md). El operador es un usuario de plataforma:
-- NO pertenece a ningún negocio (sin filas en business_users) y el login le
-- emite un token sin el claim businessId, que JwtFilter autentica sin fijar
-- TenantContext. La API /api/platform/** exige ROLE_PLATFORM_ADMIN.
--
-- El rol es una fila en el catálogo; el usuario operador se crea aparte
-- (INSERT en users + user_roles), porque su credencial no es cosa de una
-- migración. Docs/dev-seed.sql ya lo siembra para el entorno de desarrollo.
--
-- Idempotente: la segunda corrida inserta 0 filas.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-19-platform-admin-role.sql

BEGIN;

INSERT INTO roles (id, name)
SELECT 990005, 'PLATFORM_ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM roles r WHERE r.name = 'PLATFORM_ADMIN');

COMMIT;
