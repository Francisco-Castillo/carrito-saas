-- Dev seed for local end-to-end verification (docs/dev-seed.sql).
--
-- Expects the MIGRATED business_hours shape (docs/migrations/
-- 2026-10-15-business-hours-shifts.sql): position column, no closed column,
-- UNIQUE (business_id, day_of_week, position).
--
-- Design notes:
--  * Insert-only and idempotent: safe to run against a database that already
--    holds someone else's data (every statement either uses ON CONFLICT DO
--    NOTHING or is guarded by NOT EXISTS). Nothing is dropped or truncated.
--  * Collision-free: it uses ids in the 99xxxx range, well above the ids the
--    project's own tests and demo rows use, so it cannot clash with them.
--  * Safe on a fresh database too: the rows that belong to the OWNER's own
--    business (id 900000001, `demo-pizzeria`) are guarded by EXISTS, so a
--    database that does not hold that business skips them instead of failing
--    the foreign key and rolling back the whole seed.
--  * Roles are reused by name when they exist (the roles table is seeded by
--    other flows and may already contain duplicates); KITCHEN is added only if
--    the name is missing.
--
-- NEVER run this against production: it creates a demo business and three
-- users whose password is the literal string admin123.
--
-- Usage:
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 -f docs/dev-seed.sql

BEGIN;

-- Roles (reuse by name, never duplicate)
INSERT INTO roles (id, name)
SELECT v.id, v.name
FROM (VALUES (990001, 'OWNER'), (990002, 'KITCHEN'), (990003, 'ADMIN'), (990004, 'PEDIDOS'),
             (990005, 'PLATFORM_ADMIN')) AS v(id, name)
WHERE NOT EXISTS (SELECT 1 FROM roles r WHERE r.name = v.name);

-- Business
INSERT INTO businesses (id, name, phone, slug)
VALUES (990000001, 'Foodtruck Demo', '1123456789', 'demo-e2e')
ON CONFLICT (slug) DO NOTHING;

-- Users: password is admin123 (bcrypt, cost 10)
INSERT INTO users (id, username, email, password) VALUES
  (990001, 'e2e-owner',   'e2e-owner@demo.test',   '$2a$10$bpCdNav/Sp.8IOE8kV4TweC5zpRUNCirGstxsaqpV/AL/eQ9sIOZ2'),
  (990002, 'e2e-kitchen', 'e2e-kitchen@demo.test', '$2a$10$bpCdNav/Sp.8IOE8kV4TweC5zpRUNCirGstxsaqpV/AL/eQ9sIOZ2'),
  (990003, 'e2e-admin',   'e2e-admin@demo.test',   '$2a$10$bpCdNav/Sp.8IOE8kV4TweC5zpRUNCirGstxsaqpV/AL/eQ9sIOZ2'),
  (990004, 'e2e-pedidos', 'e2e-pedidos@demo.test', '$2a$10$bpCdNav/Sp.8IOE8kV4TweC5zpRUNCirGstxsaqpV/AL/eQ9sIOZ2'),
  (990005, 'e2e-platform-admin', 'e2e-platform-admin@demo.test', '$2a$10$bpCdNav/Sp.8IOE8kV4TweC5zpRUNCirGstxsaqpV/AL/eQ9sIOZ2')
ON CONFLICT (id) DO NOTHING;

-- Operador de plataforma (rol PLATFORM_ADMIN): no pertenece a ningún
-- business, sólo a la tabla de authorities. Es quien usa el panel de alta
-- de clientes (odd/tasks/plataforma-altas.md).
-- OJO: el login exige businessId en el token; ver docs del panel operador.

-- Authorities (ROLE_ + roles.name) and business membership.
-- Roles are looked up by name with MIN(id): the table may already hold many
-- duplicate rows for the same name, and joining on the name alone would insert
-- one row per duplicate.
INSERT INTO user_roles (user_id, role_id)
SELECT v.user_id, (SELECT MIN(r.id) FROM roles r WHERE r.name = v.role)
FROM (VALUES (990001, 'OWNER'), (990002, 'KITCHEN'), (990003, 'ADMIN'), (990004, 'PEDIDOS'),
             (990005, 'PLATFORM_ADMIN')) AS v(user_id, role)
WHERE EXISTS (SELECT 1 FROM roles r WHERE r.name = v.role)
ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO business_users (id, business_id, role_id, user_id)
SELECT v.id, 990000001, (SELECT MIN(r.id) FROM roles r WHERE r.name = v.role), v.user_id
FROM (VALUES (990001, 'OWNER', 990001), (990002, 'KITCHEN', 990002), (990003, 'ADMIN', 990003), (990004, 'PEDIDOS', 990004)) AS v(id, role, user_id)
WHERE EXISTS (SELECT 1 FROM roles r WHERE r.name = v.role)
ON CONFLICT (id) DO NOTHING;

-- Menu categories
INSERT INTO categories (id, name, orden, active, business_id) VALUES
  (990011, 'Hamburguesas', 1, true, 990000001),
  (990012, 'Bebidas',      2, true, 990000001),
  (990013, 'Postres',      3, true, 990000001)
ON CONFLICT (id) DO NOTHING;

-- Products (one inactive, one out of stock)
-- image_url points at the demo photos shipped in frontend/public/dev-media/
-- (served by the app at /dev-media/**, permitted in SecurityConfig).
INSERT INTO products (id, name, description, image_url, price, cost, stock, unit_measure, active, category_id) VALUES
  (990051, 'Doble cheddar',   'Doble medallon, cheddar y bacon',              '/dev-media/doble-cheddar.jpg', 9500.00, 4200.00, 30, 'UNIDAD',  true,  990011),
  (990052, 'Clasica',         'Medallon, lechuga, tomate y cheddar',         '/dev-media/clasica.jpg',       7500.00, 3300.00, 40, 'UNIDAD',  true,  990011),
  (990053, 'Veggie',          'Medallon de lentejas y palta',                '/dev-media/veggie.jpg',        8000.00, 3500.00, 15, 'UNIDAD',  true,  990011),
  (990054, 'Coca-Cola 500ml', 'Botella de vidrio',                           '/dev-media/bebida-gaseosa.jpg', 2500.00, 1100.00, 60, 'UNIDAD', true,  990012),
  (990055, 'Agua mineral',    '500ml sin gas',                               '/dev-media/bebida-agua.jpg',   1800.00,  700.00, 80, 'UNIDAD',  true,  990012),
  (990056, 'Flan casero',     'Con dulce de leche',                          '/dev-media/postre-flan.jpg',   3200.00, 1200.00, 12, 'PORCION', true,  990013),
  (990057, 'Brownie',         'Con helado de crema (inactivo a proposito)',  '/dev-media/postre-brownie.jpg', 3800.00, 1500.00,  0, 'PORCION', false, 990013)
ON CONFLICT (id) DO NOTHING;

-- Las filas ya existentes no las toca el INSERT de arriba, asi que la foto se
-- re-aplica aparte para que re-correr el seed deje el catalogo consistente.
UPDATE products p
SET image_url = v.url
FROM (VALUES
  (990051, '/dev-media/doble-cheddar.jpg'),
  (990052, '/dev-media/clasica.jpg'),
  (990053, '/dev-media/veggie.jpg'),
  (990054, '/dev-media/bebida-gaseosa.jpg'),
  (990055, '/dev-media/bebida-agua.jpg'),
  (990056, '/dev-media/postre-flan.jpg'),
  (990057, '/dev-media/postre-brownie.jpg')
) AS v(id, url)
WHERE p.id = v.id;

-- Products 990052 and 990054 are the ones the delete-safety check must refuse to remove.
-- combo_products has its PRIMARY KEY on the surrogate `id` and NO unique on
-- (combo_id, producto_id), so ON CONFLICT DO NOTHING does not deduplicate here:
-- the guard has to be an explicit NOT EXISTS, or every re-run of this seed adds
-- another pair of rows.
INSERT INTO combos (id, name, price, active, category_id)
VALUES (990091, 'Combo Clasica + Bebida', 9000.00, true, 990011)
ON CONFLICT (id) DO NOTHING;

INSERT INTO combo_products (combo_id, producto_id, quantity)
SELECT v.combo_id, v.producto_id, v.quantity
FROM (VALUES (990091, 990052, 1), (990091, 990054, 1)) AS v(combo_id, producto_id, quantity)
WHERE NOT EXISTS (
  SELECT 1 FROM combo_products cp
  WHERE cp.combo_id = v.combo_id AND cp.producto_id = v.producto_id
);

-- Storefront (etapa 2): perfil del local + horarios estructurados.
-- Requiere el schema actual (columnas nuevas de businesses y business_hours):
-- el negocio demo se carga DESPUÉS de que la app creó las tablas con ddl-auto.
UPDATE businesses
SET logo_url      = '/dev-media/logo-local.jpg',
    banner_url    = '/dev-media/banner-local.jpg',
    address       = 'Av. Siempreviva 742, Springfield',
    instagram_url = 'https://instagram.com/foodtruckdemo'
WHERE id = 990000001;

-- El local atiende mediodía y noche: viernes y sábado tienen DOS turnos
-- (position 0 y 1), que es el caso que este modelo vino a resolver.
-- Cierre menor que apertura = madrugada del día siguiente (convención
-- documentada en IBusinessProfileService), y sólo puede ser el ÚLTIMO turno
-- del día: por eso el viernes cierra 00:30 y no el mediodía.
INSERT INTO business_hours (business_id, day_of_week, position, opens_at, closes_at) VALUES
  (990000001, 1, 0, '11:00', '23:00'),
  (990000001, 2, 0, '11:00', '23:00'),
  (990000001, 3, 0, '11:00', '23:00'),
  (990000001, 4, 0, '11:00', '23:00'),
  (990000001, 5, 0, '11:00', '15:00'),
  (990000001, 5, 1, '19:00', '00:30'),
  (990000001, 6, 0, '12:00', '15:30'),
  (990000001, 6, 1, '19:00', '00:30')
ON CONFLICT (business_id, day_of_week, position) DO UPDATE
SET opens_at = EXCLUDED.opens_at, closes_at = EXCLUDED.closes_at;

-- Segunda demo: la pizzería que se muestra en el panel. A diferencia del
-- foodtruck, este negocio NO es del seed (es del dueño), así que sus filas van
-- con DO NOTHING: siembran un estado vacío una sola vez y NUNCA pisan lo que el
-- dueño cargue desde Panel → Local. El contraste con el DO UPDATE de arriba es
-- deliberado: esas filas son del seed, éstas son del dueño.
-- Lunes cerrado (un día sin turnos ES un día cerrado); martes a jueves de
-- noche; viernes y sábado mediodía y noche; domingo sólo noche. El último turno
-- cierra después de medianoche (convención documentada en
-- IBusinessProfileService) y ninguno de esos cierres se solapa con la apertura
-- del día siguiente: 00:30 del viernes contra las 12:00 del sábado, por ejemplo.
INSERT INTO business_hours (business_id, day_of_week, position, opens_at, closes_at)
SELECT v.business_id, v.day_of_week, v.position, v.opens_at::time, v.closes_at::time
FROM (VALUES
  (900000001, 2, 0, '19:00', '00:00'),
  (900000001, 3, 0, '19:00', '00:00'),
  (900000001, 4, 0, '19:00', '00:00'),
  (900000001, 5, 0, '12:00', '15:00'),
  (900000001, 5, 1, '19:00', '00:30'),
  (900000001, 6, 0, '12:00', '15:00'),
  (900000001, 6, 1, '19:00', '00:30'),
  (900000001, 7, 0, '19:00', '23:30')
) AS v(business_id, day_of_week, position, opens_at, closes_at)
-- El negocio del dueño existe sólo en su base: en una base nueva (CI, otra
-- máquina, un contenedor) esta sección se saltea en vez de violar la FK y
-- hacer rollback de TODO el seed.
WHERE EXISTS (SELECT 1 FROM businesses b WHERE b.id = 900000001)
ON CONFLICT (business_id, day_of_week, position) DO NOTHING;

-- Fechas especiales de la pizzería, con la bandera `yearly`: son fechas del
-- calendario que se repiten TODOS los años, no un año suelto. El `date` guardado
-- es la referencia de la validación de derrame (y de la primera ocurrencia
-- cargada): el match anual es por día y mes, así que 25/12 rige en 2027, en 2030
-- y siempre, sin volver a cargarlo.
--   · 24/12 (Nochebuena): horario especial, cierra a las 23:00.
--   · 25/12 (Navidad) y 01/01 (Año Nuevo): cerrado. La fila marcadora SIN
--     horarios es la que representa "cerrado", porque en fechas la ausencia de
--     la fila significa día normal.
-- Mismo criterio que los horarios de arriba: DO NOTHING, porque este negocio es
-- del dueño y el seed no pisa lo que él cargue desde Panel → Local.
INSERT INTO business_date_exception (business_id, date, position, opens_at, closes_at, yearly)
SELECT v.business_id, v.date, v.position, v.opens_at, v.closes_at, v.yearly
FROM (VALUES
  (900000001, DATE '2026-12-24', 0, '20:00'::time, '23:00'::time, true),
  (900000001, DATE '2026-12-25', 0, NULL::time, NULL::time, true),
  (900000001, DATE '2027-01-01', 0, NULL::time, NULL::time, true)
) AS v(business_id, date, position, opens_at, closes_at, yearly)
-- Mismo guard que los horarios: el negocio del dueño puede no existir.
WHERE EXISTS (SELECT 1 FROM businesses b WHERE b.id = 900000001)
ON CONFLICT (business_id, date, position) DO NOTHING;

-- Cancellation reasons used by the KDS cancel modal
INSERT INTO cancellation_reasons (id, code, description, active, created_at) VALUES
  (990101, 'NO_STOCK',     'Sin stock de ingredientes', true, now()),
  (990102, 'CUSTOMER',     'El cliente cancelo',        true, now()),
  (990103, 'ERROR_PEDIDO', 'Error en el pedido',        true, now())
ON CONFLICT (code) DO NOTHING;

COMMIT;
