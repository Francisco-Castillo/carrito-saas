# Feature: kds-access

## Objective

Que una persona con sesión pueda abrir la pantalla de cocina (KDS) y el dashboard, y que el login deje de rechazar el rol que realmente existe en los datos.

## Problem

El 16/09/2026 el ensayo piloto con celular pasó en la mitad del cliente (menú + pedido) y falló al entrar al KDS. La causa **no** es de datos ni del servidor: son tres defectos apilados en el frontend, cada uno verificado por ejecución.

### Hecho 1 — el login rechaza el único rol que existe

`api/src/main/resources/static/login/login.js:160-172`

```js
if (role.includes("OWNER"))        { ...dashboard... }
else if (role.includes("KITCHEN")) { ...kds... }
else { throw new Error("Rol no permitido") }
```

La rama `ADMIN` está **comentada** en la línea 168 (`//window.location.href = /admin/admin.html?restaurant=${slug};`).
En la base hay **un solo rol**: `ADMIN` (id 77, asignado al usuario 74 = `demo`), verificado con `select * from roles`.

Consecuencia: `POST /api/auth/login` responde **200** con credenciales correctas y la UI muestra "Rol no permitido". Nadie puede loguearse.

### Hecho 2 — el KDS redirige a una página que no existe, y no corta

`api/src/main/resources/static/kds/index.html:12-19`

```js
const token = localStorage.getItem("token");
if (!token) { window.location.href = "/login.html"; }   // ← ruta inexistente
const payload = JSON.parse(atob(token.split(".")[1]));  // ← sigue ejecutándose igual
```

- `GET /login.html` → **HTTP 500**. La página real es `/login/login.html` → **200**.
- `window.location.href = ...` **no aborta el script**: la línea siguiente corre con `token === null` y lanza `TypeError` en `token.split`.
- Dato duro de la divergencia: `dashboard/index.html:14` usa la ruta **correcta** (`/login/login.html`). Es un guard copiado entre páginas que quedó desincronizado.

### Hecho 3 — el guard de rol no puede funcionar nunca

`kds/index.html:21-28` y `dashboard/index.html:18-25` leen `payload.roles` del JWT y llaman `roles.includes("KITCHEN"|"OWNER")`.

El token **no lleva claim `roles`**: `security/src/main/java/com/carrito/saas/security/JwtUtil.java` sólo emite `subject` + `businessId` (+ `iat`/`exp`). Payload real decodificado del token de `demo`:

```
{"sub":"demo","businessId":900000001,"iat":1789582774,"exp":1789669174}
```

En `JwtFilter.java:55-58` la llamada a `jwtUtil.extractRole(token)` está **comentada porque ese método no existe**. `OWNER` y `KITCHEN` no aparecen en **ningún** archivo Java: existen sólo en `login.js`.

Consecuencia: `payload.roles` es `undefined` → `roles.includes(...)` lanza `TypeError` → el script de la página muere **incluso con un token válido**. Sembrar un usuario con rol `KITCHEN` no arregla nada.

### Lo que sí funciona (verificado, no asumido)

| Chequeo | Resultado |
| --- | --- |
| `GET /api/business/orders/active` con el token de `demo` (rol ADMIN) | **200**, 1151 bytes, con los pedidos |
| El mismo endpoint sin token | **403** |
| `GET /kds/index.html` anónimo | 200 (es un estático; sólo su API pide token) |
| Pedido real del celular | `id=65 nro=4`, 15:29:44, total 9700.00, `status=NEW`, `table_id=null`, items Muzzarella+Napolitana; stock 48 → 47 |

El control de acceso real **ya está del lado servidor** y funciona.

## Why

Un guard de rol escrito en el navegador no es un control de seguridad, y este además mata la página. El control real es la autenticación en el servidor (403 sin token, ya probado). La corrección consiste en que el cliente deje de fingir que autoriza: sólo exige sesión para las pantallas que la necesitan, y el login manda a cada rol a donde corresponde. Así el piloto puede continuar y la decisión de "sólo cocina ve el KDS" queda donde corresponde: en el servidor, con authorities de verdad.

## Scope

**In**

- `api/src/main/resources/static/kds/index.html` — guard de sesión.
- `api/src/main/resources/static/dashboard/index.html` — el mismo guard.
- `api/src/main/resources/static/login/login.js` — ruteo por rol.
- `api/src/test/js/page-guard.test.mjs` — nuevo test de contrato JS.
- `.github/workflows/ci.yml` — paso que lo ejecuta (sin tocar los 5 pasos existentes).

**Out** (decisiones explícitas, cada una su slice)

1. **No** se agrega la claim `roles` al JWT ni se implementa `extractRole`. El modelo OWNER/KITCHEN no existe en el backend y hacerlo real toca la autenticación de todos los usuarios.
2. **No** se agrega autorización `KITCHEN` en `/api/business/orders/**`. Hoy cualquier usuario autenticado lee los pedidos de *su propio* negocio (`businessId` sale del token). Si el producto quiere "sólo cocina ve el KDS", es un `hasAuthority` server-side con los roles sembrados, en su propio slice.
3. **No** se toca el 500-como-404: `GlobalExceptionHandler` mapea `NoResourceFoundException` a 500, y por eso `/login.html` y `/menu/null` devuelven 500 en vez de 404.
4. **No** se investiga quién pide `/menu/null` (aparece 4 veces en el log; no lo arma ningún archivo estático; queda como anomalía abierta).
5. **No** se cambia el contenido del panel admin ni su falta de guard.

## Constraints

- Los tests JS corren **fuera de Maven**, por convención del repo: `node api/src/test/js/<archivo>.test.mjs`, con líneas `PASS`/`FAIL` y un resumen final; exit code distinto de cero al fallar.
- No hay `AGENTS.md` en el repo. Runner completo (lo corre el padre, no el escritor): `mvn -B --no-transfer-progress verify` + los tres tests de node.
- Sin commits: la autorización de esta sesión no incluye commitear. Los cambios quedan en el working tree.
- El jar servido es estático: el arreglo no se ve hasta reconstruir y reiniciar la app.

## Tasks

- [x] **T1 — Test RED-first del guard de sesión.** `api/src/test/js/page-guard.test.mjs`: ejecuta el bloque `<script>` real del guard de cada página dentro de un sandbox `vm` (mismo patrón que `menu-app.test.mjs`) con `localStorage` y `window.location` falsos, y pincha el contrato: sin token → al login real **con el slug** y sin excepciones; con token válido (payload sin `roles`) → sin redirección y sin excepciones. Se cablea el paso en CI. Debe fallar contra el código actual (evidencia RED literal en este documento).
- [x] **T2 — Arreglar los dos guards.** `kds/index.html` y `dashboard/index.html`: redirigir a `/login/login.html` (con `?restaurant=<slug>` cuando hay slug), **cortar** la ejecución, y eliminar el gate de rol del cliente junto con el parseo de la claim inexistente. Cada página sólo conoce su propio camino (se borra el `if (pathname.includes("/kds/"))` del dashboard y viceversa).
- [x] **T3 — Ruteo por rol en el login.** `login.js`: rama `ADMIN` → `/admin/admin.html?restaurant=<slug>` (la intención abandonada de la línea 168), conservando `OWNER` → dashboard y `KITCHEN` → kds, sin que el `else` convierta un rol válido en un error de credenciales.
- [x] **T4 — Endurecer el guard contra una sesión inutilizable.** Origen: hallazgos 5 y 6 de la primera verificación independiente, más el refinamiento del predicado que encontró la segunda. Un token que no es una sesión (`null`, `undefined`, vacío, sólo espacios, y los mismos literales con espacios alrededor) y una excepción al leer `localStorage` o al parsear la query hoy producen una **pantalla muerta** en vez de una redirección. Regla: el guard nunca deja la página sin sesión y nunca lanza; ante cualquier fallo, redirige al login (con slug si lo pudo leer, sin slug si no). Un token cuenta como sesión sólo si, **recortado**, no es vacío ni el literal `null`/`undefined`. **Fuera**: validar la forma del JWT, y el manejo de 401/403 en `kds.js` (eso es otro slice, más profundo: hoy `kds.js` se traga el 403 y dibuja columnas vacías).

## Acceptance criteria

| ID | Criterio |
| --- | --- |
| AC1 | KDS sin token y con `?restaurant=demo-pizzeria` → redirige a `/login/login.html?restaurant=demo-pizzeria`, sin excepción |
| AC2 | KDS sin token y sin slug → redirige a `/login/login.html`, sin excepción |
| AC3 | KDS con token válido cuyo payload **no** trae `roles` → **no** redirige y **no** lanza (hoy lanza `TypeError`) |
| AC4 | AC1–AC3 valen igual para `dashboard/index.html` |
| AC5 | `login.js`: `ADMIN` → `/admin/admin.html?restaurant=<slug>`; `OWNER` → dashboard; `KITCHEN` → kds; los tres sin excepción |
| AC6 | No queda ninguna referencia a `payload.roles` bajo `api/src/main/resources/static/` |
| AC7 | `node api/src/test/js/page-guard.test.mjs` verde, más `menu-app.test.mjs` y `frontend-origin.test.mjs` verdes |
| AC8 | `mvn -B --no-transfer-progress verify` verde |
| AC9 | Ensayo manual: con la app reconstruida y reiniciada, loguearse como `demo`/`demo1234` y abrir `http://192.168.1.5:9090/kds/index.html?restaurant=demo-pizzeria` muestra los pedidos existentes |
| AC10 | `localStorage.getItem` lanza (almacenamiento bloqueado) → redirige al login con el slug y **no** propaga excepción |
| AC11 | token `"null"`, `"undefined"`, `""`, `"   "`, `" null "`, `"null\n"`, `"\tnull\t"` o `" undefined "` → se trata como ausencia de sesión y redirige al login |
| AC12 | `URLSearchParams` ausente → redirige al login **sin** slug y no propaga excepción |
| AC13 | un token con forma de JWT sigue sin redirigir (sin regresión de AC3/AC4) |

## Progress

| Task | Estado | Evidencia |
| --- | --- | --- |
| T1 | hecho | RED literal (abajo): `login.html` vs `login/login.html`, y `Cannot read properties of undefined (reading 'includes')` en las dos páginas. 9 casos, exit 1 |
| T2 | hecho | 8/9 verde tras arreglar los dos guards; solo quedaba el ADMIN del login |
| T3 | hecho | 9/9 verde, exit 0 |
| T4 | hecho | RED literal del predicado crudo (`the unusable token " null "` → `''`, 15/17); verde 17/17. Segunda verificación independiente: PASS WITH FINDINGS, sin bloqueantes |

Todo el slice lo escribió un `gentle-ai-worker` en una sola corrida, en orden RED → T2 → T3. Su transcript tiene 6 comandos de bash: tres corridas del test de node, un `git status`, un `git diff` y un `ls`. **Sin Maven, sin commits, sin tocar la app.**

## Evidence

### RED (literal, `node api/src/test/js/page-guard.test.mjs`, exit 1, 7 FAIL / 2 PASS)

```text
FAIL kds/index.html: no token and a restaurant slug lands on /login/login.html with the slug and stops
AssertionError [ERR_ASSERTION]: an anonymous visitor with a restaurant slug must land on the login page keeping the slug
+ actual - expected

+ '/login.html'
- '/login/login.html?restaurant=demo-pizzeria'

FAIL kds/index.html: a token without a roles claim is accepted without redirect or error
AssertionError [ERR_ASSERTION]: ifError got unwanted exception: Cannot read properties of undefined (reading 'includes')

FAIL dashboard/index.html: no token and no slug lands on the bare /login/login.html and stops
AssertionError [ERR_ASSERTION]: ifError got unwanted exception: Cannot read properties of null (reading 'split')

FAIL login.js: ADMIN lands on /admin/admin.html?restaurant=demo-pizzeria
AssertionError [ERR_ASSERTION]: ADMIN must land on the admin page with the restaurant slug
+ actual - expected

+ ''
- '/admin/admin.html?restaurant=demo-pizzeria'

2 test(s) passed
EXIT CODE: 1
```

Se ve el defecto completo en una sola corrida: la ruta vieja, el `TypeError` por la claim inexistente, el segundo `TypeError` por no cortar tras redirigir, y el login rechazando `ADMIN`.

### GREEN

- Tras T2: `8 test(s) passed`, exit 1 (solo el ADMIN del login, esperado antes de T3).
- Tras T3: `9 test(s) passed`, exit 0.
- Tras T4: **`17 test(s) passed`, exit 0** (los 9 previos más 8 nuevos).
- `node api/src/test/js/menu-app.test.mjs` → 4/4; `frontend-origin.test.mjs` → 13/13.
- `mvn -B --no-transfer-progress verify` → **33 tests, 0 failures, 0 errors, BUILD SUCCESS** (lo corrió el padre, no el escritor).

### Verificación independiente

La corrió un `gentle-ai-verify` read-only, sin acceso de escritura al repo. Veredicto: **PASS WITH FINDINGS**, con el repo byte-idéntico al terminar (`md5sum -c` OK, mtimes sin cambios; todas las mutaciones sobre copias en `/tmp`).

**Sensibilidad del test — probada por mutación, no afirmada.** Cada defecto reintroducido en una copia hace fallar el test:

| Mutación en la copia | Observado |
| --- | --- |
| destino de `/kds` vuelto a `/login.html` | 2 FAIL, exit 1 |
| quitado **solo** el corte (`return`) | 2 FAIL: `Cannot read properties of null (reading 'split')`, exit 1 |
| quitado el `?restaurant=<slug>` | 1 FAIL (el caso sin slug sigue pasando bien) |
| reintroducido un gate `payload.roles` que lanza | 1 FAIL: `Cannot read properties of undefined`, exit 1 |
| rama ADMIN de `login.js` comentada otra vez | 1 FAIL, exit 1 |
| correr el test nuevo contra `HEAD` (código viejo) | 7 FAIL / 2 PASS, exit 1 |

**Anti-vacuidad.** Si el bloque del guard no aparece, el test **falla** en vez de pasar en silencio (`cannot find the inline guard <script> block in kds/index.html`); con un `<script></script>` vacío o con el guard movido a un `.js` externo, también falla. Único punto débil (NIT): la tercera aserción de cada página ("con token no redirige") pasaría en el vacío contra un guard que no hiciera nada — es inofensiva porque las otras dos de la misma página fallan ruidosamente, así que la suite no puede volverse vacua.

**AC1–AC6 re-derivados de los artefactos** con un harness propio, no desde el reporte del escritor: los cuatro casos (dos páginas × con/sin token) coinciden; `grep -n pathname` sobre ambas páginas no devuelve nada, así que el olfateo cruzado de rutas desapareció; AC6 (`grep -rn "payload\.roles|roles\.includes" static/`) sin resultados. **AC8** no lo corrió el verificador (por diseño); lo corroboró parseando los `surefire-reports`: 13 suites, 33 tests, 0 failures.

**Alcance.** `git status --porcelain` y `git diff --stat`: exactamente los 4 archivos versionados + el test nuevo + el documento. Nada fuera de las superficies. `.codegraph/` y `.pi/` son untracked preexistentes (fechados 15/09, antes de este cambio).

### Segunda verificación independiente (T4)

`gentle-ai-verify` read-only, con el repo byte-idéntico al terminar (589 de 590 archivos iguales; el único distinto es `logs/app.log`, gitignoreado y escrito por el propio planificador periódico de la app). Veredicto: **PASS WITH FINDINGS**, sin bloqueantes.

**Sensibilidad probada por mutación** sobre copias en `/tmp`: cada mutación la detecta el caso correcto y no hay daño colateral.

| Mutación | Observado |
| --- | --- |
| quitar el `try/catch` | AC10 + AC12 fallan en las dos páginas (4 FAIL) |
| quitar sólo el filtro de literales muertos | AC11 falla en las dos páginas |
| leer el token **antes** del slug | AC10 falla: se pierde el slug |
| mover el redirect **dentro** del `try` | AC10 + AC12 fallan: el catch se come el redirect |
| volver el destino de `/kds` a `/login.html` | 5 FAIL, incluidos casos previos |

**RED de T4 (literal)**, antes del arreglo del predicado:

```text
FAIL kds/index.html: dead token strings land on /login/login.html with the slug (AC11)
AssertionError [ERR_ASSERTION]: the unusable token " null " must be treated as no session and redirect with the slug
+ actual - expected

+ ''
- '/login/login.html?restaurant=demo-pizzeria'

15 test(s) passed
EXIT CODE: 1
```

**El hallazgo que trajo esa verificación, y que se arregló acá**: el filtro comparaba el valor **crudo**, así que `" null "`, `"null\n"`, `"\tnull\t"` y `" undefined "` seguían pasando como sesión y dejaban la pantalla muerta, la misma clase que T4 venía a cerrar. El verificador lo juzgó **no explotable en este repo** (el único escritor de `token` es `login.js`, que guarda el valor tal cual viene del 200) pero inconsistente con el chequeo de vacío que está dos líneas más arriba. Se arregló RED-first, como todo el slice.

**Re-derivación independiente**: 44 chequeos propios del verificador sobre los archivos reales, `ALL AC CHECKS MATCH`; AC10 a AC13 y AC6 confirmados en ambas páginas, más el caso compuesto (sin `URLSearchParams` **y** con el almacenamiento bloqueado). Los dos guards quedaron con el cuerpo **byte-idéntico** entre páginas.

**Build servido**: el verificador comprobó que los bytes servidos coinciden con el working tree y que el jar contiene el guard endurecido. Tras el refinamiento: jar reconstruido a las 18:17, 13 suites / 33 tests / 0 fallos, app reiniciada, `/login/login.html`, `/kds/index.html`, `/dashboard/index.html` y `/menu/index.html` → 200, y el refinamiento presente en el guard servido.

## Open gaps and decisions

1. Después del arreglo, el login de `ADMIN` manda al panel admin (no al KDS): no existe soporte de `?next=` en `login.js`. Para ver la cocina hay que abrir la URL del KDS con la sesión ya iniciada. Si se quiere volver al destino pedido, es un slice aparte.
2. El KDS deja de distinguir roles en el cliente: cualquier usuario autenticado puede abrir la URL del KDS y ver los pedidos de su propio negocio. Es el mismo nivel de acceso que ya permitía la API. Endurecerlo es el punto 2 de "Out".
3. `dashboard/index.html` queda con el mismo nivel de acceso; su guard roto se arregla acá porque es el mismo defecto copiado.
4. La anomalía `/menu/null` → 500 queda sin productor identificado.
5. **FIXED en T4 — el guard no tenía `try/catch`** (hallazgo de la primera verificación). Si `localStorage.getItem` lanza (almacenamiento deshabilitado, modo privado) o `URLSearchParams` no existe, la IIFE muere y la página se queda renderizando sin sesión en vez de redirigir. No es una regresión (el guard viejo también lanzaba) y hoy no se auto-cura: `kds.js` no maneja 401/403, así que muestra columnas vacías. Bajo impacto, pero es el único agujero real de "redirigir o nada".
6. **FIXED en T4 — `token === "null"` o `"undefined"` pasaba como sesión** (hallazgo de la primera verificación, y refinado tras la segunda: también contaban los mismos literales con espacios alrededor). `if (token) return;` aceptaba los strings literales, y el resultado es una pantalla muerta (la API responde 403, verificado con `Bearer null`, `Bearer undefined`, `Bearer garbage`). Es degradación de UX, no un agujero de acceso: la autorización del servidor se mantiene. No es alcanzable desde el camino feliz de `login.js`, que guarda `data.token` de un 200.
7. **Riesgo operativo descubierto acá**: reconstruir el jar **debajo de una JVM corriendo** la deja sin poder servir ningún estático (500 en todas las páginas) — la JVM lee el jar anidado con un handle que queda inválido. Ocurrió con el `mvn verify` del padre a las 16:07 sobre la app arrancada a las 15:19; se arregló reiniciando. Para el ensayo: rebuild y restart siempre en ese orden, con la app parada.
8. **Batch de 20 requests a las 15:50:17 con 500 en todas las páginas** (más 1 a las 15:41:08): sin actor identificado. No fue el escritor (su transcript tiene 6 comandos, ninguno HTTP ni Maven, verificado) ni el verificador (que corrió a las 16:09). Se solapa con la franja en que el usuario intentaba entrar al KDS. Queda como anomalía abierta junto al punto 4.

## Ensayo manual (AC9) — PASÓ

La app quedó **corriendo** en `192.168.1.5:9090` con el fix servido, con la JVM arrancada limpia. Verificado del lado servidor después del reinicio: `/login/login.html`, `/kds/index.html`, `/dashboard/index.html`, `/menu/index.html`, `/admin/admin.html` y `/kds/kds.js` → **200**; `/api/business/orders/active` sin token → **403**; el guard servido en `/kds/index.html` contiene `login/login.html` y **cero** ocurrencias de `payload.roles`.

**Resultado (16/09/2026, confirmado por el usuario):** el login con `demo`/`demo1234` funciona y el KDS entra bien. Es la evidencia que ningún test podía dar: el render real en un navegador logueado. Junto con el escaneo del celular del mismo día (menú + pedido #3 y #4 creados de verdad), quedan cerradas las dos mitades del piloto que antes sólo estaban cubiertas por tests en capas.

Queda **una sola cosa sin verificar en esta sesión**: que la tarjeta del pedido se dibuje y suene en el KDS con un pedido nuevo llegando en vivo por WebSocket desde otro dispositivo (el ensayo anterior ya había probado la emisión al topic `/topic/orders/demo-pizzeria` con un cliente STOMP crudo, así que lo que falta es el dibujo de la tarjeta en el navegador de cocina). Es el próximo ensayo, no un pendiente del arreglo.

### Estado de los criterios

AC1–AC13: **cumplidos**. AC1–AC8 y AC10–AC13 por test (17 casos de node, más las mutaciones de las dos verificaciones independientes); AC9 por el ensayo manual confirmado arriba.
