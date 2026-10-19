-- orders: el CHECK de order_type formalizado, con SALON incluido.
--
-- CONTEXTO — Hibernate no crea CHECKs (ddl-auto=update ignora las
-- restricciones de nivel de tabla), así que la regla `order_type IN (...)`
-- nunca vivió en el modelo: en la base local de desarrollo existía como un
-- constraint hecho a mano (`orders_order_type_check`) que NO incluía SALON —
-- agregado a mano por el feature orders-console (odd/tasks/orders-console.md,
-- T2: pedidos de salón con mesa). Sin ese ensanchamiento, ningún pedido SALON
-- insertaba (DataIntegrityViolationException → 500). En bases frescas (CI) el
-- constraint no existe y nunca hizo falta.
--
-- Qué hace esta migración: en cualquier base que tenga el constraint viejo lo
-- reemplaza por la versión con SALON; en bases que no lo tienen (frescas) lo
-- crea, formalizando la regla a nivel de base.
--
-- Por qué es seguro: `order_type` es un enum de Hibernate
-- (RETIRO | DELIVERY | SALON desde el feature de salón); ninguna fila puede
-- tener un valor fuera de ese dominio porque la columna se escribe por JPA.
-- Si una base alojara un valor ajeno (imposible por el camino normal), el ADD
-- CONSTRAINT falla ruidosamente en vez de corromper datos.
--
-- Idempotente: DROP IF EXISTS + ADD — la segunda corrida deja el mismo
-- constraint. Ejecutar con psql (el repo no tiene cliente psql local: usar el
-- contenedor/host que lo tenga):
--   psql -U admin -h 127.0.0.1 -d carrito_db -v ON_ERROR_STOP=1 \
--     -f docs/migrations/2026-10-19-order-type-salon.sql

BEGIN;

ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_order_type_check;
ALTER TABLE orders ADD CONSTRAINT orders_order_type_check
	CHECK (order_type IN ('RETIRO', 'DELIVERY', 'SALON'));

COMMIT;
