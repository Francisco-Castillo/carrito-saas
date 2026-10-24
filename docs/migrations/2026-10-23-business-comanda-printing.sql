-- Comandas impresas (config por negocio): el dueño activa la impresión de
-- comandas y la impresión automática al entrar pedido desde el panel Admin
-- (Datos del local). Ambas arrancan OFF: activar la feature no cambia el
-- comportamiento de ningún local existente hasta que él la encienda.
--
-- ESTA MIGRACIÓN ES OBLIGATORIA en todos los escenarios:
--   * Base entity-managed (ddl-auto:update) con filas: Hibernate NO puede
--     agregar columnas NOT NULL sin default a una tabla no vacía (falla el
--     ALTER y las columnas quedan sin crear) — el ADD COLUMN las crea.
--   * Base entity-managed fresca: Hibernate crea las columnas NOT NULL pero
--     SIN default, y el seed documentado (docs/dev-seed.sql, que inserta
--     filas de businesses sin estos campos) viola el not-null. Los dos
--     ALTER COLUMN ... SET DEFAULT reparan esa forma.
BEGIN;
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS comanda_printing_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS comanda_auto_print BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE businesses ALTER COLUMN comanda_printing_enabled SET DEFAULT FALSE;
ALTER TABLE businesses ALTER COLUMN comanda_auto_print SET DEFAULT FALSE;
COMMIT;
