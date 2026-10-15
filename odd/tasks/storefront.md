# Portada del local (etapa 2)

**Estado:** cerrado (2026-10-14)
**Fecha inicio:** 2026-10-14
**Objetivo:** Que la carta digital tenga una portada de local como la referencia QueResto: logo, banner, estado **abierto/cerrado** calculado, ubicación con mapa, WhatsApp y redes.

## Decisiones del usuario

1. **Logo y banner por URL** (el dueño pega el link): cero infraestructura de archivos y cero superficie de subida.
2. **Horarios estructurados por día** (una fila por día: abre, cierra, cerrado), para poder calcular "Abierto ahora / Cerrado" automáticamente. El texto libre no lo permite.
3. **Ubicación**: dirección en texto y el link de Maps se arma solo con la búsqueda de esa dirección.

## Estado del modelo (verificado)

- `businesses`: sólo `id, name, slug, phone`. El WhatsApp del menú sale del teléfono.
- `business_settings`: exclusivamente configuración del PDF de QR (`default_template`, `show_logo`, `show_url`), uno por negocio.
- `GET /api/menu/{slug}` devuelve `business: { id, name, slug, whatsappNumber }` — nada de portada.
- El admin tiene vistas dashboard / productos / categorías / combos.

## Diseño acordado

- **Negocio** (`businesses`): `logo_url`, `banner_url`, `address`, `instagram_url` — todos nullable (un negocio sin portada sigue funcionando).
- **Horarios**: nueva entidad `business_hours` (business_id, day_of_week 1..7, opens_at, closes_at, closed) con unique `(business_id, day_of_week)`.
- **Público**: el payload de `GET /api/menu/{slug}` incluye esos campos + el arreglo de horarios, para que la carta no necesite otra llamada.
- **Dueño**: `GET`/`PUT /api/business/profile` autenticado y acotado al negocio del JWT (mismo criterio que el resto: negocio del token, nunca del request).
- **Estado abierto/cerrado**: lo calcula la carta a partir de los horarios en la hora local del cliente (misma ciudad que el local); no se persiste un estado.
- **Admin**: nueva vista "Local" con los campos y un editor de 7 días.
- **Validaciones**: URLs http(s) absolutas o rutas relativas; `opens_at`/`closes_at` obligatorios si el día no está cerrado y `opens_at < closes_at`; `day_of_week` entre 1 y 7 sin repetidos.

## Tareas

- [x] 1. Backend: campos del negocio + entidad/repositorio de horarios + DTOs + payload público + `GET/PUT /api/business/profile` con tests (incluido el aislamiento por negocio).
- [x] 2. Portada en la carta: banner, logo, nombre, estado abierto/cerrado, ubicación con link a Maps, WhatsApp y redes, con tests del cálculo de estado.
- [x] 3. Admin: vista "Local" para editar el perfil y los horarios, con tests.
- [x] 4. Datos y assets del negocio demo: logo + banner (reusar el banner generado) y seed actualizado.
- [x] 5. Verificación: suites + e2e en navegador de la portada (desktop y mobile) y del guardado desde el admin.

## Evidencia / commits

- **Tarea 1 — commit `9051241`** *feat(api): give the business a storefront profile and opening hours*: `businesses` con `logo_url`/`banner_url`/`address`/`instagram_url` (nullable) y la tabla `business_hours` (una fila por día, unique `(business_id, day_of_week)`); `BusinessDTO` del menú público con los campos + `hours` ordenado lunes→domingo (lista vacía si no hay); `GET`/`PUT /api/business/profile` autenticados y anclados al negocio del JWT. `BusinessProfileAndStorefrontTests` (11 tests) con RED observado, incluidos aislamiento entre negocios y validaciones. Suite **114/114** (verificada por el orquestador).
- **Assets y datos — commits `576d5f4` y el seed**: logo circular y banner 16:6 generados en `dev-media/`, y `docs/dev-seed.sql` carga el perfil del negocio demo con horarios realistas.

### Hallazgo que apareció probando en vivo (no en los tests)

La primera validación exigía `opensAt < closesAt`, así que **un local que cierra pasada la medianoche no podía guardar sus horarios**: probar un viernes `11:00 → 00:30` devolvió 400. Todos los tests del worker usaban horarios que no cruzan el día, por eso el hueco no apareció en la suite. Se corrigió: `closesAt < opensAt` significa **cierre en la madrugada del día siguiente** y sólo se rechaza la igualdad (ambigua). Tests nuevos con el caso de madrugada y el de igualdad, RED observado. **La lógica de "abierto ahora" de la carta debe cruzar la medianoche con esta convención.**

### Casos de horarios que el modelo todavía no representa (anotados, no resueltos)

- **Dos franjas por día** (mediodía y noche, típico en locales de almuerzo): imposible con una fila por día y la unique actual; requiere lista de rangos por día.
- **Horario de verano/invierno o feriados**: el modelo no tiene vigencia temporal.
- **Día de 24 h / cierre exacto a medianoche**: `00:00 → 00:00` se rechaza por ambigüedad; hoy habría que cargar `23:59`.

## Evidencia de la portada (2026-10-14)

- **Commit `31e4324`** *feat(frontend): add the storefront to the public menu*: `openStatus.ts` (lógica pura de abierto/cerrado), `Storefront.tsx` (banner, logo, nombre, badge, línea de hoy, WhatsApp, Instagram y dirección con link a Maps armado desde el texto), estilos y tipos. Tests: `storefront-open-status.test.ts` (15) + `storefront.test.tsx` (15); frontend **81/81**.
- **Verificación en navegador** (`~/.local/opt/e2e/storefront-check.mjs`), **7/7 PASS**, con el reloj simulado de Playwright para los casos difíciles:
  - banner, logo circular, nombre y contacto (WhatsApp, Instagram y Maps con la dirección codificada) visibles en desktop y en 390px;
  - miércoles con horario 11:00-23:00 → **"Abierto"** + "Cierra a las 23:00" + "Hoy: 11:00–23:00";
  - **viernes 23:30** → abierto y avisa **"Cierra mañana a las 00:30"** (cierre que cruza la medianoche);
  - **sábado 00:15** → sigue abierto por el rango del viernes (la cola de madrugada pertenece al día anterior);
  - **sábado 10:00** → "Cerrado" con **"Abre hoy a las 12:00"**.
- Capturas: `/tmp/e2e-menu/17-portada-desktop.png` y `18-portada-mobile.png`.

### Decisión de copy

El badge dice **"Abierto"** (no "Abierto ahora") con la hora de cierre al lado y la línea de hoy debajo: en una pantalla chica el texto largo compite con el banner, y la información útil es *cuándo cierra*, no el adverbio.

### Pendiente menor anotado

El estado se calcula al renderizar: si la carta queda abierta cruzando la hora de cierre, el badge no se actualiza solo. No se pidió; si molesta, se resuelve con un re-render cada minuto (o al recuperar foco la pestaña).
## Ajuste del banner (pedido del usuario)

El usuario marcó que el banner quedaba muy grande. Medido: con `aspect-ratio: 16/6` medía **~576px** en una pantalla de 1600 (más de media pantalla antes del primer producto) y en teléfono el 4:3 daba **268px**, que sumado a la tarjeta y los chips empujaba la carta casi fuera de la primera pantalla.

Commit `c2d2310`: el banner pasa a altura acotada (`clamp(170px, 17vw, 260px)` en escritorio; `clamp(140px, 36vw, 190px)` en teléfono) con `object-fit: cover`, y el fundido inferior se ajustó (62%→100%) porque el gradiente anterior lavaba la foto en un banner corto. Resultado medido: banner de **576 → 260px** y de **268 → 140px**, y **el primer producto pasó a aparecer al 62% de la pantalla** (antes quedaba bajo el pliegue). Re-verificado el e2e de portada: 7/7.

## La barra sticky queda sólo con las píldoras (pedido del usuario)

El usuario marcó que la barra era redundante: repetía el eyebrow y el nombre del local que la portada ya muestra unos centímetros más arriba, y lo pagaba en altura (118px de barra mientras se scrollea). Commit `fix(frontend): keep only the category pills in the sticky bar`: la barra lleva **sólo las píldoras**, medida en **61px** en desktop y teléfono (el menú gana 57px de pantalla). El `scroll-margin-top` de las secciones bajó de los 140px estimados a los **80px medidos**, y las reglas CSS del header eliminado se fueron con él. Tests 81/81 y e2e 8/8 (menú), 7/7 (portada) y 5/5 (pantallas).

## La composición: cada cosa en su bloque (pedido del usuario, commit `27355c2`)

El usuario marcó que los chips de contacto y la barra de filtros "quedaban ahí porque sí, sin relación a lo demás". Medido antes del cambio: los chips **no** estaban dentro de la tarjeta, no existía un bloque de menú, y la barra de píldoras **siempre** tenía la franja de vidrio (parecía una barra de app ajena).

Ahora:
- **Los chips de contacto viven dentro de la tarjeta del local** (tercera columna en escritorio, fila envuelta en teléfono): logo + nombre + estado + horarios + WhatsApp/Instagram/dirección se leen como un solo encabezado. En escritorio la tarjeta sigue midiendo **108px** (las píldoras entran en su fila); en 390px crece una fila (**220px**).
- **Las píldoras son parte del menú**: van bajo un título real **"Menú"** que abre la carta, y la franja de vidrio aparece **sólo mientras la barra está pegada**. Se detecta con un centinela de 1px antes de la barra + `IntersectionObserver` (patrón que ya usaba el scroll-spy), sin trucos de scroll; en reposo las píldoras son simplemente parte del bloque de menú.
- El `scroll-margin-top` de 80px medido se mantiene, así que navegar con una píldora nunca tapa el título.

Verificación en el navegador (medida, no mirada): `contactoDentroDeLaTarjeta: true`, `bloqueMenu: true`, barra transparente en reposo y con `blur(24px) saturate(1.6)` + opacidad 1 al pegarse. Capturas: `/tmp/e2e-menu/21-antes-*` vs `21-despues-*` (composición) y `22-despues-*-pegado.png` (estado pegado). E2E: menú **8/8** (3 corridas), portada/medianoche **7/7**, 5 pantallas **5/5**, admin **6/6**; frontend **84/84**.

### Nota de verificación

El e2e del menú falló de forma intermitente al agregar un producto: el click resolvía el botón pero no lo alcanzaba, porque mi script scrolleaba a una posición arbitraria y **la barra sticky tapaba el botón**. Centrar el objetivo antes de clickear (`scrollIntoView({block:'center'})`) lo resolvió: 3 corridas limpias. No era un problema del producto, pero vale como límite conocido: con la barra pegada, un elemento que quede justo debajo de ella puede requerir un scroll mínimo.

### Ajuste de un test (ambigüedad resuelta)

`storefront.test.tsx` afirmaba `sticky.previousElementSibling === .storefront`, que con el nuevo orden (portada → bloque de menú → barra) ya no puede cumplirse. Se adaptó a la intención real: la portada es el hermano anterior **del bloque de menú** que contiene la barra.

## Pantalla "Local" del admin y cierre (commit `3c9a29b`)

- **Vista nueva "Local"** en el admin: nombre, URL de logo y banner **con previsualización** (el dueño pega URLs: ver la imagen es su única confirmación), dirección, Instagram y **editor de los 7 días** donde un día cerrado deshabilita y limpia sus horarios. Carga desde `GET /api/business/profile` y guarda con `PUT`, convirtiendo los tiempos del `<input type="time">` (que habla `HH:mm`) a lo que espera el backend, y mostrando **el mensaje de validación del backend tal cual** si rechaza. La aclaración sobre la medianoche es deliberada: "cierra después de medianoche" no se adivina desde un formulario.
- Tests: `admin-business-profile.test.tsx` (6) con RED observado (la vista no existía). Frontend **90/90**.

### Verificación de cierre: el ida y vuelta completo

`~/.local/opt/e2e/profile-roundtrip.mjs`, **4/4 PASS** contra el backend real:
1. el admin carga el perfil sembrado (dirección, los 7 días, domingo cerrado);
2. guardar desde el panel muestra «Perfil actualizado»;
3. **la carta pública refleja el cambio** de inmediato, en el texto de la dirección y en el link a Maps;
4. restaurar el valor deja el demo como lo dejó el seed (verificado en el payload público).

Capturas: `/tmp/e2e-menu/23-admin-local.png` (el panel) y `24-carta-con-cambio.png` (la carta mostrando lo editado).

## Pendientes que quedan anotados

- **Dos franjas por día** y **horario de verano/feriados**: el modelo no los representa (ver arriba).
- El estado abierto/cerrado se calcula al renderizar: si la carta queda abierta cruzando el cierre, el badge no se actualiza solo.
- Los días se envían siempre los siete (el contrato borra un día omitido): hoy no hay forma de "quitar" un día, sólo marcarlo cerrado.
- El PUT es de reemplazo total: una pantalla que quiera ediciones parciales necesita otro contrato.