# Product options (grupos de opciones por producto)

Feature: permitir que el dueño del local defina grupos de opciones por producto
(ej: tipo de pan, tamaño), con reglas de obligatoriedad y selección múltiple,
precio adicional por opción, selección obligatoria al pedir, y visibilidad en KDS.

## Specs

- S1: El dueño puede crear, editar y borrar grupos de opciones desde la creación/edición de un producto. Cada grupo tiene nombre (ej: "Tipo de pan"), flag obligatorio, y si permite elegir 1 sola opción o varias con mínimo/máximo.
- S2: Cada opción dentro de un grupo tiene nombre (ej: "Pan integral") y puede sumar un recargo al precio del producto.
- S3: Las opciones se definen por producto, sin biblioteca/plantillas compartidas entre productos.
- S4: Al pedir un producto con grupos obligatorios desde el menú público, el cliente debe completar las elecciones obligatorias antes de agregar al carrito; el precio del ítem se recalcula con los recargos elegidos.
- S5: Las opciones elegidas se guardan con cada ítem del pedido y son visibles en el KDS (pantalla de cocina) para cada ítem.
- S6: Productos sin grupos de opciones siguen funcionando exactamente igual que hoy (ningún cambio de comportamiento para productos sin opciones).

## Tasks

- T1 (S1,S2,S3) backend: entidades `ProductOptionGroup` y `ProductOption` (por producto, required, min/max selecciones, priceDelta), schema.sql + migration doc. Commit.
- T2 (S1,S2,S3,S6) backend: DTOs y endpoints de creación/edición de producto aceptan grupos de opciones; validación (grupo obligatorio requiere opciones; min<=max). Tests endpoint. Commit.
- T3 (S4,S5,S6) backend: `OrderRequestDTO` acepta selecciones de opciones por ítem; el servicio valida obligatorios/min/max contra el producto, recalcula precio, persiste selecciones en `OrderItem` (JSON) y las expone en `OrderItemDTO` y `OrderItemKitchenDTO`. Tests. Commit.
- T4 (S1,S2,S3) frontend admin: `ProductModal` con editor de grupos de opciones (agregar/quitar grupos y opciones, recargo, obligatorio, min/max). Commit.
- T5 (S4,S6) frontend menú: `ProductDetailSheet` renderiza los grupos, bloquea "agregar" si falta un obligatorio, muestra el precio recalculado y envía las selecciones en el pedido. Commit.
- T6 (S5) frontend KDS: `Kds.tsx` muestra las opciones elegidas por ítem. Commit.

## Log

- L1 (pedido original, verbatim): "hay productos que por ejemplo tienen diferentes opciones, tipo de pan, tamaño, etc y eso no lo tenemos ahora a la ahora de crear un producto y menos al ahora de pedirlo, estaria bueno poder agregar esa funcionalidad que el dueño del local pueda crea una opcion necesaria, nose si me entiendes"
- L2: Decisiones del usuario: opciones con recargo de precio (S2); grupos obligatorios y múltiples configurables (S1); opciones visibles en KDS (S5); definición por producto sin plantillas (S3).
