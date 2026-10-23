-- Notas de preferencia por ítem de pedido ("sin mayonesa, sin lechuga"):
-- el cliente las escribe en el modal del producto y llegan a cocina.
-- ddl-auto:update crea la columna en bases gestionadas por entidades; esta
-- migración deja el cambio explícito para bases provisionadas a mano.
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS item_notes VARCHAR(300);
