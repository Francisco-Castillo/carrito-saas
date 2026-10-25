# delivery-ticket-copy

Ticket de ENTREGA además de la comanda: al imprimir (auto o manual) sale la
comanda de cocina y la copia de entrega junta; cuando el pedido queda LISTO,
la cocina se queda la comanda y entrega el otro ticket al repartidor.

## Specs

S1 (pedido original, verbatim): "que te parece si le damos a elegir al cliente,
entre un ticked comanda para la cocina o doble ticked cocina y delivery, osea
cuando el pedido este listo la cocina se queda con la comanda y entrega el otro
ticked al delivery"

S2 (decisión del usuario): configurable + botones — flag nuevo en Admin → Datos
del local (`comanda_delivery_copy`); al imprimir la comanda (auto o manual) sale
también la copia de entrega en el mismo trabajo; botón manual "Entrega"
reimprime solo el ticket de entrega. El auto-print con copia ON imprime AMBOS
juntos al entrar el pedido (decisión del usuario: "que si salga juntos").

S3 (decisión del usuario): el ticket de entrega lleva TODO lo del repartidor:
cliente, teléfono, dirección, número, tipo/hora, ítems con opciones, TOTAL y
método de pago. La línea de cobro es dinámica: TRANSFERENCIA → "PAGADO" (el
gate de pago lo garantiza), EFECTIVO → "COBRAR $ X".

## Tasks

 - **T1** (S2) Backend: migración `comanda_delivery_copy` + entity + DTO +
   get/PUT profile + tests roundtrip.
 - **T2** (S2) Admin: toggle "Copia de entrega" en DatosSection (deshabilitado
   sin master) + useBusinessProfile.
 - **T3** (S3) Frontend: `DeliveryTicket.tsx` (print-only) + CSS de doble página
   (`page-break-after`); "PAGADO"/"COBRAR" según método de pago.
 - **T4** (S2) Frontend: `useComandaPrint` con trabajos (kitchen/delivery/
   double); botón "Entrega" en las cards; auto-print respeta la copia.
 - **T5** Verificación: suite completa + build:spring + mvn verify + PR.

## Log

L1 (pedido original, verbatim): "que te parece si le damos a elegir al cliente,
entre un ticked comanda para la cocina o doble ticked cocina y delivery, osea
cuando el pedido este listo la cocina se queda con la comanda y entrega el otro
ticked al delivery"

L2 (decisión del usuario, Q&A): configurable + botones; ticket completo con
total y pago; los tickets salen juntos al crear el pedido.
