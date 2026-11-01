# Feature: categoria-review-advisories

Cerrar los tres hallazgos informativos de la revisión nativa del PR #94
(`review-b0484bb596de677e`, lente `reliability`, aprobada el 2026-11-01):
`R3-client-order-ignored`, `R3-nextorden-race` y `R3-todas-auth-parity`.

**Limitación registrada**: el texto de los revisores no es recuperable del
store local del motor — el recibo de consumo
(`.git/gentle-ai/review-transactions/terminal-consumption/v1/`) guarda sólo
target y lineage, igual que pasó con la serie de reviews del panel
(`odd/tasks/review-findings-backlog.md`). Cada hallazgo se reconstruye leyendo
el código en la ubicación que declaró el cierre, no desde su texto.

## Specs

S1. `CategoryDTO.order` deja de sorprender: el javadoc dice que en el alta
    (`POST /api/categorias`) el campo se **ignora** porque el servidor asigna
    `max(orden) + 1`, que en la edición (`PUT /api/categorias/{id}`) sí se
    aplica cuando viene, y que el reordenamiento del panel usa su propia ruta
    (`PUT /api/categorias/orden`). Sin cambio de comportamiento.

S2. El alta de categoría está **serializada por negocio**: antes de calcular
    `max(orden) + 1` toma la fila del negocio con `PESSIMISTIC_WRITE`
    (`BusinessRepository.findByIdForUpdate`), así dos altas concurrentes del
    mismo negocio no leen el mismo máximo. Se prefiere el lock a un índice
    único sobre `(business_id, orden)` porque no necesita migración, no puede
    fallar con los empates que ya existen en los datos y no convierte un
    problema cosmético en un error de escritura.

S3. `/api/categorias/todas` conserva el anclaje del tenant: el negocio sale del
    JWT, así que un token de otro negocio no ve sus categorías —incluidas las
    inactivas, que son justo la superficie que la ruta nueva expone—.

S4. No-acción registrada: no se endurece el guard de `/todas` más allá de
    `authenticated()`. En `SecurityConfig` la ruta cae en
    `.requestMatchers("/api/**").authenticated()`, exactamente la misma paridad
    que `/api/categorias` (y que el resto de los endpoints autenticados del
    panel); el aislamiento por negocio vive en el JWT y quedó pinneado en S3.

S5. Las tres escrituras que deciden el orden quedan serializadas por negocio, no
    sólo el alta: `actualizarCategoria` (cuando el body trae `order`) y
    `ordenarCategorias` (`1..n`) toman el mismo lock mediante un único
    `lockCurrentBusiness()`, porque las tres son read-then-write sobre `orden`.
    Cubierto por `CategoryWriteSerializationTests`, que prueba las tres (alta,
    edición y reordenamiento) con el mismo mecanismo: sostener la fila desde
    otra conexión y exigir que la escritura espere y después aplique su efecto
    (el `order` nuevo en la edición, el orden nuevo en el reordenamiento). Las
    dos escrituras nuevas se observaron en RED antes del fix.

## Tasks

- **T1** (S1) docs: javadoc de `CategoryDTO.order`. Commit: `ccfe56c`.
- **T2** (S2) fix+test: lock del negocio en el alta +
  `CategoryWriteSerializationTests`. Commit: `4cbb550`. **Riesgo: item 4
  (concurrencia)** — commit propio, con verificación independiente (L5).
- **T3** (S3) test: pin cross-tenant de `/todas` en
  `AdminCategoryListHttpTests`. Commit: `7d24d50`.
- **T4** (S4) docs: esta no-acción. Este documento — commit `bbefc5b`.
- **T5** (S5) fix+test: lock compartido en las tres escrituras de orden
  (`lockCurrentBusiness`) + `CategoryWriteSerializationTests` renombrado y
  extendido. Commit: (ver Log L6). **Riesgo: item 4 (concurrencia)** — commit
  propio.

## Log

- **L1** (pedido del dueño, verbatim): «si continua con los pendientes».
  Contexto inmediato: los pendientes que quedaban tras el merge del PR #94 eran
  borrar la rama (hecho: `fix/carta-orden-categorias` eliminada en local y en
  `origin`), los tres advisories de la revisión, y la issue sin vincular. La
  issue **no** se creó: el trabajo ya está mergeado y ninguna issue abierta
  describe este alcance; queda a decisión del dueño si quiere la convención
  estricta.
- **L2** (reconstrucción de los hallazgos): sin texto de los revisores, cada
  advisory se leyó desde su ubicación. `R3-nextorden-race` en
  `CategoryRepository.nextOrden` (read-then-write del máximo);
  `R3-client-order-ignored` en `CategoryServiceImpl.crearCategoria` (el
  `order` del cliente se ignora sin decirlo); `R3-todas-auth-parity` en
  `CategoryController` (contrato de la ruta nueva).
- **L3** (depuración del test de serialización): las dos primeras versiones
  daban verde sin el fix y por la razón equivocada. La primera chequeaba
  `isDone()` apenas después del `submit`, cuando el worker todavía no había
  arrancado. La segunda esperaba al worker pero seguía verde porque el
  `INSERT` del alta ya toma `FOR KEY SHARE` sobre la fila del negocio por la
  FK: con el test sosteniéndola con `FOR UPDATE`, el alta se bloqueaba por la
  FK y no por la ausencia del fix. La versión final sostiene la fila con
  `FOR NO KEY UPDATE` (compatible con `FOR KEY SHARE`, incompatible consigo
  mismo, que es el modo que toma el alta para calcular el lugar) y el RED se
  observó:
  «the creation must wait for the business row instead of racing the max —
  Expecting value to be false but was true». Con el lock, el test tarda ~2 s
  (la ventana en que el alta espera) y termina en verde con `orden = 3`.
- **L4** (evidencia de cierre): backend `mvn -B --no-transfer-progress verify`
  → **512/512**, BUILD SUCCESS (510 previos + el pin cross-tenant + el test de
  serialización). Sin cambios en el frontend en este lote.

- **L5** (verificación independiente, `gentle-ai-verify`): sin bloqueantes, con
  tres correcciones y advertencias que se registran acá. (a) Hibernate en
  PostgreSQL renderiza `PESSIMISTIC_WRITE` como **`FOR NO KEY UPDATE`**, no
  `FOR UPDATE` (`PostgreSQLDialect#getForUpdateString`, verificado con `javap`
  sobre `hibernate-core-7.2.4.Final`): el mecanismo sigue siendo válido —ese
  modo se bloquea consigo mismo— pero el commit y los comentarios decían
  `FOR UPDATE`, así que se corrigió el texto. (b) El test pincha la
  *presencia* del lock, no su *posición* respecto de `nextOrden`: una
  implementación que leyera el máximo antes de lockear pasaría igual. Se deja
  como límite conocido del test. (c) `ordenarCategorias` y
  `actualizarCategoria` (cuando el body trae `order`) siguen escribiendo el
  orden sin tomar el lock del negocio, así que un reordenamiento concurrente
  con un alta puede empatar o ubicar mal la fila nueva: fuera del alcance de
  S2 (dos altas), queda como hueco conocido. Se agregó además el pin del
  contrato de error de la ruta que ahora lockea
  (`aTokenForANonExistentBusinessStillAnswersNotFound`).

- **L6** (cierre de `R3-remaining-unserialized-writers`, pedido del dueño: «si
  dale hacelo como vos recomiendas»): el hueco que anotó L5 lo marcó también,
  por su cuenta, la lente `reliability` en la revisión nativa del lote
  (`review-c77980408a4aae8a`, advisory `R3-remaining-unserialized-writers`,
  WARNING informativo): dos lecturas independientes del mismo cambio
  convergieron en el mismo punto. Se cerró con un `lockCurrentBusiness()`
  compartido por las tres escrituras y dos casos nuevos en
  `CategoryWriteSerializationTests` (edición y reordenamiento), cuyos RED se
  observaron uno por uno: «the write must wait for the business row instead of
  racing the order — Expecting value to be false but was true». Backend
  `mvn -B --no-transfer-progress verify` → **515/515**, BUILD SUCCESS (513
  previos + los dos casos nuevos).

