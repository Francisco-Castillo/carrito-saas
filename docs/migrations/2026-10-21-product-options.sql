-- product_option_groups / product_options: grupos de opciones por producto.
--
-- FEATURE: opciones de producto (odd/tasks/product-options.md, T1). El dueño
-- define grupos por producto (ej: "Tipo de pan" obligatorio, elegir 1;
-- "Extras" opcional, elegir varios) y cada opción puede sumar un recargo
-- (price_delta) al precio del producto al pedirlo.
--
-- Por qué un doc de migración si ddl-auto:update crea las tablas: este
-- archivo es el documento hermano para bases administradas a mano (mismo
-- patrón de 2026-10-21-menu-font.sql) — sin él, una DB creada a mano queda
-- sin las tablas y el producto con opciones falla al pedirse.
--
-- Definición por producto (S3): sin biblioteca compartida. Borrar un
-- producto borra sus grupos y opciones (ON DELETE CASCADE, igual que el
-- cascade JPA de la entidad).
--
-- Reglas del grupo (S1): required (obligatorio), multiple (elegir varios),
-- min_selections / max_selections para grupos múltiples (NULL = sin límite
-- para max en grupos múltiples; obligatorios no-múltiples eligen exactamente 1).
--
-- Idempotente: la segunda corrida es un no-op.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-21-product-options.sql

BEGIN;

CREATE TABLE IF NOT EXISTS product_option_groups (
  id              BIGSERIAL PRIMARY KEY,
  product_id      BIGINT       NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  name            VARCHAR(255) NOT NULL,
  required        BOOLEAN      NOT NULL DEFAULT FALSE,
  multiple        BOOLEAN      NOT NULL DEFAULT FALSE,
  min_selections  INTEGER,
  max_selections  INTEGER
);

CREATE TABLE IF NOT EXISTS product_options (
  id           BIGSERIAL PRIMARY KEY,
  group_id     BIGINT          NOT NULL REFERENCES product_option_groups(id) ON DELETE CASCADE,
  name         VARCHAR(255)    NOT NULL,
  price_delta  NUMERIC(19, 2)  NOT NULL DEFAULT 0,
  position     INTEGER
);

CREATE INDEX IF NOT EXISTS idx_product_option_groups_product
  ON product_option_groups(product_id);
CREATE INDEX IF NOT EXISTS idx_product_options_group
  ON product_options(group_id);

COMMIT;
