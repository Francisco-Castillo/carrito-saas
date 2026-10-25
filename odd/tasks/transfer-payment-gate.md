# transfer-payment-gate

Pedidos con transferencia: el operador confirma el pago en la consola de
Pedidos y recién ahí el pedido pasa al KDS. Las acciones de preparar/listo/
entregado salen de la consola: son flujo del KDS.

## Specs

S1: "quiero agregar una accion sobre los pedidos con metodo de pago trasnferencia
para marcarlos como pagado una vez que el operador compruebe que la tranferencia
fue realizada y ahi recien esa comanda pasaria al kds"

S2: "tambien sacar las acciones de comenar, marcar listo y entregado por que eso
es parte del flujo del kds"

S3: "El flujo de Pedidos deberia ser Aceptar, Cancelar, imprimir y confirmar el
pago solo en aquellos pedidos que llegan dle menu publico con el metdo de pago
por transferencia"

S4 (decisión del usuario): todo pedido con método de pago `TRANSFERENCIA`
arranca en "pago pendiente" (no aparece en KDS hasta confirmar); no se marca el
origen de creación.

S5 (decisión del usuario): "Confirmar pago" y "Aceptar" son UNA sola acción:
"Confirmar y aceptar" en transferencia, "Aceptar" en el resto.

## Tasks

 - **T1** (S4) Backend: `OrderStatus.PENDING_PAYMENT` con transiciones
   `PENDING_PAYMENT → NEW | CANCELLED` y label "Pago pendiente". ✅
   `repository/.../enums/OrderStatus.java`.
 - **T2** (S1, S4) Backend: creación de pedido con `TRANSFERENCIA` arranca en
   `PENDING_PAYMENT`; `findActiveOrders` (KDS) excluye `PENDING_PAYMENT`;
   cancelar desde `PENDING_PAYMENT` devuelve stock como lo hace desde `NEW`. ✅
   `OrderServiceImpl.java`, `OrderRepository.java`.
 - **T3** (S1, S4) Tests backend: ✅ `TransferPaymentGateHttpTests` — 4/4
   verde: nacimiento por método de pago, exclusión KDS + entrada al confirmar,
   409 salteando la confirmación, stock devuelto al cancelar.
 - **T4** (S2, S3, S5) Frontend consola de Pedidos: ✅ botones preparar/listo/
   entregado fuera (`OrderListCard.tsx`); acción única Aceptar / "Confirmar y
   aceptar" (transferencia) → PATCH `status=NEW`; pill y labels "Pago
   pendiente" (`api.ts`, `Orders.tsx`); ding también para
   `PENDING_PAYMENT` (`useOrdersFeed.ts`); estilo `--accept`
   (`Orders.css`). Vitest: 59 archivos, 465 tests, verde
   (`orders-transitions.test.tsx` reescrito al nuevo contrato).
 - **T5** Verificación: ✅ `mvn -B --no-transfer-progress -Pfrontend verify`
   → BUILD SUCCESS (498 tests backend); suite Vitest 465 tests verde;
   build de Vite verde (warning de chunk size preexistente).

## Commits

 - `85e5435` feat(orders): payment gate — TRANSFERENCIA orders start
   PENDING_PAYMENT (backend + tests, 280 líneas).
 - `2994f41` feat(orders): console accepts orders — kitchen transitions move
   to the KDS (frontend + tests).
 - `bac9bdf` docs(odd): track transfer-payment-gate feature (L1-L2).

 Nota: el contador de slice de la rama mide contra `origin/main`, pero la
 rama sale de `develop` (que ya traía ~3.1k líneas propias); cada unidad
 real quedó acotada.

## Log

L1 (pedido original, verbatim): "quiero agregar una accion sobre los pedidos con
metodo de pago trasnferencia para marcarlos como pagado una vez que el operador
compruebe que la tranferencia fue realizada y ahi recien esa comanda pasaria al
kds y tambien sacar las acciones de comenar, marcar listo y entregado por que
eso es parte del flujo del kds. El flujo de Pedidos deberia ser Aceptar,
Cancelar, imprimir y confirmar el pago solo en aquellos pedidos que llegan dle
menu publico con el metdo de pago por transferencia, que te parece ?"

L2 (decisiones del usuario, Q&A): todo `TRANSFERENCIA` pende del pago (sin
campo de origen); una sola acción de aceptar/confirmar.
