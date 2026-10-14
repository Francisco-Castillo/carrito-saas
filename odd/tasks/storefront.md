# Portada del local (etapa 2)

**Estado:** backend, datos y carta listos; falta la pantalla del admin
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
- [ ] 3. Admin: vista "Local" para editar el perfil y los horarios, con tests.
- [x] 4. Datos y assets del negocio demo: logo + banner (reusar el banner generado) y seed actualizado.
- [ ] 5. Verificación: suites + e2e en navegador de la portada (desktop y mobile) y del guardado desde el admin.

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