-- branches: sucursales del negocio (multi-sucursal).
--
-- FEATURE: multi-sucursal (odd/tasks/multi-sucursal.md, S1/T1).
-- Nueva tabla; Hibernate la crea al arrancar (ddl-auto:update, lo que hace
-- bien para tablas nuevas). Esta migración cubre las DBs administradas a
-- mano Y siembra la sucursal default en AMBOS tipos de DB: cada negocio
-- existente necesita al menos una sucursal para que las fases siguientes
-- (pedidos/horarios/WhatsApp/QR por sucursal, T3-T6) tengan dónde
-- re-parentar.
--
-- El nombre default es "Casa central" (el mismo que sugiere el CRUD);
-- es irrelevante funcionalmente y el dueño lo puede renombrar.
--
-- Idempotente: la segunda corrida es un no-op.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-24-branches-default.sql

BEGIN;

CREATE TABLE IF NOT EXISTS branches (
    id          BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses (id),
    name        VARCHAR(255) NOT NULL,
    address     VARCHAR(255),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_branches_business_name UNIQUE (business_id, name)
);

-- Una sucursal default por negocio que no tenga ninguna.
INSERT INTO branches (business_id, name, address, active)
SELECT b.id, 'Casa central', NULL, TRUE
FROM businesses b
WHERE NOT EXISTS (SELECT 1 FROM branches br WHERE br.business_id = b.id);

COMMIT;
