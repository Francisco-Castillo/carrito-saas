-- order_items: columna selected_options (snapshot JSON de las opciones elegidas).
--
-- FEATURE: opciones de producto (odd/tasks/product-options.md, T3). Al pedir,
-- el servicio resuelve y valida las elecciones del cliente contra los grupos
-- del producto, recalcula el precio unitario con los recargos y persiste el
-- snapshot [{optionId, groupName, optionName, priceDelta}] por ítem.
--
-- Por qué un doc de migración si ddl-auto:update agrega la columna: mismo
-- patrón de 2026-10-21-menu-font.sql — DBs administradas a mano necesitan el
-- ALTER hermano.
--
-- Snapshot al momento de la venta (mismo criterio que price/cost): cambiar
-- los grupos del producto después NO reescribe pedidos pasados. Null = ítem
-- sin opciones (S6: productos sin grupos siguen igual).
--
-- Idempotente: la segunda corrida es un no-op.
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-21-order-item-selected-options.sql

BEGIN;

ALTER TABLE order_items ADD COLUMN IF NOT EXISTS selected_options TEXT;

COMMIT;
