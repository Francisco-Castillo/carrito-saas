# Feature: whatsapp-inbound

## Objective

Que un cliente pueda escribir su pedido en texto libre por WhatsApp, y que el sistema le proponga al local un pedido estructurado que **un humano confirma** antes de que llegue a la cocina.

## Problem

Hoy el único camino de entrada de un pedido es `POST /api/orders/menu/{slug}` con cuerpo estructurado. El cliente que escribe prosa por WhatsApp no existe para el sistema: el comercio recibe texto y lo re-escribe a mano. Ocho hechos verificados, con línea.

### Hecho 1 — no hay ningún ingreso de mensajes

Se enumeraron todos los `@*Mapping` de `api/src/main/java/**`: son menú, producto, pedido, dashboard, analytics, mesa, usuario, QR y auth. **No hay webhook, no hay callback, no hay ruta de verificación.** Tampoco hay colas ni mensajería: cero dependencias JMS/Rabbit/Kafka/AMQP, y cero cliente HTTP (`RestTemplate`/`WebClient`/`HttpClient`/`OkHttp` → 0 hits).

### Hecho 2 — no existe un estado "propuesta", y un pedido creado es visible en la cocina en ~0 ms

`repository/src/main/java/com/carrito/saas/repository/enums/OrderStatus.java:5` tiene exactamente cinco constantes: `NEW, PREPARING, READY, DELIVERED, CANCELLED`. `OrderServiceImpl.java:101` hardcodea `NEW`, y `Order.java:113-116` (`@PrePersist`) pone `NEW` como default. Del lado del KDS no hay filtro de aceptación:

- Bootstrap: `findActiveOrders` sólo **excluye** `DELIVERED`/`CANCELLED` (`OrderRepository.java:30`).
- Tiempo real: `OrderController.java:43` emite a `/topic/orders/{slug}` y `kds.js:692,710` dibuja la tarjeta y suena en `status === "NEW"`.

**Consecuencia dura: persistir una propuesta como `Order` la manda a la cocina al instante.** Y peor: `createOrder` descuenta stock inline (`OrderServiceImpl.java:164,220`), así que un pedido mal interpretado por el normalizador descontaría stock real.

### Hecho 3 — no hay mapeo teléfono → negocio, y el teléfono ni siquiera es único

No existe columna `whatsappNumber`. `BusinessDTO.whatsappNumber` es un alias de `Business.phone` (`BusinessMapperImpl.java:23,35`). Y `Business.java:36` es:

```java
private String phone;   // sin @Column, sin unique, sin nullable = false
```

**Nullable, sin índice, sin unicidad.** Un teléfono que resuelve a dos negocios es un pedido ruteado al local equivocado, en silencio.

### Hecho 4 — no hay idempotencia, y el proveedor reintenta

`grep -i idempoten` → **cero hits** en todo el repo. No hay tabla de deduplicación, log de mensajes procesados ni clave de idempotencia. Y lo ya verificado en el canal público: `#sendOrder` no tiene guarda de reentrada, un doble toque crea dos pedidos y descuenta stock dos veces. Un webhook reintentado hace exactamente eso.

### Hecho 5 — no hay ninguna forma de ir de texto a `productId`

El contrato del intake es **id-based**: `OrderItemDTO.productId` / `comboId` son `Long` (`OrderItemDTO.java:10,12`). No hay ruta por nombre. Y el matching que existe son dos ayudantes angostos y de admin:

- LIKE de substring sobre nombre de producto o categoría, para el listado de admin (`ProductSpecification.java:50-58`).
- Igualdad exacta normalizada con `LOWER(TRIM(...))`, para detectar duplicados dentro de una categoría (`ProductRepository.java:83-90`).

`grep -E 'alias|synonym|sinonim|levenshtein|similarity|fuzzy'` → **cero hits en código fuente**. No hay entidad ni columna de alias.

### Hecho 6 — no hay infraestructura asíncrona, así que el normalizador corre inline

El módulo `jobs/` existe pero está muerto dos veces: **ningún** módulo lo declara como dependencia (`api/pom.xml:33-48` y `service/pom.xml` no lo mencionan) y **`@EnableScheduling` no aparece en todo el repo**. Los tres `@Scheduled` nunca corren. Sin colas y sin executor, el trabajo del webhook ocurre dentro del request.

**Consecuencia de diseño:** el normalizador tiene que ser rápido. Esto valida la decisión de matching determinista: es O(n) sobre el catálogo en memoria, sin latencia de red ni costo por mensaje.

### Hecho 7 — una ruta nueva cae en 403, y la trampa espejo es quedar anónima sin querer

`SecurityConfig.java:79` cierra con `.anyRequest().authenticated()`. Una ruta nueva que no matchee ninguna regla previa cae ahí, y con la cadena `STATELESS` sin `formLogin` ni `httpBasic` ni `exceptionHandling`, un POST anónimo es rechazado con **403** antes de llegar al controller. Es la misma trampa de match exacto ya documentada para `/api/orders`.

**La trampa espejo:** si la ruta se pone bajo un prefijo ya permitido, como `/api/restaurants/**` (`SecurityConfig.java:71`) o `/api/menu/**` (`:64`), queda **silenciosamente anónima** sin tocar nada. Las dos formas de fallar son opuestas y ninguna es un error de compilación.

Y no hay **ningún** control de ingreso por secreto: `grep -E 'apiKey|api-key|hmac|signature|secret'` sólo devuelve el firmado saliente de JWT (`JwtUtil.java:20`). No hay `X-Hub-Signature`, ni secreto compartido, ni filtro de API key, ni chequeo de replay. Los dos únicos filtros son `TenantContextFilter` (correlación, sin lógica de auth) y `JwtFilter` (Bearer; sale temprano sólo en `/actuator` y `/api/auth`).

### Hecho 8 — el WebSocket está abierto y sin interceptor

`/ws/**` es `permitAll` (`SecurityConfig.java:74`) y el endpoint STOMP es `registry.addEndpoint("/ws/orders").setAllowedOriginPatterns("*").withSockJS()` (`WebSocketConfig.java:24`) con broker simple en `/topic`. No hay `ChannelInterceptor` ni `configureClientInboundChannel` ni `Principal` en ningún lado. **Cualquier anónimo se suscribe a `/topic/orders/{slug}`.**

## Why

El valor del sistema hoy es el camino determinista: menú → pedido estructurado → KDS en vivo. Ese camino **no necesita un LLM** y hay que dejarlo quieto. Lo que no existe es el camino del cliente que ya está escribiendo por WhatsApp: hoy ese pedido no entra, y el comercio lo re-tipea. Ese es el diferencial real.

Pero es un diferencial que puede hacer daño en las dos puntas: si el normalizador se equivoca, **descuenta stock real** (Hecho 2) y si el ruteo se equivoca, **le manda el pedido al local equivocado** (Hecho 3). Por eso el diseño no es "un bot que toma pedidos", es **un intérprete que propone y un humano que decide**, con la garantía de que la propuesta no puede tocar stock ni la cocina por construcción, no por un `if`.

## Scope

**In**

- `service/src/main/java/com/carrito/saas/service/whatsapp/` — puerto `InboundMessage`, adapter con el contrato de Meta, resolver, normalizador.
- `api/src/main/java/com/carrito/saas/api/WhatsappWebhookController.java` — handshake `GET` + ingreso `POST`.
- `api/src/main/java/com/carrito/saas/config/SecurityConfig.java` — **una** regla explícita para la ruta del webhook.
- `repository/.../entity/OrderProposal.java`, `OrderProposalItem.java`, `repository/.../enums/ProposalStatus.java`, `repository/.../jpa/OrderProposalRepository.java`.
- `api/src/main/java/com/carrito/saas/api/OrderProposalController.java` — listar y confirmar (autenticado).
- `api/src/main/resources/static/kds/` — sección de propuestas con confirmar/descartar.
- Tests: contrato del canal, handshake, seguridad de la ruta, resolver, idempotencia, normalizador, confirmación, y un simulador ejecutable.
- `odd/tasks/tools/whatsapp-simulator.mjs` (o equivalente) — el simulador fuera del árbol de producción.

**Out** (decisiones explícitas, cada una su motivo)

1. **Sin lado saliente.** No se le responde nada al cliente por WhatsApp. El cliente escribe, el sistema propone, el local confirma en el KDS. Motivo: el lado saliente de Cloud API trae plantillas aprobadas por Meta, ventana de 24 h y costos por conversación, y **duplica el tamaño del slice**. Se corta entero y queda como feature siguiente.
2. **Sin LLM.** El normalizador es matching determinista contra el catálogo del negocio (decisión del usuario). Motivo: es testeable con asserts de string, reproducible en CI, sin costo por mensaje y sin latencia. Un LLM no determinista en el camino que crea pedidos con stock real necesita su propio slice, con suites de fixtures en vez de asserts.
3. **Sin conexión real a Meta.** No hay app secret, ni número verificado, ni WABA. El contrato se implementa y se congela en un fixture; el simulador lo ejerce. Conectar es cambiar credenciales y URL.
4. **Sin realtime para las propuestas.** No se emite por `/topic/orders/{slug}` (semántica equivocada y topic anónimo) ni se crea un topic nuevo en el primer slice. La lista se lee por REST autenticado con refresco manual. Motivo: Hecho 8 — un topic nuevo tiene hoy exactamente la misma exposición anónima, y resolver eso es un slice de seguridad aparte.
5. **Sin UI de administración del mapeo teléfono→negocio.** El mapeo se administra por datos/ALTA manual en este slice.
6. **Sin edición de líneas antes de confirmar.** El humano confirma la propuesta tal como quedó o la descarta. Editar líneas es una feature propia y agranda la UI del KDS.
7. **Sin expiración de propuestas.** Una propuesta pendiente no caduca en este slice.
8. **Sin tocar `OrderStatus`.** La propuesta tiene su propio enum `ProposalStatus`; `Order` y su máquina de estados quedan intactos. Es la decisión de diseño central de este documento.
9. **Sin cubrir mensajes que no son texto** (audio, imagen, ubicación, botones). Sólo `type: "text"`.

## Constraints

- **Strict TDD activado.** RED literal antes de cada fix, pegado en este documento.
- Runner completo, lo corre el padre: `mvn -B --no-transfer-progress verify`. El escritor corre sólo sus tests.
- **El escritor no corre la suite completa** (lección de un slice anterior: el escritor que corre todo tarda y puede morir en silencio).
- Patrón de test obligatorio del repo: `@SpringBootTest` + `@Transactional`, MockMvc armado a mano con `webAppContextSetup(context).addFilters(springSecurityFilterChain)`.
- `businesses.id` se asigna a mano (sin `@GeneratedValue`) → ids fijos altos en fixtures.
- **`createOrder` es el único escritor de stock y de pedidos.** Confirmar una propuesta significa construir un `OrderRequestDTO` y llamarlo. No se duplica ni se reimplementa el decremento de stock, el re-lookup de precios, el `orderNumber` ni el broadcast.
- **Dependencia dura:** este slice asume cerrado `odd/tasks/cross-tenant-stock.md`. El normalizador emite `productId` desde texto libre; sin el aislamiento de tenant, la feature nueva hace el agujero más alcanzable (alcanza con nombrar un plato ajeno).
- Cada task cierra con un work-unit commit en la rama de la feature, **sujeto a autorización explícita del usuario**. Sin autorización, los cambios quedan en el working tree.
- **El payload de Meta transcripto abajo está escrito de memoria, no verificado contra la documentación oficial.** Es un riesgo declarado y tiene su propio pendiente en "Open gaps". El deliverable de T1 es congelarlo en un fixture y en el simulador; antes de conectar de verdad hay que contrastarlo, y si difiere, lo único que cambia es el fixture y el adapter.

### Contrato de Meta Cloud API a implementar

Handshake de alta (lo llama Meta una vez, y de nuevo ante cambios):

```
GET /api/whatsapp/webhook?hub.mode=subscribe&hub.verify_token=<TOKEN>&hub.challenge=<NONCE>
→ 200, Content-Type: text/plain, body = <NONCE> exacto
→ 403 si hub.verify_token no coincide
```

Ingreso (lo llama Meta en cada mensaje):

```json
{
  "object": "whatsapp_business_account",
  "entry": [{
    "id": "<WABA_ID>",
    "changes": [{
      "field": "messages",
      "value": {
        "messaging_product": "whatsapp",
        "metadata": { "display_phone_number": "54911...", "phone_number_id": "<PNID>" },
        "contacts": [{ "profile": { "name": "Juan" }, "wa_id": "54911..." }],
        "messages": [{
          "from": "54911...",
          "id": "wamid.HBg...",
          "timestamp": "1726000000",
          "type": "text",
          "text": { "body": "hola quiero 2 milanesas con papas y una coca" }
        }]
      }
    }]
  }]
}
```

Verificación de origen: cabecera `X-Hub-Signature-256: sha256=<hex>`, HMAC-SHA256 del **cuerpo crudo** con el app secret. En este slice el secreto sale de configuración (`whatsapp.app-secret`), con el mismo patrón de placeholder que `qr.public-menu-url` (`${QR_PUBLIC_MENU_URL:...}`). El simulador firma con el mismo secreto, así que el camino de verificación se ejercita de verdad y no queda como código muerto.

Respuesta: **200 con cuerpo `EVENT_RECEIVED`** (o `ignored`), siempre que el mensaje se haya persistido, incluso si el normalizador no entendió nada. Motivo (Hecho 7 del slice anterior): `GlobalExceptionHandler` mapea todo lo que no es `BusinessException` a **500**, y un 500 hace que el proveedor reintente. "No entendí el mensaje" es un resultado de negocio, no un error del servidor: se persiste como propuesta `FAILED` y se responde 200.

## Tasks

- [ ] **T1 — El contrato del canal.** Puerto `InboundMessage {channel, externalId, fromPhone, text, receivedAt}` + adapter que traduce el payload de Meta a ese puerto + `GET` de handshake + fixture con el payload exacto de arriba + `whatsapp-simulator.mjs` que firma el cuerpo con el app secret y lo POSTea. Tests: el handshake devuelve el `challenge` exacto en `text/plain`; con `hub.verify_token` incorrecto devuelve 403; un payload de Meta válido y bien firmado produce un `InboundMessage` con el texto intacto; un payload **mal firmado** se rechaza sin producir mensaje; un cuerpo que no es de WhatsApp (`object` equivocado, o `entry[].changes[].field` distinto de `messages`) no produce mensaje y responde 200.
- [ ] **T2 — Regla de seguridad explícita para la ruta.** Una regla propia en `SecurityConfig` para el webhook, con el test que cubre **las dos trampas**: (a) un `POST` anónimo sin firma no llega al controller y no crea nada; (b) un `GET` de handshake anónimo **sí** responde con el `challenge` cuando el token coincide. Motivo explícito: sin regla la ruta da 403; puesta bajo un prefijo ya permitido quedaría anónima sin que nadie lo note. El test fija cuál de las dos es la intención.
- [ ] **T3 — Resolver teléfono → negocio.** Normalización del número a forma canónica de dígitos antes de comparar (Meta manda sin `+`, y lo guardado es lo que tipeó el admin). Regla explícita para el caso ambiguo: **si dos negocios comparten número, se rechaza y se registra**, nunca se elige el primero. Tests: número que resuelve a un negocio; número desconocido → propuesta `FAILED` con motivo, y **200**; número ambiguo → rechazo registrado, sin propuesta asociada a ningún negocio. Incluye la decisión de unicidad de `Business.phone` con su pre-chequeo de datos existentes (mismo tratamiento que se le dio a `businesses.slug`, que ya es `@Column(unique = true, nullable = false)`).
- [ ] **T4 — Idempotencia por `messageId`.** El `id` de Meta (`wamid...`) es la clave natural. Un reintento del mismo mensaje no reprocesa ni crea una segunda propuesta. Test: el mismo payload firmado POSTeado dos veces produce **una** propuesta y **dos** respuestas 200. Sin este task, cada reintento del proveedor es una propuesta duplicada en la cara del encargado.
- [ ] **T5 — `OrderProposal` + líneas + `ProposalStatus` + repositorio.** La entidad guarda el mensaje crudo (texto, teléfono, `messageId`), el negocio resuelto, el estado de la propuesta y sus líneas interpretadas; sirve a la vez como registro del ingreso y como la tabla de idempotencia de T4. `ProposalStatus` es **propio** (`PENDING, CONFIRMED, REJECTED, FAILED`), no una extensión de `OrderStatus`. La entidad y sus queries **no** participan de `findActiveOrders` ni del broadcast: la garantía de que una propuesta no llega a la cocina es estructural. Tests: se persiste y se relee con sus líneas; `PENDING` no aparece en ninguna query de pedidos activos.
- [ ] **T6 — Normalizador determinista contra el catálogo.** Texto libre + `MenuDTO` del negocio → líneas candidatas. Cubre: cantidades en dígitos y en palabra ("2" y "dos"), separadores de lista, mayúsculas y acentos, y **reporte explícito de ambigüedad** en vez de adivinar (en el catálogo del ejemplo, "una coca" puede ser 500 ml o 2.25 L: se propone pedir precisión, no se elige). Test por tabla de casos: frase → líneas esperadas + conjunto de frases no resueltas. La ambigüedad es un resultado de primera clase, no un error.
- [ ] **T7 — Confirmación: de propuesta a pedido.** Endpoint autenticado (JWT; `businessId` sale del token, nunca de un parámetro) que lista las propuestas `PENDING` del negocio y otro que confirma una. Confirmar **construye un `OrderRequestDTO` y llama a `createOrder(slug, dto)`**, reusando el escritor único: precios re-leídos, stock atómico bajo lock, `orderNumber`, broadcast al KDS y métrica `pedidos.creados`, todo sin reimplementar nada. El pedido confirmado nace `NEW` y **ahí sí** aparece en la cocina. Descartar marca `REJECTED` y no toca stock. Tests: confirmar produce un pedido idéntico al que produciría el menú para las mismas líneas; **confirmar no descuenta stock dos veces**; descartar no descuenta nada; un negocio no puede listar ni confirmar propuestas de otro; una propuesta ya decidida no se puede volver a confirmar.
- [ ] **T8 — Sección de propuestas en el KDS.** Panel aparte (no una columna de pedidos) que muestra el texto original del cliente junto a las líneas interpretadas y las frases no resueltas, con confirmar y descartar. Refresco manual en este slice (Out #4). Tests JS en el patrón del repo (`node api/src/test/js/*.test.mjs`, sandbox `vm` con stubs de DOM y `fetch`, sin browser): una propuesta ambigua se muestra marcada y **no** ofrece confirmar como si estuviera completa; una propuesta sin negocio resuelto no aparece; el botón de confirmar llama al endpoint real y no reenvía dos veces ante doble clic.

## Acceptance criteria

| ID | Criterio |
| --- | --- |
| AC1 | `GET` de handshake con token correcto → 200, `text/plain`, `challenge` exacto; con token incorrecto → 403 |
| AC2 | Un `POST` de Meta bien firmado con texto libre persiste **una** propuesta `PENDING` con el texto original intacto |
| AC3 | Un `POST` con firma inválida no persiste nada y no llega a la lógica de negocio |
| AC4 | El mismo mensaje POSTeado dos veces produce **una** sola propuesta |
| AC5 | Un número desconocido produce 200 y un registro `FAILED` con motivo; no crea propuesta asociada a ningún negocio |
| AC6 | Un número compartido por dos negocios se rechaza y queda registrado; nunca se rutea a uno de los dos |
| AC7 | "hola quiero 2 milanesas con papas y una coca" contra un catálogo con dos tamaños de coca produce las líneas resueltas **y** marca la coca como ambigua |
| AC8 | Una propuesta `PENDING` **no** aparece en `findActiveOrders` ni en el KDS de pedidos |
| AC9 | Confirmar una propuesta crea un pedido `NEW` con las mismas líneas, precios y total que el mismo pedido hecho por el menú |
| AC10 | Confirmar descuenta stock **una sola vez**, y descartar no descuenta nada |
| AC11 | Un negocio no puede listar ni confirmar propuestas de otro negocio (403 o lista vacía, consistente con el resto del panel) |
| AC12 | `mvn -B --no-transfer-progress verify` verde, más los tests de node verdes |
| AC13 | El simulador corre end to end contra la app levantada y produce una propuesta visible en el panel del KDS |

## Progress

| Task | Estado | Evidencia |
| --- | --- | --- |
| T1 | pendiente | — |
| T2 | pendiente | — |
| T3 | pendiente | — |
| T4 | pendiente | — |
| T5 | pendiente | — |
| T6 | pendiente | — |
| T7 | pendiente | — |
| T8 | pendiente | — |

## Evidence

### RED (pendiente)

### GREEN (pendiente)

### Verificación independiente (pendiente)

## Open gaps and decisions

1. **El payload de Meta está transcripto de memoria.** No se consultó la documentación oficial de Cloud API. Antes de conectar de verdad hay que contrastar: nombre y forma de `X-Hub-Signature-256`, ubicación exacta del texto en `entry[].changes[].value.messages[]`, campos obligatorios del handshake, y si Meta exige responder 200 en menos de un umbral de tiempo. Si algo difiere, lo que cambia es el fixture y el adapter, no el puerto `InboundMessage` — que es exactamente para lo que está el puerto.
2. **El simulador no prueba que el contrato sea el de Meta.** Prueba que nuestro código es consistente con nuestra transcripción del contrato. Son dos afirmaciones distintas y conviene no confundirlas al leer la evidencia.
3. **Sin unicidad previa en `Business.phone`.** Puede haber datos existentes con teléfonos duplicados o mal tipeados, así que agregar la restricción necesita pre-chequeo de datos y probablemente backfill. Es la misma situación que `businesses.slug`, que ya resolvió con `@Column(unique = true, nullable = false)` más un `ALTER TABLE` manual (no hay Flyway/Liquibase en el repo: `ddl-auto: update` no altera una columna existente).
4. **Productos con `stock = null` podrían faltar en el catálogo con el que normaliza.** Mismo pendiente registrado en `cross-tenant-stock.md`: la query del menú público pide `stock > 0` y en SQL `NULL > 0` no es verdadero, mientras `Product.java:52-54` documenta que `null` significa stock infinito. Si es cierto, el normalizador no va a poder proponer esos productos: propondría "no tengo eso" para algo que sí se vende. **Inferencia sin verificar**: se resuelve insertando un producto con `stock = null` y leyendo `GET /api/menu/{slug}`.
5. **`ComboItemDTO` no expone el `productId` de los componentes** (sólo `productName` y `quantity`, vía `IComboMapperImpl.java:32-33`). Un combo se puede proponer y confirmar por `comboId`, pero el normalizador no puede validar ni re-preciar los componentes desde el payload del menú.
6. **Sin expiración de propuestas** (Out #7). Una propuesta vieja queda pendiente para siempre y el encargado ve basura acumulada. Necesita una decisión de producto: ¿caduca?, ¿se archiva?, ¿cuánto tiempo?
7. **Sin realtime** (Out #4). El encargado tiene que refrescar para ver una propuesta nueva. Es aceptable para un primer slice, pero el valor real del KDS es el tiempo real, y la exposición anónima de `/ws/**` (Hecho 8) es lo que bloquea resolverlo bien. Ese es el próximo slice de seguridad evidente.
8. **Sin edición de líneas** (Out #6). Si el normalizador entiende 2 de 3 líneas, el humano confirma o descarta todo. Editar es la salida natural y probablemente el primer refinamiento pedido en uso real.
