-- roles: catálogo de roles, con el nombre único.
--
-- TARGET SHAPE — el nombre pasa a ser único. Nada lo impedía, y la base local
-- acumuló 93 filas OWNER (92 sin que nadie las referencie) por corridas viejas
-- de scripts: `roles` se sembraba con INSERT sin guarda antes de que el seed
-- fuera idempotente.
--
-- Por qué es seguro: sólo se borran filas que NADIE referencia, ni por
-- `user_roles` ni por `business_users`. Un rol en uso nunca se toca.
--
-- Idempotente: la segunda corrida borra 0 filas y recrea la constraint.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-15-roles-name-unique.sql

BEGIN;

-- Roles que nadie referencia: son las copias que dejaron los seeds viejos.
DELETE FROM roles r
WHERE NOT EXISTS (SELECT 1 FROM user_roles ur WHERE ur.role_id = r.id)
  AND NOT EXISTS (SELECT 1 FROM business_users bu WHERE bu.role_id = r.id);

-- Y de acá en adelante el nombre no se repite.
ALTER TABLE roles DROP CONSTRAINT IF EXISTS uq_roles_name;
ALTER TABLE roles ADD CONSTRAINT uq_roles_name UNIQUE (name);

COMMIT;
