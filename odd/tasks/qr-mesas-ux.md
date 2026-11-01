# QR y mesas: la configuración se explica sola

Pestaña "QR y mesas" de la vista Local del panel admin. El pedido fue de UX:
la pantalla no explicaba qué hace cada QR, cada formato ni el alta por lote.
Se reescribió la pestaña para que el dueño entienda sin leer código ni probar.

## Specs

- S1: "en la configuracion del local qr y mesas el diseño ux flaquea, haz algo
  mejor para que el usuario entienda".
- S2: diferencia explícita entre los dos QR, en la propia pantalla. El QR de
  cada mesa codifica `{slug}?table={id}` (la carta se abre con esa mesa
  identificada y el pedido llega con su número de mesa —
  `MenuUrlBuilder.buildTableMenuUrl`); el QR del menú público codifica
  `{slug}` y no identifica mesa (mostrador, puerta, redes).
- S3: cada plantilla de PDF dice qué imprime y cuántos QRs entran por hoja
  (`SINGLE` 1, `GRID_3X3` 9, `GRID_4X4` 16, `ACRYLIC` tarjeta grande).
  `QrTemplate.GRID_4X4` existía en el enum SIN estrategia: `PdfTemplateFactory`
  tiraba "Unsupported template" y el endpoint respondía 500. Se implementó
  `Grid4Template` en vez de esconder la opción (L2), así que la UI ofrece las
  cuatro.
- S3b: `GRID_4X4` imprime 16 QRs por hoja A4 (4×4) con el nombre de la mesa
  arriba, no pierde la fila final incompleta (`PdfPTable.completeRow()`, la
  lección de `Grid3RowRenderingTests`) y pagina sin perder mesas cuando hay
  más de 16. El QR se genera a 4× del tamaño impreso para que no salga
  borroso (~288dpi en vez de ~72dpi).
- S4 (reescrita en L3 y L4): el alta por lote pide cuántas mesas NUEVAS se
  agregan y el backend las numera con los números LIBRES más bajos (huecos de
  numeración y números liberados por una baja); si no hay huecos sigue después
  de la más alta. `POST /api/tables/bulk {quantity}` AGREGA `quantity` mesas —
  antes completaba 1..quantity salteando las existentes, así que con Mesa 1..12
  pedir 3 no creaba nada, y numerar siempre después de la última dejaba al
  dueño con Mesa 1, 3, 4 sin forma de recuperar la 2. Fijado por
  `TableEndpointsHttpTests.bulkAddsRequestedNewTablesContinuingTheNumbering`,
  `bulkReusesTheNumberFreedByADeletedTable` y
  `bulkFillsGapsBeforeExtendingTheNumbering`. La UI dice "Cantidad de mesas
  nuevas" y anticipa la numeración exacta.
- S4b (L4): una mesa dada de baja conserva su fila (historial de pedidos y QR
  ya impreso) pero LIBERA su número: también para crear y renombrar, no sólo
  para el alta por lote. La unicidad de número se evalúa contra las mesas
  vivas (`existsByBusinessIdAndTableNumberAndStatusNot`). Fijado por
  `TableEndpointsHttpTests.deletedTableNumberIsFreeForCreateAndUpdate`, y los
  409 por duplicado siguen valiendo entre mesas vivas
  (`TableStatusHonestyHttpTests`).
- S5b (L4): una mesa dada de baja no existe públicamente — su QR deja de
  servir, que es lo que el panel promete al confirmar la baja. `GET
  /api/menu/{slug}/table/{id}` y `POST /api/orders/menu/{slug}` con
  `orderType: SALON` responden 404 "Mesa no encontrada", el mismo 404 que un
  id inexistente (sin filtrar que la mesa existió).
- S5: cada mesa muestra la URL real a la que apunta su QR (`qrUrl` del
  response), y "Ver QR" abre una vista previa del PNG sin descargarlo.
- S6: en mobile la lista de mesas se lee sin scroll horizontal: filas de
  tarjeta (nombre + estado + URL + acciones) en vez de `<table>`, y la tira de
  pestañas de Local scrollea sola (antes recortaba la primera pestaña, que
  quedaba inalcanzable en 390px).

## Tasks

- T1 (S1-S5): reescritura de `frontend/src/pages/admin/QrTablesSection.tsx` —
  bloque de introducción que separa los dos QR, tarjeta por QR público (con
  "Ver la carta"), lista de mesas, alta por lote con preview de numeración y
  bloque de impresión con la ayuda de cada plantilla. ✅
- T2 (S2, S3, S4, S5): estilos en `frontend/src/pages/Admin.css` —
  `.admin-qr-intro/-card/-hint/-list/-row/-status/-plan/-print*`, más
  `overflow-x` en `.admin-local-tabs`. ✅
- T3 (S1-S6): specs en `frontend/tests/admin-local-tabs.test.tsx` (7 casos
  nuevos, uno por regla) + fixture `qrUrl` con `?table=`. ✅ vitest
  tests/admin-local-tabs.test.tsx 19/19, suite 62 archivos OK (las 2 fallas de
  `build-artifacts.test.ts` son previas al cambio: dependen de un
  `npm run build:spring` que no corre en este worktree), lint + `tsc -b` OK,
  e2e visual con API mockeada en mobile 390 y desktop 1280.
- T4 (S3b): `service/.../strategies/impl/Grid4Template.java` — estrategia
  `GRID_4X4` que faltaba (4 columnas, 110pt por QR, padding 6pt, nombre en
  10pt, QR a 4× de píxeles, `completeRow()`). ✅
- T5 (S3b): `api/src/test/java/com/carrito/saas/Grid4TemplateTests.java` — 6
  tests: wiring de la fábrica, 16 mesas en una página, fila final incompleta,
  40 mesas paginando sin perder ninguna, URL pública por mesa y el endpoint
  `?template=GRID_4X4` devolviendo PDF. RED observado sacando la estrategia
  (6/6 fallan con `Unsupported template: GRID_4X4`), GREEN 6/6; regresión de
  las otras plantillas 23/23 (`QrPdfTemplateUrlTests`,
  `Grid3RowRenderingTests`, `TableQrPdfContractTests`,
  `TableEndpointsHttpTests`). PDF real rasterizado y revisado: 16 QRs en una
  A4, fila incompleta rellenada, 40 mesas en 2 páginas. ✅
- T6 (S3): la UI vuelve a ofrecer `GRID_4X4` ("Grilla 4×4 (16 por hoja)") con
  su ayuda, y el spec del frontend pasó de exigir su ausencia a exigir que el
  PDF general pida `template=GRID_4X4`. ✅
- T7 (S4): `TableServiceImpl.generateBulk` agrega N mesas desde
  `max(tableNumber) + 1` en vez de completar huecos; los dos tests HTTP del
  bulk se reescribieron (RED: fallaban con la implementación vieja creando 0
  mesas; GREEN: 12/12 en `TableEndpointsHttpTests`). ✅
- T8 (S4): UI del alta — campo "Cantidad de mesas nuevas", ayuda con la
  numeración (y caso local vacío), preview `Mesa 13 a Mesa 15` + total
  resultante, y confirmación con los nombres creados. Specs del frontend
  actualizados; vitest 19/19 en la pestaña. ✅
- T9 (S4, S4b): `TableServiceImpl.generateBulk` numera con los números libres
  más bajos (huecos y números liberados por baja) y los validadores de
  `create`/`update` dejaron de contar las mesas DISABLED
  (`existsByBusinessIdAndTableNumberAndStatusNot`). Evidencia: RED de los tests
  nuevos contra la implementación anterior (creaba 0 mesas), GREEN
  `TableEndpointsHttpTests` 16/16 + `TableStatusHonestyHttpTests` 8/8. ✅
- T10 (S5b): `MenuServiceImpl.getPublicTable` y el bloque SALON de
  `OrderServiceImpl` responden 404 "Mesa no encontrada" para una mesa
  DISABLED — el mismo 404 que un id inexistente. RED observado en ambos
  (esperaba 404, respondía 200), GREEN `MenuTableEndpointFailClosedHttpTests`
  4/4 y `SalonOrderTableTests` 7/7; regresión de los caminos felices
  `MenuTableEndpointHttpTests` 3/3 y `CreateOrderWithoutTableTests` 1/1. ✅
- T11 (S5b): `TableServiceImpl.generatePdfAll` filtra las mesas DISABLED (no
  imprime QRs muertos) y responde 404 "No hay mesas activas para generar el
  PDF" si no queda ninguna viva. Tests en `TableEndpointsHttpTests`
  (texto extraído del PDF: Mesa 1 y 3 sí, Mesa 2 no). ✅
- T12 (S6b): UI — `AdminTable.status`, filas de mesas eliminadas ("Eliminada",
  "Su QR ya no funciona.", sin URL ni acciones), resumen que cuenta sólo las
  vivas, y preview del alta con la misma asignación de números que el backend;
  el bloque de impresión se oculta si no hay mesas vivas. Specs S7/S8 (RED
  2 fallas → GREEN 21/21 en la pestaña). ✅

## Commits

| Slice | Commit | Contenido |
| --- | --- | --- |
| 1 | `c7a33c6` | `feat(service)`: plantilla `GRID_4X4` + `Grid4TemplateTests` (T4, T5) |
| 2 | `93e48db` | `fix(service)`: baja de mesa real — numeración libre, fail-closed de carta/pedidos, PDF sin QRs muertos (T7, T9, T10, T11) |
| 3 | `25cf740` | `feat(frontend)`: pestaña QR y mesas — componente, estilos y specs (T1, T2, T3, T6, T8, T12) |
| 4 | `f354b37` | `docs(odd)`: esta ficha |
| 5 | `376bd75` | `fix(service)`: corrección del hallazgo R3 (el template 4×4 honra el DTO) |

Frontera de slices: la rama `worktree/quiet-field-ddd5` acumula 113 commits
previos ajenos a esta feature (`origin/main..HEAD`); lo de acá son los cuatro de
la tabla. El slice 3 supera la referencia de ~400 líneas porque es la
reescritura de un solo componente con sus estilos y sus specs: partirla dejaba
un estado intermedio con la UI prometiendo algo que el backend todavía no hacía.

## Review

Revisión nativa (RDD) del slice 1 (`c7a33c6`, plantilla 4×4), lentes riesgo y
confiabilidad. Los dos revisores entregaron y el refuter confirmó **un hallazgo
crítico introducido por mí**:

- **R3-config-ignored** (`Grid4Template.java:90-125`): `generate()` recibía un
  `QrPdfRequestDTO` y nunca lo leía — `qrSize` y `showUrl` se descartaban y el
  QR siempre salía a 110pt con el nombre. Corregido en `376bd75`: tamaño pedido
  honrado y acotado a 40..110pt, `showUrl` imprime la URL debajo (cortada cada
  34 caracteres porque OpenPDF no parte URLs sin espacios) y
  `showTableName=false` omite el nombre. Dos tests nuevos
  (`honoursTheRequestedQrSizeAndTheOptionalUrlText`,
  `omitsTheTableNameWhenTheCallerTurnsItOff`), `Grid4TemplateTests` 8/8.

La revisión **no cerró**: el validador dirigido que tenía que aprobar la
corrección fue rechazado por la admisión del transporte dos veces, de forma
idéntica y en 214 ms ("reviewer payload contains no complete JSON object …
scan ended at byte 1"), es decir, sin llegar a producir veredicto. El slot no
se consumió y no hubo mutación de autoridad; por decisión del dueño (L5) la
revisión queda bloqueada y se sigue con la política normal del repo. Los
slices 2 y 3 (`93e48db`, `25cf740`+`f354b37`) no llegaron a revisarse: quedan
como deuda explícita, no como aprobados.

Nota: el candidato global de 1835 líneas tampoco entra en el presupuesto de
contexto de los lentes (`lens_context_budget_exceeded` al arrancar), así que la
única forma de revisar esta feature es por slices.

## Log

- L1 (usuario): "en la configuracion del local qr y mesas el diseño ux flaquea,
  haz algo mejor para que el usuario entienda".
- L2 (usuario): "si hacelo", eligiendo explícitamente la opción "Grilla 4x4:
  implementar Grid4Template" (backend: nueva estrategia de PDF) en vez de
  cambiar la semántica del alta por lote.
- L3 (usuario): "si hazlo" sobre la decisión que había quedado pendiente: el
  alta por lote pasa a agregar N mesas nuevas numeradas a continuación, en vez
  de completar la numeración hasta el total.
- L4 (usuario): "arregla lo que encontraste" — (a) la UI mostraba una mesa dada
  de baja como "Libre", (b) la numeración no reutilizaba el número de una mesa
  borrada. Al mirar (a) apareció el defecto de fondo: una mesa DISABLED seguía
  sirviendo en la carta pública y aceptando pedidos, así que la promesa del
  diálogo de baja ("su QR dejará de funcionar") era falsa.
- L5 (usuario): "Dejar la revisión bloqueada y seguir" — el defecto de admisión
  del validador dirigido no se reintenta; la feature sigue con política normal.
