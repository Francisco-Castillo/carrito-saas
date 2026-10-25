-- Copia de entrega junto a la comanda (config por negocio): con este flag ON,
-- cada impresión de comanda (automática o manual) sale acompañada del ticket
-- de ENTREGA (cliente, teléfono, dirección, ítems, total y cobro) que la
-- cocina entrega al repartidor cuando el pedido queda LISTO. Arranca OFF:
-- activar la feature no cambia el comportamiento de ningún local existente
-- hasta que él la encienda.
--
-- Misma justificación de la migración de comandas (2026-10-23): entity-managed
-- con filas no puede agregar NOT NULL sin default, y el seed documentado
-- necesita el default para no violar el not-null.
BEGIN;
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS comanda_delivery_copy BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE businesses ALTER COLUMN comanda_delivery_copy SET DEFAULT FALSE;
COMMIT;
