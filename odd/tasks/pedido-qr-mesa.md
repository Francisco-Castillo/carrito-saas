# Feature: pedido-qr-mesa

**Branch**: `worktree/lucky-field-e594` (worktree de `carrito-saas`)
**Status**: **cerrado (2026-10-19)**. 4 commits: `f865dbc` (T1), `84e53c0` (T2), `5c16ea0` (T3), `188ae31` (tests de regresión de la verificación).
**Verificación independiente**: `gentle-ai-verify` sobre `HEAD~3..HEAD` (copia scratch, sin escribir en el workspace) — todos los puntos **MET**, sin bloqueos. Re-ejecutó backend enfocado (14/14) y frontend (4/4), más 10 probes propios (cero negocios → 404, negocio de baja → 404, parámetros `?table=` inválidos, fallo de red, QR viejo sin param, `pos.ts`/`OrderRequestDTO`/`OrderServiceImpl` intactos en el rango). Sus recomendaciones se commitearon como tests de regresión (`188ae31`); suites finales: backend 386/0/2 skips pre-existentes, frontend 285/285, `tsc --noEmit` limpio.
**Origen**: cierra el gap #1 dejado abierto por `odd/tasks/table-qr-links.md` ("Per-table identity is dropped from the QR").

## Objective

Que un pedido hecho desde el menú público abierto escaneando el QR de una mesa quede identificado con esa mesa (`orderType=SALON` + `tableId`), de punta a punta: el QR de cada mesa encodea su mesa, el menú la lee, y el pedido entra a la cocina con la mesa asignada.

## Specs

- **S1** — Verbatim del usuario: «lo que quiero es que se identifique que el pedido realizazdo desde una mesa atra vez del qr de la misma».
- **S2** — Decisión del usuario (batch de preguntas, respuesta literal: «Fijar Salón»): cuando el cliente entra desde el QR de una mesa, el tipo de pedido queda fijo en SALON; el selector de tipo de pedido no aparece en ese modo.
- **S3** — Decisión del usuario (respuesta literal: «Badge con mesa»): el cliente ve en el carrito el nombre/número de su mesa, via un endpoint público chico que devuelve los datos de la mesa.
- **S4** — Verbatim del usuario: «lo ideal es que no se pueda pagar o elegir metodo de pago cuando se pide desde una mesa ?». Pedido desde mesa: sin selector de forma de pago y sin `paymentMethod` en el payload — el pago pasa en la mesa/caja. RETIRO/DELIVERY siguen exigiéndolo; el POS no cambia.

## Design decisions (recordadas)

1. **El QR encodea `?table=<id numérico>`** (no el `qrToken`). El id ya es la clave que `OrderRequestDTO.tableId` y el POS usan; una sola convención. Riesgo aceptado y documentado: el endpoint de pedidos es público, así que cualquiera puede presentar un `tableId` arbitrario del negocio — igual que hoy en el POS — y el impacto se limita a atribuir su propio pedido a otra mesa. `OrderServiceImpl` ya valida que la mesa pertenezca al negocio resuelto (`findByIdAndBusinessId`).
2. **Fallback honesto**: si `?table=` no está, es inválido, o el endpoint público responde 404/error, el menú cae al comportamiento actual (RETIRO/DELIVERY, sin badge). Un QR viejo (sin `?table=`) sigue funcionando.
3. **El nombre de mesa viaja de `/api/menu/table/{id}`** (nuevo, bajo `/api/menu/**` que ya es `permitAll`), resuelto contra el único negocio del deployment (mismo patrón slugless de `MenuController.getMenu`), 404 si no existe o es ajena.

## Tasks

- **T1** [S1] — Backend QR: `MenuUrlBuilder` gana `buildTableMenuUrl(slug, tableId)` → `<base>/menu/?table=<id>`; `TableServiceImpl.generateQr` y los tres templates PDF (`SingleTemplate`, `Grid3Template`, `AcrylicTemplate`) la usan por mesa. Tests RED-first: actualizar `TableQrServiceLinkTests` y `QrPdfTemplateUrlTests`. Route: backend. Commit: `f865dbc`.
- **T2** [S3] — Backend endpoint público: `GET /api/menu/table/{id}` → DTO mínimo `{id, tableNumber, tableName}` scopeado al único negocio (404 si no existe o es ajena). Test RED-first HTTP. Route: backend. Commit: `84e53c0`.
- **T3** [S1,S2,S3] — Frontend: `Menu.tsx` lee `?table=`, fija SALON, muestra badge con la mesa en `CartPanel` (selector de tipo oculto), y el submit envía `orderType:"SALON"` + `tableId`; fallback a menú normal si la mesa no resuelve. Vitest RED-first. Route: frontend. Commit: `5c16ea0`.
- **T4** [S4] — Tests de regresión de la verificación independiente (fail-closed del endpoint, fallback de `?table=` inválido/ausente). Route: backend+frontend. Commit: `188ae31`.
- **T5** [S4] — Backend: `createOrder` acepta `paymentMethod` null SOLO para SALON (se paga en mesa); RETIRO/DELIVERY mantienen el 400 «Debe indicar método de pago». HTTP RED-first. Route: backend. Commit: `59d6292`.
- **T6** [S4] — Frontend: en modo mesa, `CartPanel` oculta el grupo «Forma de pago» y el submit no envía `paymentMethod`; sin mesa no cambia nada. Vitest RED-first. Route: frontend. Commit: `32e2ea9`.
- **T7** [S4] — Hallazgo del entorno: la suite completa (`mvn verify`, 417 tests) falla con 6 errores de carga de contexto en `PredictionRunPrivilegeHttpTests` cuando el backend demo corre en paralelo — causa raíz `FATAL: sorry, too many clients already` (Postgres `max_connections=100`, la suite + el pool del backend la agotan). NO es un defecto del código: con los procesos `java -jar` detenidos, `mvn verify` completo queda verde (417/0/0, EXIT=0). Pre-existente, no ligado a S4.

## Log

- **L1** — Pedido original verbatim: «lo que quiero es que se identifique que el pedido realizazdo desde una mesa atra vez del qr de la misma».
- **L2** — Decisión de producto (usuario, vía preguntas): fijar Salón desde QR de mesa (sin selector) y mostrar badge con el nombre/número de la mesa.
- **L3** — Verificación independiente (gentle-ai-verify, 2026-10-19): sin bloqueos; advisories (tests fail-closed y fallback de parámetro no pinneados; race teórica de un submit en la ventana previa al fetch de la mesa — inalcanzable en uso normal y rechazada por el backend) cerrados con los tests de regresión de `188ae31` y aceptación explícita de la race.
- **L4** — Verbatim del usuario: «esta perfecto, ahora lo ideal es que no se pueda pagar o elegir metodo de pago cuando se pide desde una mesa ?» + «continua». Alcance: sólo el pedido desde QR de mesa; RETIRO/DELIVERY y POS sin cambios.
- **L5** — Verificación de T5: RED observado en dos pasos (400 por la validación vieja, luego 500 por NPE en `OrderMapperImpl.toDTO` con enum null) — ambos cubiertos por el fix. 51 tests de las 13 clases que tocan `paymentMethod` re-corridos verdes. Suite completa verde 417/417 una vez aislado el conflicto de conexiones (T7).
