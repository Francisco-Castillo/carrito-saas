# Feature: orders-console

Pantalla de gestión de pedidos (mostrador): visión de todos los estados del día, levantamiento manual de pedidos (POS completo), cancelación con motivo y detalle de pedido. Página propia `/pedidos` al nivel de Dashboard y KDS.

## Specs

- **S1** — Pedido original del usuario, verbatim: «creo que nos falta una pantalla para poder gestionar los pedidos que llegan, pasarlos al kds de la cocina, levantar pedidos desde el mostrados si hace falta, cancelar pedidos, ver el estado de un pedido. En sintesis una mejor microgestion».
- **S2** — POS completo (respuesta a «¿Qué tan completo querés levantar pedidos desde el mostrador?»): «Elegís productos del menú con búsqueda, tipo de pedido (salón/retiro/delivery), mesa si aplica, datos del cliente, método de pago y notas. Es lo que hace el flujo del cliente pero operado por vos.»
- **S3** — Ubicación (respuesta a «¿Dónde vive la pantalla de gestión de pedidos?»): «Una vista nueva (ej. /pedidos) al nivel del Dashboard y el KDS, con login por sesión y navegación desde la pantalla principal.»
- **S4** — Alcance (respuesta a «¿Qué pedidos tiene que mostrar esta pantalla?»): «Columnas o filtros por todos los estados, incluidos Entregados y Cancelados (con motivo). Visión completa del día; el KDS queda como vista de cocina únicamente.»

## Estado del código verificado (2026-10-14)

- Backend: `POST /api/orders/menu/{slug}` (y variante slugless), `PATCH /api/orders/{id}/status?status=X` con `canTransitionTo` (409 en transición inválida), `PATCH /api/orders/{id}/cancel` con motivo, `GET /api/business/orders/active` → `OrderKitchenDTO` (delgado: sin total, sin paymentMethod, sin dirección, sin mesa, sin timeline, sin motivo de cancelación), WebSocket `/topic/orders/{slug}`.
- `OrderRepository.findActiveOrders` excluye `DELIVERED` y `CANCELLED`: **no existe** endpoint que devuelva los pedidos del día con todos los estados.
- `OrderType` = RETIRO | DELIVERY — **no existe SALON**. La entidad `Order` tiene `restaurantTable` pero `createOrder` nunca la setea y `OrderRequestDTO` no trae `tableId`.
- Frontend React (`frontend/src`): páginas Login, Menu, Dashboard, Admin (secciones productos/categorías/combos/mesas), Kds (kanban NEW/PREPARING/READY + CancelOrderModal + sonido + WebSocket). Routing por `view.ts` (familia legacy + paths limpios) → `App.tsx`.
- Productos para el POS: `GET ${API}/products` con header auth ya usado por `ProductsTable`.
- Tests: `mvn verify` (backend), Vitest en `frontend/tests/` (patrón session-guard, login-redirect, etc.).

## Contrato implementado (T1+T2, commit 68dc55d)

- `GET /api/business/orders?date=YYYY-MM-DD` (date opcional, default `LocalDate.now()` server-local, igual que el dashboard) → `OrderManagementDTO[]`: `orderNumber`, `orderType`, `status`, `customerName/Phone/Address`, `paymentMethod`, `notes`, `table` (`OrderManagementTableDTO` id+name cuando hay), `total`, `createdAt`, `items` (`OrderManagementItemDTO`: name, quantity, unitPrice, subtotal), `timeline` (`preparingAt/readyAt/completedAt/cancelledAt`), `cancellationReason` (id+description) y `cancellationNote`.
- `OrderType.SALON` nuevo; `OrderRequestDTO.tableId` opcional. SALON exige mesa del propio negocio (404 «Mesa no encontrada» si es ajena); RETIRO/DELIVERY nunca setean mesa.
- `OrderDTO` ahora expone `tableId` (siempre) y `tableName` (solo con referencia inicializada — guard `Hibernate.isInitialized`).
- `findActiveOrders` quedó byte-idéntico: el KDS no cambia.

## Tasks

- **T1** (S4) Backend — pedidos del día con todos los estados: método de repository (business del JWT, fecha, sin filtro de estado), servicio, endpoint `GET /api/business/orders` (o `/orders/today`) y DTO de gestión rico (total, paymentMethod, orderType, dirección, mesa, timeline `preparingAt/readyAt/completedAt/cancelledAt`, `cancellationReason`/`cancellationNote`, ítems con precio). No romper `findActiveOrders` ni el KDS. Route: worker backend. Commit: `feat(api): management orders endpoint with full-day all-status view`.
- **T2** (S2) Backend — pedidos de salón con mesa: `OrderType.SALON`, `tableId` opcional en `OrderRequestDTO`, `createOrder` setea `restaurantTable` validando pertenencia al negocio; SALON exige mesa, RETIRO/DELIVERY la rechazan o ignoran (decisión: SALON → obligatoria, resto → no se setea). `GET` del T1 expone mesa. Route: worker backend. Commit: `feat(api): salon orders with table assignment`.
- **T3** (S3, S4) Frontend — página Orders `/pedidos`: vista de todos los estados del día (columnas o filtros) con datos del `OrderManagementDTO`, WebSocket en vivo (`/topic/orders/{slug}`), avanzar estado respetando transiciones (409 → toast), cancelar con motivo (reusar patrón `CancelOrderModal`), detalle de pedido. Guard de sesión igual al resto de los paneles (`hasPanelSession`/`enforceSession`). Route: worker frontend. Commit: `feat(frontend): orders management page`.
- **T4** (S2) Frontend — POS de mostrador dentro de la página Orders: catálogo con búsqueda (endpoint products del admin), carrito, tipo de pedido (SALON/RETIRO/DELIVERY), mesa para SALON, cliente, método de pago, notas; crea vía API existente y refresca la vista. Route: worker frontend. Commit: `feat(frontend): counter POS order creation`.
- **T5** (S3) Frontend — routing y navegación: `/pedidos` (+ `/pedidos/index.html`) en `view.ts`, `App.tsx`, entrada desde la pantalla principal (dashboard/admin nav), routing post-login actualizado si corresponde. Commit: `feat(frontend): route and navigation for orders console`.
- **T6** Verificación: `mvn -B --no-transfer-progress verify` + suite Vitest + build de Vite; ensayo manual de la pantalla completa.

## Log

- **L1** — «creo que nos falta una pantalla para poder gestionar los pedidos que llegan, pasarlos al kds de la cocina, levantar pedidos desde el mostrados si hace falta, cancelar pedidos, ver el estado de un pedido. En sintesis una mejor microgestion»
- **L2** — Decisiones del usuario (ask_user_question): POS completo (S2); página propia (S3); todos los estados (S4).
- **L3** — Verificación del padre (2026-10-18): `mvn verify` completo → 318 tests, 50 errores en 5 suites HTTP (PanelErrorStatus 22, PredictionEndpointHonesty 5, TableEndpoints 11, TableStatusHonesty 8, PredictionRunPrivilege 4), todos `duplicate key ... uq_roles_name` al sembrar OWNER. **Preexistentes**: los mismos 5 suites con las mismas cifras fallan sobre HEAD limpio (stash + re-run). Causa ambiental: la DB local de tests (`carrito_db`) tiene una fila OWNER persistente fuera de todo rollback. Ítem abierto propio, no de este feature.
- **L4** — Decisión de DB local (reportada por el worker): el CHECK `orders_order_type_check` existe solo en la `carrito_db` local (sin migración ni schema.sql); se ensanchó con `ALTER TABLE ... CHECK (order_type IN ('RETIRO','DELIVERY','SALON'))`. Sin él, ningún SALON inserta (500). En DBs frescas de CI no existe el constraint, así que los tests pasan igual. Pendiente de decisión del equipo: migración formal o eliminación del constraint.
- **L5** — «today» del endpoint nuevo sigue la convención existente (`LocalDate.now()` server-local, igual que el dashboard): no hay timezone por negocio en el código.
- **L6** — Commit `68dc55d` en `feat/orders-console` (rama creada desde `develop`). Sin push. El documento se versiona con el próximo commit de trabajo.
- **L7** — El worker no commitea (regla del harness); el commit lo hizo el padre tras verificar. Tests de preservación en verde: CreateOrderWithoutTableTests, OrderStatusTransitionHttpTests, OrderKitchenCustomerPhoneTests, PublicMenuOrderHttpTests, PublicMenuSluglessHttpTests, PublicMenuSluglessOrderWsTests, AnonymousMenuOrderSecurityTests, OrderListMappingTests, TenantScopedQueryContractTests.
- **L8** — T3–T5 verificados por el padre (2026-10-18): suite Vitest completa 253/253 re-corrida; spot-check de review focus (payload del POS inerte y verídico, `tableId` solo SALON, dirección solo DELIVERY, debounce+cleanup del feed, 409 → mensaje del servidor + re-sync). Los eventos WS traen el DTO slim de cocina, no el ManagementDTO: el feed nunca parchea estado local, agenda re-fetch con debounce de 250ms.
- **L9** — Defecto preexistente bloqueante encontrado en T6: `tsc -b` fallaba con 3 TS2339 en `Menu.tsx` (`primaryColor` leído pero no declarado en `StorefrontBusiness`, del commit 497039a). Arreglado inline en commit `12c4432` (fix separado, fuera del alcance del feature pero necesario para el build). Build completo verde: 299ms, chunk principal 548KB (warning de tamaño preexistente).
- **L10** — El build de Vite escribe a `api/src/main/resources/static/assets/` (gitignoreado): el SPA se sirve desde el jar de Spring. Un cambio de frontend no se ve hasta rebuild del jar + restart.
- **L11** — Decisión: post-login no redirige a /pedidos (el mapa rol→destino es 1:1 y cambiarlo toca comportamiento existente). Acceso por nav de Dashboard/Admin. La pantalla no tiene gate de rol en cliente, igual que Dashboard/KDS — la autorización real es server-side (403).

## Progress

| Task | Estado | Evidencia |
| --- | --- | --- |
| T1 | hecho | Commit `68dc55d`. RED literal: día-view 404, SALON rechazado por el enum, mesa ajena 400≠404. GREEN: 7/7 (`Tests run: 7, Failures: 0, BUILD SUCCESS`). PRESERVE: 20 tests del camino de pedidos en verde |
| T2 | hecho | Mismo commit. RED: `salonOrderWithoutTableIsRejected`, `salonOrderWithForeignTableIsRejected`, `salonOrderIsCreatedWithItsTable`. GREEN incluido en los 7/7 |
| T3 | hecho | RED: 7 failed / 8 total + 5 imports rotos (módulos ausentes). GREEN: 31/31 en los 7 archivos nuevos. Full suite: 253/253. Agrupación: pills de filtro con conteos («Todos» default) — 5 estados con tarjetas ricas no entran lado a lado; el KDS queda como kanban exclusivo de cocina |
| T4 | hecho | POS con búsqueda sin diacríticos, combos, mesa solo para SALON, dirección solo para DELIVERY; crea vía POST existente autenticado. Payload builder (`pos.ts`) puro con tests propios |
| T5 | hecho | Routing `/pedidos` + `/pedidos/index.html` + `?view=pedidos` en view.ts; nav «Pedidos» en Dashboard topbar y Admin sidebar. Login intacto: el mapa rol→destino es 1:1 y no hay equivalente de una línea (L11) |
| T6 | en curso | Vitest 253/253 (padre); `tsc -b && vite build` verde tras fix `12c4432`; falta rebuild jar + verificación servidor + ensayo |

## Open gaps and decisions

1. **Constraint `order_type` local (L4)**: migración formal vs eliminación — decisión del equipo.
2. **DB de tests contaminada (L3)**: 5 suites HTTP fallan por la fila OWNER persistente en la DB local. Preexistente; no bloquea este feature (los suites del feature corren verdes), pero impide un `mvn verify` verde completo.
