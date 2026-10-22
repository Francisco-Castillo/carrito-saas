-- roles: PLATFORM_ADMIN deja de existir; ADMIN pasa a ser el rol exclusivo
-- del panel de plataforma, y OWNER hereda los permisos de negocio de ADMIN.
--
-- FEATURE: simplificación de roles (odd/tasks/simplificacion-roles.md).
-- Modelo final:
--   ADMIN   → operador de plataforma: /api/platform/** (ROLE_ADMIN). Usuario
--             SIN membresía; su login emite token sin businessId.
--   OWNER   → manejo completo del negocio (hereda lo que era de ADMIN):
--             /api/business/orders, /api/predictions/run, panel /admin.
--   KITCHEN, PEDIDOS → sin cambios.
--
-- Autoridades Spring (CustomUserDetailsService) salen de user_roles, NO de
-- la membresía business_users: por eso la migración toca ambas tablas.
-- Orden:
--   (a) memberships business_users ADMIN → OWNER (decisión del usuario:
--       los ADMIN de negocio pasan a OWNER).
--   (b) user_roles ADMIN de usuarios CON membresía → OWNER (misma decisión,
--       aplicada a las authorities: un usuario de negocio no puede conservar
--       ROLE_ADMIN o podría golpear /api/platform/** con token de negocio).
--   (c) user_roles PLATFORM_ADMIN → ADMIN (el operador ahora es ADMIN).
--   (d) dedup de pares (user_id, role_id) que pudieran crear (b)+(c).
--   (e) drop del rol PLATFORM_ADMIN.
--
-- Idempotente: cada paso es un no-op en la segunda corrida.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-21-admin-platform-role.sql
--
-- Los lookups de rol usan MIN(id): el catálogo puede tener filas duplicadas
-- por nombre (uq_roles_name histórica) y actualizar por id exacto dejaría
-- gemelas sin migrar.

BEGIN;

-- (a) Membresías de negocio: ADMIN → OWNER (S4). El guard EXISTS evita el
-- NULL silencioso si una DB fresca (sin seed) no tiene el rol OWNER: mejor
-- no tocar nada que dejar membresías apuntando a NULL.
UPDATE business_users
SET role_id = (SELECT MIN(id) FROM roles WHERE name = 'OWNER')
WHERE role_id IN (SELECT id FROM roles WHERE name = 'ADMIN')
  AND EXISTS (SELECT 1 FROM roles WHERE name = 'OWNER');

-- (b) Authorities de usuarios de negocio: ADMIN → OWNER (S2/S4).
UPDATE user_roles ur
SET role_id = (SELECT MIN(id) FROM roles WHERE name = 'OWNER')
WHERE ur.role_id IN (SELECT id FROM roles WHERE name = 'ADMIN')
  AND EXISTS (SELECT 1 FROM business_users bu WHERE bu.user_id = ur.user_id)
  AND EXISTS (SELECT 1 FROM roles WHERE name = 'OWNER');

-- (c) Operador de plataforma: PLATFORM_ADMIN → ADMIN (S2/S5).
UPDATE user_roles
SET role_id = (SELECT MIN(id) FROM roles WHERE name = 'ADMIN')
WHERE role_id IN (SELECT id FROM roles WHERE name = 'PLATFORM_ADMIN');

-- (d) Dedup: (b) y (c) podrían haber convergido dos filas al mismo par
-- (user_id, role_id). Conserva una.
DELETE FROM user_roles a
USING user_roles b
WHERE a.user_id = b.user_id
  AND a.role_id = b.role_id
  AND a.ctid > b.ctid;

-- (e) El rol dedicado deja de existir: ya no hay autoridad que lo referencie.
DELETE FROM roles WHERE name = 'PLATFORM_ADMIN';

COMMIT;
