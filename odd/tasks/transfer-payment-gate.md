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

L3 (revisión nativa RDD, activada por el usuario): lineage
`review-fe58cb3f6d9d45be`, riesgo medio, lente `review-reliability`, 12
archivos / 2043 líneas → **approved** (receipt quemado,
`gentle-ai.review-acknowledged/v1`). Hallazgos NO bloqueantes
(informational, trabajo posterior, no reabren la revisión):
 - R3-print-unconfirmed (WARNING, useOrdersFeed.ts:63-70): el auto-print y el
   ding disparan también para pedidos PENDING_PAYMENT (comanda de una
   transferencia aún sin confirmar).
 - R3-removed-toast (WARNING, Orders.tsx:130-133): toast de PREPARING quedó
   como código muerto (la consola ya no puede transicionar a PREPARING).
 - R3-test-substr (SUGGESTION, TransferPaymentGateHttpTests.java:163-168):
   aserción por substring sobre JSON serializado puede dar falso positivo.

L4 (corrección de los 3 hallazgos, commit `afe72d3`): auto-print sólo para
pedidos que nacen NEW (la transferencia sin confirmar ya no imprime comanda;
el ding sigue en ambos), toast PREPARING muerto eliminado, aserciones del
feed KDS estructurales por orderId. RED→GREEN con test diferencial de
auto-print (`orders-comanda.test.tsx`, 11 tests). Suite completa: 466 tests
frontend + 4/4 backend, verde. L5 (corrección de desincronización del POS, reportada por el usuario): el
POS de mostrador creaba la orden con TRANSFERENCIA y quedaba en "Pago
pendiente" exigiendo un segundo click al operador que acaba de cobrar.
Ahora el POS confirma el pago en el mismo paso (create → PATCH NEW; si el
PATCH falla, warning y la consola lo confirma a mano), y la confirmación
de pago (manual o del POS) dispara el auto-print de la comanda via feed
(PENDING_PAYMENT → NEW). Tests: orders-pos-flow.test.tsx (2) y comanda
(1). Sin cambios de backend.

Nota de entorno: un `pnpm build` suelto deja
las entradas legacy con bundle viejo (falla `build-artifacts.test.ts`);
`pnpm build:spring` regenera todas.
