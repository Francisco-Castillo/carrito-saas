-- Dev seed for local end-to-end verification (docs/dev-seed.sql).
-- NEVER run this against production: it creates a demo business and three
-- users whose password is the literal string admin123, and it fixes ids
-- that collide with the fixtures the backend tests seed on a clean schema.
-- Usage: psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 -f docs/dev-seed.sql

-- Dev seed for local end-to-end verification (NOT for production).
-- Password for owner/kitchen/admin is admin123 (bcrypt, cost 10).

BEGIN;

-- Business
INSERT INTO businesses (id, name, phone, slug)
VALUES (1, 'Foodtruck Demo', '1123456789', 'demo');

-- Roles
INSERT INTO roles (id, name) VALUES (1, 'OWNER'), (2, 'KITCHEN'), (3, 'ADMIN');

-- Users (same bcrypt hash for admin123)
INSERT INTO users (id, username, email, password) VALUES
  (1, 'owner',   'owner@demo.test',   '$2a$10$bpCdNav/Sp.8IOE8kV4TweC5zpRUNCirGstxsaqpV/AL/eQ9sIOZ2'),
  (2, 'kitchen', 'kitchen@demo.test', '$2a$10$bpCdNav/Sp.8IOE8kV4TweC5zpRUNCirGstxsaqpV/AL/eQ9sIOZ2'),
  (3, 'admin',   'admin@demo.test',   '$2a$10$bpCdNav/Sp.8IOE8kV4TweC5zpRUNCirGstxsaqpV/AL/eQ9sIOZ2');

-- User -> role (authorities are ROLE_ + roles.name)
INSERT INTO user_roles (user_id, role_id) VALUES (1, 1), (2, 2), (3, 3);

-- User -> business + role used by /api/auth/login
INSERT INTO business_users (id, business_id, role_id, user_id) VALUES
  (1, 1, 1, 1),
  (2, 1, 2, 2),
  (3, 1, 3, 3);

-- Menu categories
INSERT INTO categories (id, name, orden, active, business_id) VALUES
  (1, 'Hamburguesas', 1, true, 1),
  (2, 'Bebidas',      2, true, 1),
  (3, 'Postres',      3, true, 1);

-- Products
INSERT INTO products (id, name, description, image_url, price, cost, stock, unit_measure, active, category_id) VALUES
  (1, 'Doble cheddar',   'Doble medallon, cheddar y bacon', NULL, 9500.00, 4200.00, 30, 'UNIDAD', true, 1),
  (2, 'Clasica',         'Medallon, lechuga, tomate y cheddar', NULL, 7500.00, 3300.00, 40, 'UNIDAD', true, 1),
  (3, 'Veggie',          'Medallon de lentejas y palta', NULL, 8000.00, 3500.00, 15, 'UNIDAD', true, 1),
  (4, 'Coca-Cola 500ml', 'Botella de vidrio', NULL, 2500.00, 1100.00, 60, 'UNIDAD', true, 2),
  (5, 'Agua mineral',    '500ml sin gas', NULL, 1800.00, 700.00, 80, 'UNIDAD', true, 2),
  (6, 'Flan casero',     'Con dulce de leche', NULL, 3200.00, 1200.00, 12, 'PORCION', true, 3),
  (7, 'Brownie',         'Con helado de crema', NULL, 3800.00, 1500.00, 0, 'PORCION', false, 3);

-- Combo: burger + drink
INSERT INTO combos (id, name, price, active, category_id) VALUES (1, 'Combo Clasica + Bebida', 9000.00, true, 1);
INSERT INTO combo_products (combo_id, producto_id, quantity) VALUES (1, 2, 1), (1, 4, 1);

-- Cancellation reasons used by the KDS cancel modal
INSERT INTO cancellation_reasons (id, code, description, active, created_at) VALUES
  (1, 'NO_STOCK',    'Sin stock de ingredientes', true, now()),
  (2, 'CUSTOMER',    'El cliente cancelo',        true, now()),
  (3, 'ERROR_PEDIDO','Error en el pedido',        true, now());

SELECT setval(pg_get_serial_sequence('businesses', 'id'), 1, true);
SELECT setval(pg_get_serial_sequence('roles', 'id'), 3, true);
SELECT setval(pg_get_serial_sequence('users', 'id'), 3, true);
SELECT setval(pg_get_serial_sequence('business_users', 'id'), 3, true);
SELECT setval(pg_get_serial_sequence('categories', 'id'), 3, true);
SELECT setval(pg_get_serial_sequence('products', 'id'), 7, true);

COMMIT;
