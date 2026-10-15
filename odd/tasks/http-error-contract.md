# Contrato de errores HTTP

**Estado:** cerrado (2026-10-14)
**Rama:** `feature/ui-liquid-glass`

## El problema

`GlobalExceptionHandler` tenía un catch-all de `Exception` que convertía **todo** en 500, incluidas:

1. **Las excepciones del propio Spring MVC**, que ya traen su código (404, 405, 400, 415).
2. **~40 `RuntimeException` lanzadas a mano** en servicios, de las cuales **21 son "not found"**: la API contestaba `500 Ocurrió un error inesperado` donde correspondía 404, 400 o 409.

Medido con `curl` contra el backend corriendo, antes del arreglo: ruta sin handler → 500; método no permitido → 500; JSON roto → 500; `Content-Type` no soportado → 500. Después: 404, 405, 400 y 415.

## Lo que ya está cerrado

El catch-all sigue existiendo para lo que **sí** es una falla del servidor (500 con el mismo mensaje genérico), pero las excepciones de Spring MVC pasan por un handler propio que devuelve su estado real conservando el cuerpo `{status, error, message, path}`. Detalle fino: `org.springframework.web.ErrorResponse` es una **interfaz, no un `Throwable`**, así que no puede ir en `@ExceptionHandler`; hay que listar las clases concretas y leer estado y detalle desde la interfaz adentro del método. En Spring 7 tres miembros de la familia **no** implementan `ErrorResponse` y necesitan una rama explícita de 400. El `printStackTrace` del catch-all pasó a ser un log SLF4J de verdad. Commit `9a95b4a`, 121/121 y las cuatro sondas verificadas contra el servlet stack.

## Camino público (en curso)

Es lo que puede tocar un cliente y por eso va primero. El patrón correcto ya existe en el proyecto (`BusinessException` + `ErrorType`, mapeado por `ErrorMapper`: `VALIDATION` → 400, `NOT_FOUND` → 404, `CONFLICT` → 409); lo que falta es usarlo.

| Sitio | Hoy | Debe ser |
| --- | --- | --- |
| `ProductController` `GET /restaurants/slug/{slug}` (slug inexistente) | 500 | 404 |
| `ProductController` `GET /restaurants/slug/{slug}/products` | 500 | 404 |
| `MenuServiceImpl` `GET /api/menu/{slug}` | 500 | 404 |
| `OrderServiceImpl` validación del pedido (nombre, tipo, pago, dirección, items, cantidad) | 500 | 400 |
| `OrderServiceImpl` producto o combo inexistente | 500 | 404 |
| `OrderServiceImpl` stock insuficiente | 500 | 409 |
| `OrderServiceImpl` pedido inexistente / ajeno | 500 | 404 |

`No se pudo devolver stock del producto` **no** se toca: eso sí es una falla del servidor y el 500 es honesto.

Los textos de los mensajes se conservan tal cual (están escritos para el cliente). No hay cambio de frontend: la carta sólo mira `response.ok` y cae al respaldo de WhatsApp.

## Clúster del panel (pendiente, anotado)

Mismo defecto, rutas del dueño: `UserServiceImpl` (6), `TableServiceImpl` (3), `DashboardServiceImpl` (2), `AnalyticsServiceImpl` (2), `BusinessServiceImpl` (1), `CategoryServiceImpl` (4), `SecurityService` (1), `UserServiceImpl` "Username already exists" (debería ser 409). No es urgente porque no lo alcanza un cliente anónimo, pero es la misma mentira. Merece su propia unidad de trabajo con tests de status por endpoint.

## Tareas

- [x] 1. Handler de las excepciones de Spring MVC + logging real (commit `9a95b4a`)
- [x] 2. Camino público: slug del local (x2, x3) y validación del pedido (x4-x7) — commit `01292ad`
- [x] 3. Clúster del panel: login honesto y "not found" del dueño — commit `fe3f8eb`
- [x] 4. `// TEST ERROR LOG` quitado de `ProductController.crear` (en el commit `01292ad`)

## Riesgos

- Las rutas de error del dueño que hoy devuelven 500 pueden tener tests que fijen ese 500: hay que actualizarlos al estado correcto, no al revés.
- Fijar un 404 en `Pedido no encontrado o no autorizado` es deliberado: responder 403 revelaría que el pedido existe.

## Verificación del camino público (commit `01292ad`)

Contra el **backend corriendo**, no sólo MockMvc:

| Camino | Antes | Ahora |
| --- | --- | --- |
| `GET /api/restaurants/slug/no-existe` | 500 | **404** `Local no encontrado` |
| `GET /api/restaurants/slug/no-existe/products` | 500 | **404** `Local no encontrado` |
| `GET /api/menu/no-existe` | 500 | **404** `Local no encontrado` |
| `POST` con `{}` | 500 | **400** `El nombre del cliente es obligatorio` |
| `POST` DELIVERY sin dirección | 500 | **400** `Debe indicar dirección para delivery` |

Backend **131/131**. Carta y pedido real en el navegador **8/8**, así que el frontend no dependía de esos 500. Dos mensajes que ahora viajan al cliente estaban en inglés y pasaron a español.

Dos tests viejos fijaban el 500 accidental: `AnonymousMenuOrderSecurityTests` probaba que un anónimo puede postear un pedido afirmando **"no es 404"** (que en realidad era el 500). Ahora siembra negocio y producto reales y espera 200: prueba la intención, no el accidente.

## El clúster del panel (commit `fe3f8eb`)

El login era la mitad visible: `Login.tsx` muestra el `message` del backend tal cual, así que una contraseña mal tipeada le decía al usuario **"Ocurrió un error inesperado"**, y cada intento fallido dejaba un stack trace en nivel ERROR. Medido antes y después contra el backend corriendo:

| Caso | Antes | Ahora |
| --- | --- | --- |
| Contraseña equivocada | 500 | **401** `Usuario o contraseña incorrectos` |
| Usuario inexistente | 500 | **401**, **el mismo mensaje** |
| Credenciales válidas, negocio ajeno | 500 | **403** `El usuario no pertenece a este negocio` |
| Sin `restaurantSlug` | 500 | **400** `Debe indicar el negocio` |
| `GET /api/categorias/{id}` inexistente | 500 | **404** `Categoría no encontrada` |
| Usuario duplicado | 500 | **409** |

`ErrorType` sumó `UNAUTHORIZED` y `FORBIDDEN`: con 400/404/409 no se puede decir "no estás autenticado" contra "estás autenticado pero no podés", y esa es justo la distinción del login.

Un test fija la propiedad que importa: **contraseña equivocada y usuario inexistente responden lo mismo**, para que el login no se convierta en un oráculo de qué usuarios existen.

Verificado además **en el navegador** (`~/.local/opt/e2e/login-error.mjs`): el formulario dice `Usuario o contraseña incorrectos` en los dos casos y el pedido responde 401.

### El endpoint que no era un status: `GET /api/business/slug/{slug}`

Declaraba `iBusinessService` y **nunca lo inyectaba**, así que tiraba NPE en **cualquier** slug, válido o no: no era un estado equivocado, era un endpoint que no funcionaba. Corregida la inyección, responde **200** con el negocio y **404** con un slug inexistente. El test de nivel servicio que existía sólo porque la ruta no se podía alcanzar fue reemplazado por dos de nivel HTTP.

### Notas que conviene conservar

- Sitios **sólo defensivos** (convertidos por honestidad, pero no alcanzables por HTTP): `UserServiceImpl.getLoginInfo` (ningún controller lo llama), los lookups de negocio derivados del JWT en `TableServiceImpl` y `CategoryServiceImpl`.
- Llamados opinables, resueltos con criterio: `Role not found` → **400** (el rol viene en el body, es entrada inválida; 404 también sería defendible); `getLoginInfo` sin pertenencia → **403** (coherente con el login).
- `TableController.update/delete/findAll/generateBulk` son **stubs muertos** (devuelven null, sin mapeo): el 404 de `findOwnedTable` sólo se alcanza por los endpoints de QR.
- El `403` de `GET /api/categorias` sin token es correcto (la sesión vencida del dueño se ve así).

### Guard de privilegio en `POST /api/predictions/run`

El endpoint dispara `IGeneratePredictionsForAllBusinessesUseCase.execute()`: un job **global** (todos los negocios), no el recálculo del negocio propio. Estaba bajo `anyRequest().authenticated()`, así que **cualquier** usuario logueado de **cualquier** negocio —un KITCHEN, por ejemplo— podía forzar el recálculo de toda la plataforma.

Ahora exige `hasAnyRole("OWNER", "ADMIN")` en la cadena de filtros. Tiene que ser ahí y no con `@PreAuthorize`: `@EnableMethodSecurity` no está habilitada, así que una anotación de método no haría **nada**. Las autoridades ya existen: `CustomUserDetailsService` mapea cada rol a `ROLE_<NOMBRE>`.

Alcance honesto, escrito en el código: es una barrera de **privilegio, no de tenant**. El job es global, así que un OWNER del negocio A que lo invoca sigue recalculando las predicciones de **todos** los negocios. La regla limita quién puede accionar; no aísla negocios.

El RED se midió, no se asumió: neutralizando la regla, `kitchenCallerOfRunIsForbidden` falla con `Status expected:<403> but was:<200>` — la vulnerabilidad misma. El archivo de la cadena se restauró **byte por byte** después de esa comprobación (mismo hash), y el GREEN es la suite completa.

#### Anotado: 401 vs 403 para el anónimo

La cadena **no configura entry point**, así que su default responde **403 también al anónimo** en toda ruta protegida: confunde "no tenés sesión" con "no tenés privilegio". Ese 403 está documentado como contrato vigente en tres suites (`ProductCatalogByIdTests`: *"403, because the route is not in any permitAll matcher"*, `DashboardAnonymousAccessTests`, `BusinessProfileAndStorefrontTests`), y el frontend no distingue un código del otro (no hay una sola comparación contra 401/403 en `frontend/src`).

Por eso el guard del `run` **no** cambió ese contrato: un anónimo recibe 403 y un test lo fija como comportamiento **sin cambios**, no como endorsement. Un primer intento lo resolvió por ruta con `DelegatingAuthenticationEntryPoint` + fallback explícito: ~30 líneas de mecanismo para una sola ruta, que además dejaba a esa ruta discrepando con el resto de la API y con una perilla que rompe cuatro suites si alguien la toca después. Se descartó — la pregunta es de toda la cadena, no de un endpoint.

Decisión pendiente (afecta a **toda** ruta protegida y a esas tres suites): configurar un entry point global para que el anónimo reciba **401** en todas partes, o quedarse con el 403. Se decide con apetito de cambiar el contrato público de la API, no de refilón en un guard.
