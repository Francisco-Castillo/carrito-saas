# Contrato de errores HTTP

**Estado:** mecanismo cerrado; camino público en curso; clúster del panel pendiente
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
- [ ] 2. Camino público: slug del local (x2, x3) y validación del pedido (x4-x7)
- [ ] 3. Clúster del panel: convertir los "not found" y las validaciones restantes
- [ ] 4. Revisar el `// TEST ERROR LOG` que quedó en `ProductController.crear` (log de prueba en producción)

## Riesgos

- Las rutas de error del dueño que hoy devuelven 500 pueden tener tests que fijen ese 500: hay que actualizarlos al estado correcto, no al revés.
- Fijar un 404 en `Pedido no encontrado o no autorizado` es deliberado: responder 403 revelaría que el pedido existe.
