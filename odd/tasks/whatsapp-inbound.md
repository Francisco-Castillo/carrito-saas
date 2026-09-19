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

### Hecho 9 — hallado al implementar: Spring Boot 4 usa Jackson 3, y `service/` no tiene Jackson en compile scope

Dos hechos que el diseño no anticipó y que forzaron una decisión:

1. **Spring Boot 4 trae Jackson 3** (`tools.jackson.*`), no 2.x (`com.fasterxml.jackson.*`). Todo import nuevo tiene que ser de Jackson 3.
2. **El módulo `service` no tiene Jackson en el classpath de compilación** (llega sólo en runtime, vía jjwt). Agregar dependencias está fuera de alcance.

Consecuencia implementada: `MetaWebhookPayload` es un árbol de records **sin anotaciones Jackson**, y el mapeo de los nombres de wire (`messaging_product`, `phone_number_id`, `wa_id`, `display_phone_number`) lo hace un `JsonMapper` dedicado con `PropertyNamingStrategies.SNAKE_CASE` en el controller, donde Jackson sí está en compile scope.

**Riesgo que eso introduce, y hay que decirlo**: renombrar un componente del record deja de ser un cambio de compilación y pasa a ser un **cambio silencioso del contrato de red**. Sin anotaciones, nada en el código declara el nombre de wire; lo declara el `SNAKE_CASE` derivado del nombre del componente. Está anotado en el javadoc, pero es fragilidad real de la solución, no un detalle.

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

- [x] **T1 — El contrato del canal.** Puerto `InboundMessage {channel, externalId, fromPhone, text, receivedAt}` + adapter que traduce el payload de Meta a ese puerto + `GET` de handshake + fixture con el payload exacto de arriba + `whatsapp-simulator.mjs` que firma el cuerpo con el app secret y lo POSTea. Tests: el handshake devuelve el `challenge` exacto en `text/plain`; con `hub.verify_token` incorrecto devuelve 403; un payload de Meta válido y bien firmado produce un `InboundMessage` con el texto intacto; un payload **mal firmado** se rechaza sin producir mensaje; un cuerpo que no es de WhatsApp (`object` equivocado, o `entry[].changes[].field` distinto de `messages`) no produce mensaje y responde 200.
- [x] **T2 — Regla de seguridad explícita para la ruta.** Una regla propia en `SecurityConfig` para el webhook, con el test que cubre **las dos trampas**: (a) un `POST` anónimo sin firma no llega al controller y no crea nada; (b) un `GET` de handshake anónimo **sí** responde con el `challenge` cuando el token coincide. Motivo explícito: sin regla la ruta da 403; puesta bajo un prefijo ya permitido quedaría anónima sin que nadie lo note. El test fija cuál de las dos es la intención.
- [x] **T3 — Resolver teléfono → negocio.** Normalización del número a forma canónica de dígitos antes de comparar (Meta manda sin `+`, y lo guardado es lo que tipeó el admin). Regla explícita para el caso ambiguo: **si dos negocios comparten número, se rechaza y se registra**, nunca se elige el primero.
    - **Alcance acotado (ajuste del 2026-09-19)**: el resolver devuelve un **resultado** —resuelto / no encontrado / ambiguo— y **no persiste nada**. Registrar la propuesta `FAILED` con su motivo es T5. Así T3 se prueba sin esquema y sin acoplarse a `OrderProposal`, en vez de arrastrar dependencia hacia adelante.
    - **La constraint `UNIQUE (phone)` queda FUERA, por decisión explícita.** Rechazar la ambigüedad al resolver y prohibirla en la base son dos cosas distintas, y la segunda tiene una consecuencia de producto sin decidir: `phone` es el `whatsappNumber` que la carta pública usa para el fallback de `wa.me`, así que volverlo único **prohíbe que dos locales compartan número** (el caso "un dueño, dos sucursales"). Además, con la constraint puesta el camino de "rechazar y registrar" se vuelve **inalcanzable y por lo tanto código muerto**, testeable sólo con fixtures retorcidos. Preferimos la defensa **alcanzable**: índice **no** único para la búsqueda, y la decisión de la constraint como decisión abierta del usuario (pendiente 25).
    - **Riesgo de divergencia que hay que matar**: la normalización del valor **entrante** es Java y la del valor **almacenado** tiene que ser SQL. Dos implementaciones de la misma regla derivan en silencio. Un test tiene que alimentar la misma tabla de entradas a las dos y exigir que coincidan.
    - Tests: número que resuelve a un negocio, con variantes de formato (`+54 9 11 2233-4455` vs `5491122334455`); número desconocido → no encontrado; número compartido por dos negocios → **ambiguo**, y **nunca** se elige uno; filas con `phone` nulo o vacío no matchean nada; y la coincidencia Java↔SQL sobre la tabla de entradas.

- [x] **T3b — Canonicalización a E.164 con región por defecto (reemplaza la comparación por sólo dígitos).** Decisión del usuario (2026-09-19): el resolver parsea **los dos lados** con `libphonenumber` usando una región por defecto configurable (`whatsapp.default-region`, `AR`) y compara en forma E.164. Motivo: el admin argentino tipea `011 2233-4455` y Meta manda `5491122334455`; "sólo dígitos" los deja sin matchear, así que el feature **funcionaba en los tests y fallaba con el primer cliente real**. Confirmado empíricamente por la verificación en las dos capas: `011 2233-4455` → `01122334455` vs `5491122334455` → **`NotFound`**. La regla sólo unía formatos internacionales.
    - **Supera y elimina el diseño anterior.** `regexp_replace` puede sacar adornos pero **no puede interpretar** un número, así que el diseño "una regla, dos runtimes" **no sobrevive a E.164**: se van la query nativa, la constante `NON_DIGIT_CHARS`, el índice funcional y el test de acuerdo Java↔SQL. Queda **una sola implementación** (Java) y con ella el riesgo de divergencia **desaparece** en vez de testearse.
    - **Comparación en memoria**: se cargan los negocios con teléfono no nulo y se compara el canónico. A esta escala (locales de una ciudad) es lo correcto y lo simple. Escalar a miles pide una columna canónica, y eso pide un camino de escritura que hoy no existe (pendiente 27).
    - **La tabla de formatos se resuelve empíricamente, no por lo que yo crea.** Se corren los formatos realistas (`011 2233-4455`, `11 2233-4455`, `011 15 2233 4455`, `01122334455`, `+54 9 11 2233-4455`, `5491122334455`) contra el remitente `5491122334455` de Meta y **se reporta cuáles canonicalizan igual y cuáles no**; se assertea lo que es verdad y lo que no **se reporta como limitación**, sin inventar la expectativa. La numeración móvil argentina (trunk `0`, prefijo `15`, el `9` internacional) es genuinamente enredada: si `libphonenumber` no une alguno, eso es un hallazgo, no algo para acomodar.
    - **Ambigüedad nueva y real**: dos negocios, uno con el número guardado local y el otro internacional, **mismo abonado** → ambos canonicalizan al mismo E.164 → **`Ambiguous`**, y nunca se elige uno. Es exactamente el caso que el test defectuoso del escritor describía sin querer.
    - **Validación al guardar: diferida por decisión** (no existe alta de negocios: `BusinessController` sólo tiene GETs, `IBusinessService` sólo tiene `getBusinessBySlug`, y `BusinessMapperImpl.toEntity` **no tiene llamadores**). El fallo se hace visible en la propuesta `FAILED` de T5. Obligación documentada para cuando exista el alta.
    - **Deliberar `isPossibleNumber` vs `isValidNumber`** y decirlo: demasiado estricto produce `NotFound` falsos; demasiado laxo produce matches falsos, y un match falso manda el pedido al local equivocado.
    - `whatsapp.default-region` entra en la validación de arranque de `WhatsappProperties.validate()`: en blanco o región inválida, la app **no arranca**, por el mismo criterio que F1.

- [x] **T3c — Guardar el teléfono canónico: CHECK de forma + UNIQUE (hace cumplir "cada local con su número").** Decisión del usuario (2026-09-19): cada local tiene su propio número, así que la política se hace cumplir. `Business.phone` pasa a guardar **dígitos E.164 canónicos** o `NULL`. **Depende de T3b** y toca sus mismos archivos, así que va después.
    - **Por qué `UNIQUE (phone)` sobre el string crudo no alcanza**: `011 2233-4455` y `+54 9 11 2233-4455` son **strings distintos** para el **mismo abonado**, así que la constraint los deja pasar y el resolver después contesta `Ambiguous`. La igualdad que usa el resolver es E.164 y **no es expresable como constraint SQL**: `regexp_replace` saca adornos, no interpreta. La única forma de que la constraint haga cumplir la política es que **lo guardado sea lo comparado**. Y una constraint que no ve el caso que se quiere prohibir es peor que no tenerla, porque da una garantía falsa que nadie vuelve a mirar.
    - Esquema: `CHECK (phone IS NULL OR phone ~ '^[1-9][0-9]{7,14}$')` + `UNIQUE (phone)`. En Postgres los `NULL` son distintos dentro de un índice único, así que un negocio sin teléfono sigue siendo posible y los `NULL` no colisionan entre sí.
    - Se agrega `@Column(unique = true)` en la entidad **y** se aplica el SQL a mano, siguiendo el precedente de `businesses.slug`: `ddl-auto: update` no altera de forma confiable una tabla existente y no hay Flyway. Ambos pasos van documentados en el javadoc, con el SQL exacto, porque el esquema del proyecto **no es reproducible desde el repo** (pendiente 30) y no queremos agravar eso en silencio.
    - **Normalizar las filas existentes** (hoy hay una: `demo-pizzeria`), reportando el valor antes y después.
    - **La validación al guardar que T3b difirió llega acá como CHECK** — es el único lugar posible, porque no existe alta de negocios. Cuando ese alta exista, tiene que canonicalizar con la **misma** función de `PhoneNumbers` (obligación documentada).
    - **El problema que introduce hacer cumplir la política: la defensa queda inalcanzable.** Con CHECK + UNIQUE dos negocios no pueden compartir canónico, así que `Ambiguous` es defensa para un estado que la base prohíbe — o sea, código muerto e **intesteable por la vía de la base**. La regla del proyecto es que cada capa tenga un test que falle cuando se la quita, y meterla en la base no puede significar perder su test. Solución: **extraer la lógica de matching como función pura** sobre una colección de `(businessId, phone)` → `PhoneResolution`, y testear el caso ambiguo ahí, sin necesitar que la base permita el estado. El resolver queda como el adaptador que carga y delega.
    - Tests: el caso **ambiguo por función pura** (dos entradas con el mismo canónico → `Ambiguous`, y nunca una elección); el camino real contra la base con un solo negocio → `Resolved`; y que la base **rechace** las dos violaciones — duplicado canónico y valor no canónico—, probadas cada una, porque un constraint que no se prueba no se sabe si está.
- [x] **T4 — Idempotencia por `messageId`.** El `id` de Meta (`wamid...`) es la clave natural. Un reintento del mismo mensaje no reprocesa ni crea una segunda propuesta. Test: el mismo payload firmado POSTeado dos veces produce **una** propuesta y **dos** respuestas 200. Sin este task, cada reintento del proveedor es una propuesta duplicada en la cara del encargado.
- [x] **T5 — `OrderProposal` + líneas + `ProposalStatus` + repositorio.** La entidad guarda el mensaje crudo (texto, teléfono, `messageId`), el negocio resuelto, el estado de la propuesta y sus líneas interpretadas; sirve a la vez como registro del ingreso y como la tabla de idempotencia de T4. `ProposalStatus` es **propio** (`PENDING, CONFIRMED, REJECTED, FAILED`), no una extensión de `OrderStatus`. La entidad y sus queries **no** participan de `findActiveOrders` ni del broadcast: la garantía de que una propuesta no llega a la cocina es estructural. Tests: se persiste y se relee con sus líneas; `PENDING` no aparece en ninguna query de pedidos activos. **Obligación heredada de la verificación de T1/T2, en dos partes**: (a) la **ausencia** de handler tiene que ser un fallo ruidoso, no un descarte silencioso (pendiente 9); (b) un handler que **lanza** tampoco puede terminar en 200 (pendiente 16) — si la persistencia falla porque la base está caída, un 200 le dice a Meta que el mensaje se procesó y el pedido se pierde sin rastro. Con la base caída, la respuesta correcta es **500** para que Meta reintente.
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
| T1 | hecho | RED: 8/9 en rojo con **403 de la filter chain** y cuerpo vacío. GREEN 9/9. Simulador ejecutable, 935 bytes, byte-idéntico al fixture del test |
| T2 | hecho | Regla explícita `permitAll` para `/api/whatsapp/**`, con la **firma como única autorización**. Es la regla la que convierte el 403 en 200 — el RED lo prueba |
| T3 | hecho, **canonicalización superada por T3b** | RED literal: `Tests run: 7, Failures: 6`. GREEN `8/8`. El resolver, el tipo sellado y la regla de ambigüedad quedan; lo que cambia es cómo se comparan los números. Ver la nota de evidencia abajo |
| T3b | hecho | Reemplaza la comparación por sólo dígitos: `libphonenumber 9.0.39`, parseo de los dos lados con región `AR`, comparación en memoria. RED literal: `Tests run: 9, Failures: 1` (`realisticLocalStoredFormatResolvesMetaSender` → `NotFound`). GREEN 10/10. Sensibilidad probada: el mutante "sólo dígitos" da `Failures: 4`. Ver la tabla empírica abajo |
| T3c | hecho | Verde local 22/22, **verde contra base vacía 17/17** y verificado: RED reproducido por el verificador en base propia (`Failures: 3`) y mutación que quita el `check` que confirma que el test del CHECK puede fallar. El `CHECK` se expresa con la anotación **estándar** `jakarta.persistence.CheckConstraint`. Ver "Paridad con CI" y "El hallazgo bloqueante de la región" abajo |
| T3d | hecho | Cierra el bloqueante de la región: normalización en un punto único (`normalizeRegion`), consumido por los dos llamadores. RED literal con región `ar` → `NotFound`, mutación que falla sólo el test nuevo. Suite final **82 Java + 34 node** |
| T4 | hecho | Idempotencia por `message_id` con constraint **nombrada** (`uq_order_proposals_message_id`). El handler **no** es `@Transactional`, así que la violación del constraint se captura **fuera** de toda frontera transaccional y no hay transacción rollback-only que propagar. Atribución por **nombre** de constraint, con mutación que lo prueba. Dos POST iguales → **una** propuesta y **dos** 200. Ver la nota de `message_id` nullable |
| T5 | hecho | Entidad + `ProposalStatus` propio + repositorio + handler que persiste de verdad. RED literal `Tests run: 9, Failures: 5, Errors: 4` → GREEN 11/11. `WhatsappWebhookContractTests` quedó en **15/15 sin cambiar ninguna aserción**. Sin regresión en el camino de teléfonos (18/18). Suite final **93 Java + 34 node** |
| — | hecho | **Verificación independiente de T4/T5: PASS WITH FINDINGS, sin bloqueantes.** Los cuatro mutantes del reparto de fallos confirmados (tragarse la infraestructura, lanzar ante negocio, atribución genérica, descarte silencioso), paridad con base vacía verde, y tres follow-ups **cerrados**. **Re-verificación: PASS WITH FINDINGS.** Suite completa **dos veces seguidas** con cero residuo, y los dos hallazgos nuevos (limpieza que borraba filas ajenas, y FK que rompía en T6) cerrados con RED contra el código viejo. Suite final **99 Java + 34 node** |
| T6 | pendiente | — |
| T7 | pendiente | — |
| T8 | pendiente | — |
| — | hecho | F1/F2/F4 cerrados con RED observado y sensibilidad probada por mutación (ver "Cierre de F1, F2 y F4"). `api: Tests run: 59`, 8 módulos SUCCESS. F3 sigue diferido a T5 por decisión |
| — | hecho | Pendiente 21 cerrado: `WhatsappPropertiesStartupValidationTests` prueba el **ciclo de vida del contexto**, no el método. RED literal con el `@PostConstruct` quitado: `but context started successfully` (2 fallas). Suite final **`api: Tests run: 62`** + node **34/34** |
| — | **PASS WITH FINDINGS** | Verificación independiente de T1+T2: **sin bloqueantes**. El HMAC sobre bytes crudos quedó **probado por mutación**; las dos capas de seguridad son load-bearing. `api: Tests run: 53`, 8 módulos SUCCESS. Cinco follow-ups, de los cuales dos se cierran en el acto |

## Evidence

### RED (literal)

**T1+T2 juntos — el 403 que prueba que la regla de seguridad es la que sostiene la ruta** (`-Dtest=WhatsappWebhookContractTests`, corrida **pre-regla**, sin tocar `SecurityConfig`):

```text
Tests run: 9, Failures: 8, Errors: 0, Skipped: 0
anonymousGetHandshakeWithCorrectTokenEchoesExactChallenge:177   Status expected:<200> but was:<403>
wellSignedMetaTextMessageProducesInboundMessageWithIntactText:206 Status expected:<200> but was:<403>
payloadWithWrongObjectProducesNoMessageButResponds200:246       Status expected:<200> but was:<403>
changeWithFieldOtherThanMessagesProducesNoMessageButResponds200:262 Status expected:<200> but was:<403>
nonTextMessageTypeProducesNoMessageButResponds200:277           Status expected:<200> but was:<403>
correctlySignedButMalformedJsonIsRejectedWith400...:295          Status expected:<400> but was:<403>
badlySignedPayloadIsRejectedWithoutProducingMessage:231         Response content expected:<invalid signature> but was:<>
anonymousPostWithoutSignatureIsRejected...:317                  Response content expected:<invalid signature> but was:<>
```

Ocho de nueve en rojo, todos **403 con cuerpo vacío**. El detalle importa más de lo que parece: los dos tests que *esperan* un rechazo (sin firma y mal firmado) **también** fallaban — pero por el **cuerpo vacío**, no por el status. Es decir: sin mirar el cuerpo, **una firma decorativa habría pasado en verde**, porque el 403 de la filter chain es indistinguible del 403 del controller si sólo se asserta el status. Por eso el test exige el cuerpo `invalid signature`: mientras el rechazo venga de la cadena de seguridad y no del verificador, falla.

### GREEN

`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` tras agregar la regla de T2.

Suite completa (la corrió el padre, por contrato): **`api: Tests run: 53`** (eran 44), los 8 módulos `SUCCESS`, reactor completo, `BUILD SUCCESS`.

### Triangulación

- **El HMAC sobre bytes crudos, probado en las dos direcciones.** El test de camino feliz firma con su propio `HmacSHA256` del JDK — no con el firmador de producción, que sólo probaría que el código es consistente consigo mismo — y un segundo test firma el **mismo cuerpo** con un secreto distinto (`attacker-secret`) y exige 403 + `verifyNoInteractions(handler)`. Una firma decorativa no puede pasar las dos.
- Objeto raíz equivocado, `field` distinto de `messages`, tipo `image` y texto vacío → 200 y **ningún** mensaje.
- JSON malformado **pero bien firmado** → **400**, nunca 500. Importa: `GlobalExceptionHandler` mapea todo a 500, y **un 500 le dice a Meta "reintentá"**, con lo que un payload roto se reintentaría para siempre.
- Identidad de bytes simulador ↔ fixture: 935 bytes, `byte-identical: true`.

### Verificación independiente — **PASS WITH FINDINGS** (agente distinto, read-only, todo en copias de `/tmp`)

**1. El HMAC cubre los bytes crudos — probado, no leído.** Dos cuerpos **semánticamente iguales** (`semantic-equal=true`) pero byte-distintos (935 vs 450 bytes, uno compactado y con `object` al final):

| Request | Status | Cuerpo |
| --- | --- | --- |
| A + `sig(A)` (byte-idéntico) | **200** | `EVENT_RECEIVED` |
| B + `sig(A)` | **403** | `invalid signature` |
| B + `sig(B)` | **200** | `EVENT_RECEIVED` |
| A + `sig(B)` | **403** | `invalid signature` |

B se rechaza **sólo** porque sus bytes difieren — con su propia firma pasa, así que no es "inparseable" ni inválido por otra razón. Si el cuerpo se bindeara a un DTO antes de verificar, la fila `B + sig(A)` daría 200. No la da. **La verificación es real, no decorativa.**

**2. Las dos capas son load-bearing** (mutaciones en copias `/tmp`, cada mutante comprobado compilado):

| Mutante | Resultado |
| --- | --- |
| `verifySignature` siempre `true` | **2 fallas**: los dos tests de rechazo pasan a `expected:<403> but was:<200>` |
| Regla `permitAll` eliminada | **8 fallas, todas 403 con cuerpo vacío** — reproduce el RED del documento **verbatim**, con los mismos 8 nombres y los mismos números de línea |
| `phoneNumberId` → `phoneNumberIdentifier` | **9/9 VERDE** — el campo deja de bindear en silencio (ver F4) |
| Texto truncado a 10 chars | **1 falla** — la aserción de texto intacto realmente lo sostiene |

El primer mutante es el que importa: los tests de rechazo **no** están verdes porque la cadena rechace; pasan a 200 en cuanto el verificador deja de verificar.

**3. Superficie de ataque: ninguna bypass encontrada.** 16 sondas con el header manipulado: ausente, vacío, `sha256=` solo, prefijo `sha1=`, hex truncado, hex de largo impar, hex no-hexadecimal, valor incorrecto del largo correcto, firma de cuerpo vacío, cuerpo ausente. **Todas 403**, ninguna excepción escapando como 500 (`sha256=zzzz` y el hex de largo impar los atrapa el `catch (IllegalArgumentException)` y devuelven 403). Dos variantes aceptadas: **hex en mayúsculas** y **espacio al final** (efecto de `.trim().toLowerCase()`), ambas inofensivas porque el valor comparado siguen siendo los 32 bytes exactos del HMAC. Comparación constante confirmada (`MessageDigest.isEqual`), sin `String.equals` en la clase.

**4. Trampa espejo: NO cometida.** Bajo `/api/whatsapp/**` hay exactamente dos mappings (`GET`/`POST /api/whatsapp/webhook`) y ningún estático; la ruta no está bajo `/api/restaurants/**` ni `/api/menu/**`. La mutación que quita la regla prueba que **es esa regla** la que autoriza la ruta.

**5. Afirmaciones verificadas en vez de aceptadas.**
- Simulador ≡ fixture: **reproducido independientemente** — 935 bytes los dos, `sha256: 70c66e43…6313`, byte-idénticos. **Pero sigue siendo una corrida manual, no un test** (pendiente 10).
- JSON malformado bien firmado → **400, más amplio de lo afirmado**: también `[]`, `"hello"`, `12345`, `{"entry":5}`, JSON truncado y **UTF-8 inválido** dan 400. Nunca 500.
- Texto intacto: `ArgumentCaptor` con la cadena completa, y el mutante de truncado lo hace fallar.
- Sólo el primer mensaje de una notificación: confirmado (`times(1)`, el segundo `wamid` se descarta en silencio).
- Sin anotaciones Jackson: confirmado, y **M3 prueba que renombrar un componente deja 9/9 en verde** mientras el campo deja de bindear (pendiente 11).

**6. Hallazgos: ninguno BLOQUEANTE para T1/T2.**

| ID | Severidad | Qué |
| --- | --- | --- |
| F1 | follow-up — **CERRADO** | Con `whatsapp.app-secret` **vacío**, un POST firmado da **500** — la respuesta que este mismo documento argumenta que nunca debe producirse, porque le dice a Meta "reintentá" para siempre. `SecretKeySpec` rechaza la clave vacía y el `catch` la re-lanza como `IllegalStateException`. El mismo probe muestra que un `verify-token` vacío deja que cualquiera pase el handshake. **Falta validación de arranque.** |
| F2 | follow-up — **CERRADO** | La regla es `prefix-wide` (`/api/whatsapp/**`), que recrea la trampa espejo en miniatura: hoy no expone nada de más, pero cualquier endpoint futuro bajo ese prefijo queda **silenciosamente anónimo**. |
| F3 | follow-up (**obligación de T5, ampliada**) | `handOff` atrapa `RuntimeException` por handler y **igual responde 200**: un handler que falla pierde el mensaje con sólo una línea de ERROR. El pendiente 9 cubría "sin handler", no "handler que lanza". |
| F4 | follow-up — **CERRADO** | Los nombres de wire no están fijados por ningún test: M3 lo prueba. La fragilidad del Hecho 9 no es hipotética. |
| F5 | observación | La distinción 403-controller vs 403-filter-chain descansa sólo en el cuerpo de la respuesta. El pendiente 13 es correcto y la mutación lo confirma. |

### Cierre de F1, F2 y F4

| Finding | Cómo se cerró | Prueba de sensibilidad |
| --- | --- | --- |
| F1 | `WhatsappProperties.validate()`, llamado desde `@PostConstruct`: con `app-secret` o `verify-token` en blanco o nulo la aplicación **se niega a arrancar**, nombrando la propiedad. Se testea el método directo, sin bootear un contexto | RED literal: error de compilación (`cannot find symbol: method validate()`) — el RED correcto para un método que no existía |
| F2 | La regla se estrechó a `/api/whatsapp/webhook` exacto. Un `@RestController` de prueba bajo `/api/whatsapp/not-the-webhook` debe dar 403 anónimo | **RED observado y real**: con la regla todavía prefix-wide, la sonda dio `Status expected:<403> but was:<200>`. **La trampa espejo estaba viva**, no era hipotética. Y ensanchar la regla de vuelta en `/tmp` vuelve a romper la sonda |
| F4 | El `JsonMapper` con `SNAKE_CASE` salió del controller a un `@Component` inyectable (`MetaWebhookJsonMapper`, en `api`, el único módulo con Jackson en compile scope); un test deserializa el fixture **con el mapper de producción** y fija cada nombre de wire | Mutación en `/tmp` renombrando `phoneNumberId` → `phoneNumberIdentifier` (también en el accesor del test, simulando a alguien que actualiza los sitios de compilación): `expected: "2222222222" but was: null` |

**Lo que esto NO prueba**, y el test lo dice en su javadoc: fija **nuestros** errores de tipeo y renombres, **no** valida que la transcripción del contrato de Meta sea correcta (pendiente 19).

Ningún test existente se modificó: los 9 originales quedaron intactos. Sin dependencias nuevas. Suite completa: **`api: Tests run: 59`** (eran 53), los 8 módulos `SUCCESS`.

### Re-verificación tras el cierre de F1/F2/F4 — **PASS**

Sin bloqueantes. La prueba que importa es que **la propiedad del HMAC sobrevive al refactor**: el parseo se movió a un componente inyectable, y eso podía matar la propiedad sin que fallara un solo test. Rehecha desde cero con dos cuerpos semánticamente iguales y byte-distintos (A = 935 bytes, `sha256 70c66e43…` — el mismo hash que la verificación anterior, así que el refactor no movió los bytes del fixture; B = 450 bytes compactado con claves reordenadas), con la igualdad semántica probada **a través del mapper de producción**:

| Request | Status | Cuerpo |
| --- | --- | --- |
| A + `sig(A)` | **200** | `EVENT_RECEIVED` |
| B + `sig(A)` | **403** | `invalid signature` |
| B + `sig(B)` | **200** | `EVENT_RECEIVED` |
| A + `sig(B)` | **403** | `invalid signature` |

**Sin regresión.** Y el único sitio de parseo del repo es `MetaWebhookJsonMapper.java:42-43`: no se reintrodujo ningún binding a DTO.

Resto del delta, todo confirmado:
- **La regla estrechada sigue siendo load-bearing**: quitarla → 9 fallas con 403. **Ensancharla a `/api/whatsapp/**` → la sonda falla** (`expected:<403> but was:<200>`), que es la propiedad que buscábamos: el estrechamiento está pinneado contra un futuro ensanche. La sonda además prueba que el controller de prueba **está registrado** (una ruta inexistente no podría responder 200).
- **`validate()` falla en el arranque de verdad**, probado a nivel de contexto: `BeanCreationException` → `IllegalStateException: [whatsapp.app-secret must not be blank]` dentro de `refresh()`, antes de servir nada. Esto **cierra un agujero del enfoque de test de F1**: los 4 tests del escritor llaman a `validate()` directo, así que un `@PostConstruct` que dejara de invocarse los dejaría verdes.
- **El mapper es el único lugar con `SNAKE_CASE`** (los otros tres `ObjectMapper` del repo son Jackson 2 sin naming strategy y no tocan `MetaWebhookPayload`); la configuración quedó byte a byte igual a la que estaba inline.
- **JSON malformado sigue dando 400, nunca 500**, ahora a través del mapper inyectado: `[]`, `"hello"`, `12345`, `{"entry":5}`, truncado, no-JSON y **UTF-8 inválido**.
- **Los 9 tests originales sin debilitar**: `insertions: 139, deletions: 0`. Ninguna línea preexistente se cambió ni se borró.

**Caveat honesto sobre la procedencia de esa evidencia**: el archivo de test es **untracked**, así que el diff 139/0 se apoya en la copia que el verificador anterior dejó en `/tmp`, no en un commit. Es la consecuencia concreta del pendiente del doc-first: sin commits, la precedencia y la integridad se sostienen con copias de terceros.

**Tests de node**, que `mvn verify` **no** corre y AC12 exige: verificados aparte — `frontend-origin` 13/13, `menu-app` 4/4, `page-guard` 17/17, **34/34, exit 0**.

### Resolución de teléfonos (T3 → T3b, con T3c en curso)

**Tabla empírica** (probada antes de escribir las aserciones, `libphonenumber 9.0.39`, región `AR`, remitente de Meta `5491122334455`):

| entrada guardada | canónico E.164 | ¿une al remitente? | resultado |
| --- | --- | --- | --- |
| `011 15 2233 4455` | `+5491122334455` | **SÍ** | `Resolved` |
| `+54 9 11 2233-4455` | `+5491122334455` | SÍ | `Resolved` |
| `5491122334455` | `+5491122334455` | SÍ | `Resolved` |
| `011 2233-4455` | `+541122334455` | **NO** | `NotFound` |
| `11 2233-4455` | `+541122334455` | **NO** | `NotFound` |
| `01122334455` | `+541122334455` | **NO** | `NotFound` |

**Mi ejemplo del formato argentino estaba mal, y el escritor lo probó en vez de acomodar el test.** Yo había puesto `011 2233-4455` como "el formato que tipea el admin". Empíricamente ese formato es la **forma de línea fija** (`+5411…`, sin el `9` móvil), y en el plan argentino **es otro número**: el `15` doméstico es lo que lo vuelve el móvil `2233-4455`. Forzar el puente habría sido exactamente el **ruteo al local equivocado** que T3b existe para evitar. Es la segunda vez que una sonda empírica corrige una afirmación mía, después del `stock = null` del slice anterior.

**La justificación no obvia de NO unir, que conviene tener escrita**: la contra-heurística "si el stored parece línea fija pero el canal es WhatsApp, interpretalo como móvil" suena razonable y es **peligrosa**. Un negocio que guardó una línea fija probablemente guardó un número por el que **no** atiende WhatsApp; el remitente móvil que coincide en los dígitos finales no es su cliente. Preferir la interpretación móvil rutearía ese pedido a un local que ni usa el canal. **Un `NotFound` visible es mejor que un match plausible y equivocado.**

**Cobertura real y su límite honesto**: une bien los formatos que llevan la marca de móvil —`15` doméstico, `9` internacional, o E.164 pelado—. Un valor guardado que **omite** la marca se interpreta como línea fija y no resuelve; el admin que tipea el móvil sin el `15` (habitual al escribirlo en papel, porque el `15` sólo se usa para llamar dentro del país) cae ahí. La visibilidad diseñada es el registro `FAILED` de T5: falla **visible y corregible**, nunca ruteo silencioso.

**Límites que la verificación amplió, y que antes no estaban dichos**: `15 2233 4455` y `02233 4455` (sin área o sin trunk) → `null`; y un número **de otro país** tipeado sin `+` bajo región `AR` → `null` aunque `isPossibleNumber` lo acepte (es límite de una sola región por defecto, no un falso negativo argentino). Vale saberlo porque el proyecto podría recibir mensajes de números no argentinos algún día.

**Decisión `isValidNumber` en vez de `isPossibleNumber`**, por el motivo que importa: una regla laxa produce match falso, y un match falso **manda el pedido al local equivocado**; una regla estricta produce `NotFound` visible. Se elige el error recuperable.

**Procedencia**: `libphonenumber 9.0.39`, primera dependencia del proyecto fuera del stack base, pineada en `service/pom.xml` con el motivo comentado. El índice funcional se eliminó por JDBC y se verificó que ya no existe. `duplicatedCountryCodeIsNotSecondGuessed` se conservó **re-semantizado**: bajo E.164 el código duplicado es inválido y se rechaza, no se conserva verbatim.

### Paridad con CI: T3c estaba verde local y rojo en CI

El escritor de T3c **murió sin reporte** (el modo de fallo ya documentado del proyecto). La recuperación fue la reglamentaria: **inspeccionar antes de re-delegar**. Resultado de la inspección, medido y no inferido:

- El árbol **compila** y los archivos están todos, incluidos `PhoneMatching.java` y `PhoneMatchingTests.java`.
- La **ambigüedad quedó correctamente reubicada en la función pura** (`PhoneMatchingTests`: `sameSubscriberInLocalAndInternationalFormatsIsAmbiguous`, `identicalStoredNumbersAreAmbiguous`, más los controles), que es exactamente lo que se pidió al mover la garantía a la base.
- El **esquema está completo, no a medias**: `businesses_phone_shape` (CHECK) y `uq_businesses_phone` (UNIQUE) existen, el índice funcional viejo ya no está, y `demo-pizzeria` tiene **`phone = 5493511234567`** (valor verificado en la base).

  > **Corrección de un error de registro mío**: una versión anterior de este documento afirmaba que `demo-pizzeria` había quedado normalizado a `54911235123456`. **Ese número no es el que la base contiene**, y lo escribí sin verificarlo. Peor: el valor **anterior** a la normalización **no quedó registrado** — el escritor de T3c murió sin reporte, así que no hay before/after de fuente primaria, y lo único honesto es decir que el `before` **no se conoce**. Un registro de evidencia con un número inventado es peor que un registro incompleto, porque el inventado se lee como dato.
- Local: **22/22 verde**.

**Pero verde local no era la evidencia**, y este proyecto ya lo tenía aprendido: cuando el esquema se genera desde las entidades, local puede pasar y CI fallar. Reproducida la condición de CI con una base vacía y `SPRING_DATASOURCE_URL`:

```text
PhoneMatchingTests (función pura):   8/8 verde      ← no necesita la base
WhatsappPhoneResolverTests:          3 fallas
    nonCanonicalPhoneIsRejectedByTheDatabase
    blankPhoneIsRejectedByTheDatabase
    whitespacePhoneIsRejectedByTheDatabase
    duplicateCanonicalPhoneIsRejectedByTheDatabase   ← ESTE PASÓ
```

El corte es preciso:

| constraint | ¿reproducible desde el repo? |
| --- | --- |
| `UNIQUE` | **SÍ** — `@Column(unique = true)`, Hibernate la crea en una tabla nueva |
| `CHECK` | **NO** — no existe anotación JPA para un CHECK |

**Consecuencia**: la única forma de que el `CHECK` exista era un `ALTER TABLE` a mano, así que CI (y cualquier entorno nuevo) arrancaba **sin validación de forma**, y tres tests lo detectaban. T3c no era entregable: el pendiente 30 ("el esquema no es reproducible desde el repo") dejaba de ser un problema de performance para volverse uno de **corrección**.

**Nota sobre el modo de fallo**: si el escritor hubiera reportado, **tampoco lo habría encontrado** — corrió sólo contra la base local. Lo encontró aplicar la técnica de paridad con CI que el proyecto ya tenía documentada. Un informe perdido no era el problema; el problema era que verde local nunca fue la evidencia.

**Lección de diseño que sale de acá, y es más general que este slice**: una garantía que vive **sólo en la base de datos** no es versionable ni reproducible por el mecanismo que genera el esquema. Si la garantía tiene que existir, tiene que estar **expresable en el artefacto que sí se versiona** — la entidad, o una migración. Ponerla en la base y confiar en que alguien la aplique a mano es exactamente el tipo de garantía invisible que este proyecto lleva cinco veces encontrando.

**Arreglo aplicado**: el `CHECK` se declara en la entidad con la anotación **estándar de JPA 3.2** `jakarta.persistence.CheckConstraint` —`@Column(check = @CheckConstraint(name = "businesses_phone_shape", constraint = "phone IS NULL OR phone ~ '^[1-9][0-9]{7,14}$'"))`—, que `ddl-auto` emite al crear la tabla. El `ALTER TABLE` manual queda documentado para **bases ya existentes**, con la misma división que ya usa `slug`. La procedencia de la anotación se **verificó en vez de asumirse**: `org.hibernate.annotations.Check` existe pero está `@Deprecated(since="7")` en Hibernate 7.2.4 —leído del `.class` con `javap`— y la prueba final fue empírica: el constraint aparece **nombrado** en una base cuyo **único** origen de esquema es la entidad.

**Evidencia del arreglo**: RED reproducido literalmente sobre una base vacía (`Tests run: 17, Failures: 3`, con `duplicateCanonicalPhoneIsRejectedByTheDatabase` **pasando**, tal como predecía el diagnóstico) → GREEN `Tests run: 17, Failures: 0` sobre una base **recreada de cero**. Sin regresión sobre la base de desarrollo: `ddl-auto: update` **no** intenta agregar el constraint a una tabla existente, así que no hubo `ADD CONSTRAINT` duplicado.

### El hallazgo bloqueante de la región, y su cierre

La verificación encontró que **la misma regla estaba implementada en dos lugares**: `isSupportedRegion` normalizaba (trim + uppercase) y `canonicalizeToE164` pasaba la región **cruda** a libphonenumber. Consecuencia medida:

```text
region=AR  raw=[5491122334455]    -> +5491122334455
region=ar  raw=[5491122334455]    -> null     <- la forma EXACTA que manda Meta
region=ar  raw=[+5491122334455]   -> +5491122334455
region=ar  raw=[011 15 2233 4455] -> null
```

Con `WHATSAPP_DEFAULT_REGION=ar` —la variable que el propio código documenta— la aplicación **arrancaba**, y después **todo remitente canonicalizaba a `null`**: cada mensaje daba `NotFound`. Exactamente el agujero silencioso que T3b existe para cerrar.

**Cierre**: la normalización vive en **un solo punto**, `PhoneNumbers.normalizeRegion`, consumido por los dos llamadores. Se exigió explícitamente **no** parchear el sitio del parseo, porque eso habría dejado las dos implementaciones —el bug— en su lugar.

**Evidencia**: RED literal (`PhoneMatchingTests.lowercaseAndWhitespacePaddedDefaultRegionStillResolves:198` → `expected Resolved but was NotFound[]` con región `ar`) → GREEN 9/9. Sensibilidad probada por mutación en `/tmp`: revirtiendo el parseo a la región cruda **falla sólo el test nuevo**, con los otros 8 verdes, así que ninguna aserción previa estaba tapando el bug. La validación de arranque sigue rechazando `XX`, blanco y espacios, con los mismos mensajes. Un `grep` sobre todo el árbol confirma que no queda una segunda normalización de región (los otros `toLowerCase` son el search de productos y el hex del HMAC, ajenos).

**Tercer caso del mismo patrón en este slice**, y quinto en dos slices: *una regla implementada en dos lugares*. Los otros dos de este slice fueron la normalización Java↔SQL (eliminada al quedar un solo runtime) y el test de acuerdo que comparaba cada lado contra un dorado en vez de entre sí.

## Open gaps and decisions

1. **El payload de Meta está transcripto de memoria.** No se consultó la documentación oficial de Cloud API. Antes de conectar de verdad hay que contrastar: nombre y forma de `X-Hub-Signature-256`, ubicación exacta del texto en `entry[].changes[].value.messages[]`, campos obligatorios del handshake, y si Meta exige responder 200 en menos de un umbral de tiempo. Si algo difiere, lo que cambia es el fixture y el adapter, no el puerto `InboundMessage` — que es exactamente para lo que está el puerto.
2. **El simulador no prueba que el contrato sea el de Meta.** Prueba que nuestro código es consistente con nuestra transcripción del contrato. Son dos afirmaciones distintas y conviene no confundirlas al leer la evidencia.
3. **Unicidad de `Business.phone`: RESUELTA (2026-09-19).** El usuario decidió que **cada local tiene su propio número**, así que la política se hace cumplir con `CHECK` + `UNIQUE` **sobre el valor canónico** (T3c) — no sobre el string crudo, porque dos strings distintos del mismo abonado pasarían y la constraint daría una garantía falsa. Requiere normalizar las filas existentes (hoy una) y aplicar el SQL a mano, igual que se hizo con `slug`. **Obligación que queda**: cuando exista un alta de negocios, tiene que canonicalizar con la **misma** función de `PhoneNumbers`, o va a violar el CHECK con un valor legítimo.
4. **Productos con `stock = null` podrían faltar en el catálogo con el que normaliza.** Mismo pendiente registrado en `cross-tenant-stock.md`: la query del menú público pide `stock > 0` y en SQL `NULL > 0` no es verdadero, mientras `Product.java:52-54` documenta que `null` significa stock infinito. Si es cierto, el normalizador no va a poder proponer esos productos: propondría "no tengo eso" para algo que sí se vende. **Inferencia sin verificar**: se resuelve insertando un producto con `stock = null` y leyendo `GET /api/menu/{slug}`.
5. **`ComboItemDTO` no expone el `productId` de los componentes** (sólo `productName` y `quantity`, vía `IComboMapperImpl.java:32-33`). Un combo se puede proponer y confirmar por `comboId`, pero el normalizador no puede validar ni re-preciar los componentes desde el payload del menú.
6. **Sin expiración de propuestas** (Out #7). Una propuesta vieja queda pendiente para siempre y el encargado ve basura acumulada. Necesita una decisión de producto: ¿caduca?, ¿se archiva?, ¿cuánto tiempo?
7. **Sin realtime** (Out #4). El encargado tiene que refrescar para ver una propuesta nueva. Es aceptable para un primer slice, pero el valor real del KDS es el tiempo real, y la exposición anónima de `/ws/**` (Hecho 8) es lo que bloquea resolverlo bien. Ese es el próximo slice de seguridad evidente.
8. **Sin edición de líneas** (Out #6). Si el normalizador entiende 2 de 3 líneas, el humano confirma o descarta todo. Editar es la salida natural y probablemente el primer refinamiento pedido en uso real.
9. **Un mensaje recibido sin handler se descarta en silencio y responde 200.** El controller inyecta `ObjectProvider<IInboundMessageHandler>` y, si no hay ningún bean registrado, loguea y descarta. Es **la misma clase de defecto que aquel `wa.me/null` que perdía pedidos prometiendo confirmación**: éxito aparente con pérdida real. Hoy es correcto (T1 no tiene implementación de producción, a propósito), pero **T5 tiene la obligación de convertir la ausencia de handler en un fallo ruidoso**, no en un descarte silencioso. Un 200 con el mensaje tirado es peor que un 500.
10. **La identidad de bytes simulador ↔ fixture hoy no es un test, es una corrida manual.** Se verificó una vez (935 bytes, idénticos) y quedó como afirmación en el reporte del escritor. Es exactamente el problema ya conocido en este proyecto: **evidencia sin domicilio durable**. Si los dos payloads derivan, el simulador pasa a ejercitar un contrato que nadie chequea. Debería ser un test automatizado **antes** de que el simulador se use para el ensayo manual (AC13).
11. **Renombrar un componente de `MetaWebhookPayload` cambia el contrato de red en silencio** (Hecho 9). Sin anotaciones Jackson, el nombre de wire se deriva del nombre del componente, así que no hay nada que falle al compilar. Un test que fije los nombres de wire esperados contra el fixture lo haría explícito.
12. **El traductor toma sólo el primer mensaje de texto de una notificación.** Meta puede agrupar varios en un `value.messages[]`. Está documentado en el javadoc y es aceptable para el primer slice, pero es pérdida silenciosa de mensajes si alguna vez ocurre.
13. **El 403 del controller y el 403 de la filter chain coexisten** y son semánticamente distintos: uno significa "firma inválida", el otro "no autorizado, no llegaste al código". El test los distingue por el cuerpo, que es la única señal disponible. Vale la pena recordarlo cuando alguien depure un 403 en producción.
14. **`app-secret` y `verify-token` vacíos no fallan al arrancar** (lo encontró la verificación). Con el secreto vacío, un POST firmado devuelve **500** — la respuesta que este diseño declara prohibida, porque hace que Meta reintente para siempre. Con el token vacío, **cualquiera pasa el handshake**. No es alcanzable con los defaults documentados, pero es un agujero de validación de configuración: la aplicación debería **negarse a arrancar** con cualquiera de los dos en blanco. Se cierra en el acto.
15. **La regla `permitAll` es prefix-wide** (`/api/whatsapp/**`), lo que recrea la trampa espejo en miniatura: cualquier endpoint autenticado que se agregue bajo ese prefijo en T5/T7 (por ejemplo, un endpoint de administración) queda **anónimo sin que nadie lo note**. Se estrecha a la ruta exacta del webhook, manteniendo la propiedad de que la firma es la única autorización. Se cierra en el acto.
16. **Un handler que lanza también pierde el mensaje con 200** (extiende el pendiente 9). `handOff` atrapa `RuntimeException` por handler y responde 200 igual. Con la base de datos caída, eso significa: el mensaje se acepta, no se persiste, y **nadie se entera** salvo una línea de ERROR. Queda como obligación explícita de T5.
17. **Ningún test fija los nombres de wire del payload de Meta.** La mutación `phoneNumberId` → `phoneNumberIdentifier` deja **9/9 en verde** mientras el campo deja de bindear. Es la fragilidad del Hecho 9 materializada: sin anotaciones, renombrar un componente cambia el contrato de red sin que falle nada. Se agregan aserciones que fijan los nombres contra el fixture. Se cierra en el acto.
18. **El `timestamp` del proveedor se parsea pero no viaja en `InboundMessage`.** T5 va a persistir sólo el `receivedAt` del servidor, no el momento en que Meta recibió el mensaje. Es información que se pierde en la frontera del puerto: si el webhook se reintenta con retraso, el timestamp de Meta es el único dato del momento real del pedido. Decidir en T5 si se agrega al puerto.
19. **El contrato real de Meta sigue sin contrastar** (pendiente 1). La verificación **no pudo** consultarlo (sin acceso a la documentación de Cloud API). Convertir el contrato transcripto en un test que fije los nombres de wire es una red contra *nuestros* errores de tipeo, **no** contra un contrato mal transcripto: si el payload real difiere, el test va a estar consistentemente equivocado con el código. Un renamer que edite el payload, el accesor **y** el valor esperado también escapa; lo que sí atrapa el compilador es renombrar sólo el payload.
20. **La validación de configuración es global, no de WhatsApp** (lo encontró la re-verificación). `WhatsappProperties` es un `@Component` escaneado por `com.carrito`, así que **cualquier** contexto de la aplicación se niega a arrancar con credenciales de WhatsApp en blanco, incluso uno que nunca habilita el webhook. Es defendible (fallar rápido) y hoy no rompe nada, pero es acoplamiento real y merece una línea de decisión explícita antes de que alguien lo descubra en un entorno que no usa WhatsApp.
21. **CERRADO — el cierre de F1 ya no se apoya en una sonda desechable.** Los 4 tests originales llaman a `validate()` directo, así que **si se quita el `@PostConstruct` siguen verdes**: el mismo modo de fallo que ya nos costó caro dos veces (T2 en `cross-tenant-stock`, y la firma decorativa en T1), un test verde que no prueba lo que dice probar. Ahora hay un test **del repo** (`WhatsappPropertiesStartupValidationTests`) que afirma que **el contexto se niega a arrancar**, con la sensibilidad probada: con el `@PostConstruct` quitado en una copia, los dos casos en blanco fallan con `but context started successfully`. Los 4 tests directos se conservan (cubren el contrato del método).
    - **Un detalle que evitó un falso verde**: `withUserConfiguration(WhatsappProperties.class)` a secas registra el bean **sin** el post-procesador de binding, así que con los defaults no vacíos el test habría pasado vacuo. Se usa una configuración anidada con `@EnableConfigurationProperties`, que sí instala el binding antes del `@PostConstruct`. Y el caso válido no se conforma con `hasNotFailed()`: exige que el bean tenga los **valores de la propiedad**, no los defaults del campo, que es lo que prueba que el binding ocurrió de verdad.
    - **Límite declarado de este test**: el runner no hace component scan y el bean se registra explícito, así que pinnea el ciclo de vida **del bean**, no la compuerta global del `@Component` escaneado (pendiente 20). Si el bean se hiciera condicional, el binding respeta la condición y los tests en blanco fallarían ruidosamente en vez de pasar en silencio.
22. **Un cuerpo vacío bien firmado da 403 `invalid signature`, no 400 `invalid payload`.** El atajo `rawBody.length == 0` está antes del parseo. Preexistente, defendible (sin bytes no hay firma que verificar) y anotado sólo por completitud.
23. **La regla estrechada habilita todos los métodos HTTP sobre la ruta exacta.** Hoy sólo hay mappings `GET`/`POST`, así que el resto da 405 y no hay exposición. Scope por método sería marginalmente más ajustado; no vale el cambio.
24. **Los tests de node no son parte de `mvn verify`.** El reactor corre **59 tests, todos Java**: no hay plugin de exec ni de frontend. AC12 pide "`mvn verify` verde **más** los tests de node", así que la evidencia válida son dos comandos, no uno. Los 34 de node se verificaron aparte. Vale la pena saberlo antes de declarar AC12 cumplido leyendo sólo el reactor.
25. **RESUELTO (2026-09-19): cada local tiene su propio número.** No se admite que dos locales compartan WhatsApp. Consecuencia: la política se hace cumplir con CHECK + UNIQUE **sobre el valor canónico** (T3c), no sobre el string crudo — ver T3c para por qué la constraint obvia no servía. El camino ambiguo del resolver se conserva como defensa, testeado por función pura para que no quede intesteable al mover la garantía a la base.
26. **`libphonenumber` es una dependencia nueva** (aprobada por el usuario el 2026-09-19). Es la primera dependencia del proyecto que no viene del stack base, y entra porque E.164 no se puede expresar en SQL ni razonar a mano. Vale pin de versión y una nota de por qué está.
27. **La comparación en memoria no escala a miles de negocios.** Hoy es lo correcto (locales de una ciudad). El camino a escala es una columna canónica, que **necesita un camino de escritura de negocios que hoy no existe** — y cuando exista, ahí también vive la validación al guardar diferida (T3b).
28. **Lección de diseño de test, y el test defectuoso lo diseñé yo.** Pedí un "test de acuerdo" que alimentara a los dos runtimes con la misma tabla de entradas. Lo que se escribió compara **cada lado contra un valor dorado hardcodeado**, y el "lado SQL" se reconstruye con **la misma constante que compila Java**. La mutación lo probó: cambiar la **query real** para que conserve guiones deja el test de acuerdo **en verde (1/1)** mientras la suite de comportamiento falla 4. O sea: el test no cruzaba los dos lados, comparaba cada uno contra mi expectativa. **Regla**: un test de acuerdo tiene que comparar **un lado contra el otro**; si cada lado se compara contra un dorado, lo que se está testeando es la constante, no el acuerdo. Queda moot porque T3b elimina el segundo runtime, pero la lección no.
29. **`Ambiguous` sólo expone `List<Long>`.** Es lo correcto para que no se pueda colapsar a una elección, pero si T5 quiere mostrar los **nombres** de los negocios en conflicto en el mensaje de auditoría, tiene que releerlos. Decisión de T5, anotada para que no sorprenda.
30. **El índice funcional no es reproducible desde el repo** (lo encontró la verificación de T3): existe sólo en la base de desarrollo y en un javadoc. Sin Flyway, un entorno nuevo lo pierde en silencio. **ESCALADO**: con T3c esto dejó de ser performance y pasó a ser **corrección** — el `CHECK` de forma tampoco era reproducible, así que CI arrancaba sin validación y tres tests lo detectaron. El arreglo inmediato quedó hecho: el `CHECK` se expresa en la entidad con `jakarta.persistence.CheckConstraint`, que `ddl-auto` emite al crear la tabla. El arreglo de fondo sigue siendo **adoptar un mecanismo de migraciones** (Flyway o Liquibase), que además resuelve el `ALTER TABLE` manual de `slug`, la normalización de datos y todo backfill futuro. Es un slice propio y conviene hacerlo **antes** de la primera migración que importe.
31. **El mismo esquema tiene nombres de constraint distintos por entorno.** En la base de desarrollo el único de `phone` se llama `uq_businesses_phone` (aplicado a mano) y en una base creada desde las entidades sale `uk8140cl0n9nxy70j919keu3mmj`; lo mismo pasa con `uq_businesses_slug`. Las **definiciones son idénticas**, así que hoy no hay ningún efecto — pero una migración futura que haga `DROP CONSTRAINT uq_businesses_phone` fallaría en un entorno nuevo. Se arreglaría nombrando los constraints en la entidad (`@Table(uniqueConstraints = @UniqueConstraint(name = …))`). **No se hace ahora a propósito**: los nombres recién se vuelven carga cuando existan migraciones, y ese es el momento de fijarlos de forma autoritativa (tarea 19). Anotado para que no sorprenda al adoptarlas.
32. **CERRADO — la garantía de "no llega a la cocina" ya no depende de una ausencia.** La verificación la dictaminó **no adecuada**: el único `SimpMessagingTemplate` del repo estaba en `OrderController` y nada fallaba si alguien agregaba un broadcast al handler. Ahora hay test negativo (`@MockitoBean SimpMessagingTemplate` + `verifyNoInteractions` tras un POST de ingreso), con sensibilidad probada en `/tmp`: agregar un `convertAndSend` al handler lo hace fallar con `NoInteractionsWanted`. **Dato nuevo a favor, aportado por el escritor**: el módulo `service` **no tiene dependencia de `spring-messaging`**, así que el handler **no puede** broadcastear sin agregar una dependencia — la garantía estructural es más fuerte de lo que este pendiente suponía. Pero la capa `api` sí tiene acceso, así que el pin sigue siendo necesario. **De paso, una refutación que vale registrar**: el escritor se había negado a escribir este test argumentando que *"no hay canal por el que pueda salir"*; el verificador mostró que **no hace falta un canal, hace falta un espía**. *"No hay canal para probarlo"* no es lo mismo que *"no hay forma de probarlo".
33. **`receivedAt` usa `Instant.now()` del traductor, no el `timestamp` de Meta** (gap 18, sigue abierto). Si el webhook se reintenta con retraso, el único dato del momento real del pedido se pierde en la frontera del puerto. Decisión pendiente.
34. **Desviación deliberada del encargo, y es mejor que la instrucción.** El encargo pedía `message_id` **unique y not null**; quedó **nullable**. El motivo: con `NOT NULL` había que elegir entre **perder el mensaje** (rechazarlo) o **inventar una clave sintética colisionable**; con nullable, Postgres trata los `NULL` como distintos en el índice único, así que cada llegada sin id es su propia fila — **nunca se pierde y nunca se deduplica de más**. Se acepta la desviación: el escritor reportó el desvío en vez de obedecer. **Corrección posterior (gap 36)**: esta afirmación era cierta **sólo para id ausente**; con id **en blanco** pasaba lo contrario hasta que se arregló.
35. **El javadoc de `IInboundMessageHandler` decía lo contrario del contrato nuevo** —"handlers must not throw"— cuando ese slice invirtió la semántica a propósito. Un javadoc que miente sobre el contrato de errores es exactamente lo que hace que el próximo implementador se trague errores, que es el bug que se acababa de cerrar. Corregido por el padre. **Lección**: cuando un slice cambia el contrato de errores de una interfaz, **el javadoc de la interfaz es parte del cambio**, no un extra — y el escritor no podía tocarlo porque quedaba fuera de sus superficies.
36. **CERRADO — sexta aparición del patrón "una regla en dos lugares", y con la peor consecuencia: pérdida silenciosa de mensajes.** La decisión "¿hay id usable?" trataba un `message_id` en blanco como ausente, pero el valor se guardaba **verbatim**: con `"id": ""` se persistía `''`, que el índice único **sí** trata como valor. Medido: dos mensajes distintos con id en blanco → el segundo **se descartaba con 200**. La desviación de `message_id` nullable era correcta de espíritu y quedó **a medio implementar**: `NULL` era seguro, blank no. **Arreglo con la forma correcta, no un parche**: una sola función `usableMessageId(message)` cuyo resultado alimenta **la pre-consulta y la columna**, así que "¿hay id usable" y "qué se guarda" son el mismo valor del mismo lugar y no pueden discrepar. RED observado **en el repo** (`Expected size: 2 but was: 1`, para blank y para whitespace) → verde. Y se barrió el resto del camino de persistencia buscando la misma discrepancia: no había otra (el traductor pasa el id verbatim a propósito, el repositorio y la entidad no deciden nada).
37. **CERRADO — la garantía de idempotencia ya tiene domicilio durable.** El test de integración no podía ejercitar el camino del constraint: su `@Transactional` envenenaba la sesión en el insert duplicado (`AssertionFailure ... null identifier`), así que la autoridad del constraint estaba probada por un test unitario y **una sonda desechable en `/tmp`** del verificador. Ahora hay una clase deliberadamente **no transaccional** (`WhatsappIdempotencyRaceTests`) con su justificación escrita: el constraint sólo responde en el flush de un insert realmente comprometido, que un test aislado por rollback **nunca** puede ejercitar — que es exactamente por qué la evidencia vivía en `/tmp`. Aislamiento por borrados acotados a las filas de esa suite (`fromPhone` propio, negocio `987_900_002`), y **residuo verificado en cero** tras la corrida. **Cadena de mutantes que atribuye qué mecanismo responde**: con la pre-consulta forzada a fallar, el test **sigue verde** (responde el constraint); apilando ahí la atribución deshabilitada, el test cae con `expected:\<200\> but was:\<500\>` — o sea, era la atribución la que contestaba.
38. **Riesgo nuevo y acotado que introduce la suite no transaccional**: commitea filas reales en `carrito_db` durante su corrida. La limpieza está acotada y verificada (0 residuo, **probado en tres corridas consecutivas y con la clase sola**), pero **si una corrida muere a mitad, quedan filas hasta el próximo `@BeforeEach`**. Es el precio de ejercitar el constraint de verdad, y es el precio correcto — pero conviene saberlo. Cuantificado por el verificador: sin el `@AfterEach`, quedan **2 propuestas y 1 negocio**.
39. **CERRADO — la limpieza borraba filas ajenas.** Estaba acotada al teléfono `5491122334455`, un literal **compartido** con `WhatsappInboundPersistenceTests`: borraba por un valor que no identifica a la suite, así que **"mis filas" no eran sus filas**. El RED se reprodujo **contra el código viejo**: se sembró una fila ajena, la suite corrió **verde 3/3**, y `FOREIGN_PRESENT_BEFORE=1 → AFTER=0`. Es decir: **el código viejo borraba datos que no eran suyos, en silencio, con la suite en verde** — la mejor demostración posible de que verde no es evidencia. Arreglo: marcador propio de la suite (`5491176543210`, único en el repo, verificado por grep) usado por fixtures, aserciones y limpieza. Tras el arreglo, la misma fila ajena **sobrevive**, y hay **test de regresión en el repo** (`cleanupLeavesRowsThatDoNotBelongToThisSuite`) que mantiene el literal compartido viejo a propósito para que la propiedad quede pinneada.
40. **CERRADO — la bomba de tiempo que iba a romper en T6.** El `DELETE` masivo salteaba el cascade y `order_proposal_items` tiene FK a `order_proposals`; funcionaba por accidente porque el handler siempre persiste líneas vacías, y **T6 es la tarea que produce líneas**. RED literal contra la limpieza vieja: `update or delete on table "order_proposals" violates foreign key constraint "fkmyrhpfkpaxtwwoqb1rshx8tq6" on table "order_proposal_items"`, con **tres tests envenenados por el residuo** (`Errors: 3`). Arreglo: borrar primero las líneas (subselect sobre los ids acotados por el marcador) y después las propuestas, en **una transacción**, así la FK nunca ve un huérfano. **Nota técnica que zancha una alternativa**: el cascade a nivel de entidad **nunca** podría haber arreglado un `DELETE` masivo JPQL, porque los bulk delete **saltean el cascade por especificación de JPA**. Trade-off documentado en el javadoc: si `OrderProposal` gana otra tabla hija, esta limpieza hay que extenderla en el mismo paso — pero un olvido **se anuncia como error de FK ruidoso, nunca como residuo silencioso**. Test en el repo que persiste una propuesta **con líneas** y prueba que la limpieza se lleva las dos, con aserciones de 1 propuesta + 2 líneas **antes** y cero/cero después (no puede pasar vacuo).
41. **Corrección de un error mío en el encargo de F-2.** Escribí que la suite de idempotencia "crea propuestas **sin negocio** (los casos de id en blanco/espacios/ausente)". **El código dice lo contrario**: esos tests **sí** siembran el negocio, así que ninguno ejercita el camino sin negocio. La cobertura existe, pero **en otra suite**: `unknownPhonePersistsFailedProposalWithNullBusinessAndAnswers200` y `ambiguousPhonePersistsFailedProposal...` viven en `WhatsappInboundPersistenceTests`. O sea: no hay hueco de cobertura, mi descripción de **qué suite cubre qué** estaba mal. El arreglo del marcador cubre las filas sin negocio de todos modos, por construcción. Lo anoto porque en este proyecto ya van tres veces que una afirmación mía sobre el código resulta falsa y la corrige una verificación — y siempre en dominios donde **asumí en vez de medir**.
