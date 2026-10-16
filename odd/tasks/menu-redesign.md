# Rediseño del menú público (foto-first, liquid glass)

**Estado:** implementado, pendiente de aprobación visual del usuario
**Fecha inicio:** 2026-10-14
**Objetivo:** Llevar la carta digital a un nivel muy superior a la referencia QueResto, manteniendo la identidad liquid glass de la app y un diseño **foto-first**.

## Decisiones del usuario

1. **Alcance por etapas**: primero el **menú**; la **portada del local** (logo, banner, horarios con estado, dirección, redes) queda para una etapa siguiente porque requiere datos nuevos en el negocio (backend + pantalla en el admin).
2. **Dirección visual**: seguir con **liquid glass** (vidrio oscuro, aurora, acento índigo→violeta). El menú público mantiene la coherencia con el panel; no se va a un look claro.
3. **Imágenes**: **fotos de prueba** para el negocio semilla y diseño **foto-first** (si un producto no tiene imagen, cae a un placeholder intencional, no a un hueco).

## Referencia (QueResto) — qué sí y qué no

La página de `queresto.com/Guling` renderiza **vacía**; el lenguaje se extrae de otros locales (`/querestovzla`, `/menequita`, `/Drestaurant`):

- **Sí tomamos**: categorías como navegación principal, plato con nombre + descripción + **precio destacado**, badges (descuento/`desde`), jerarquía tipográfica fuerte, foco en el producto.
- **No tomamos** (en esta etapa): portada del local, horarios, ubicación, redes, footer de sucursales.
- **Lo superamos**: vidrio con profundidad real, fotos grandes, micro-interacciones, transición de categoría con scroll-spy, hoja de detalle del plato, carrito flotante con contador, skeletons de carga y estados vacíos cuidados.

## Contrato funcional que NO cambia

- Flujo de pedido idéntico: nombre, tipo (RETIRO/DELIVERY), dirección sólo en delivery, forma de pago, notas, submit a `POST /api/orders/menu/{slug}`, fallback de WhatsApp, y el **contrato de fallo** (sin slug o con menú/restaurante caído: mensaje legible y STOP, sin panel de pedido).
- `localStorage` con la misma clave `cart`.
- Los tests de contrato existentes (`frontend/tests/menu-contract.test.tsx`, `menu-categories.test.tsx`) deben seguir cubriendo lo mismo, adaptados a la nueva estructura.

## Bugs de datos a arreglar en esta rama

- **Combo duplicado**: `combo_products` tiene PK en `id` y **no** unique en `(combo_id, producto_id)`, así que el `ON CONFLICT DO NOTHING` del seed no deduplicaba y cada re-aplicación agregaba filas (hoy 6+6 en vez de 1+1). Hay que limpiar los duplicados y hacer el seed realmente idempotente para esa tabla.
- **Modelo**: el mismo producto puede repetirse dentro de un combo. Decidir si se agrega la restricción única (entidad) y deduplicación en el servicio.

## Tareas

- [x] 1. Limpiar duplicados de `combo_products` y hacer el seed idempotente en esa tabla.
- [x] 2. Generar fotos de prueba para los productos del negocio semilla y servirlas desde la app (sin depender de un CDN externo).
- [x] 3. Rediseñar la pantalla de menú: hero del local, navegación de categorías sticky con scroll-spy, grilla de tarjetas foto-first, hoja de detalle del plato, carrito flotante y panel de pedido restilizado.
- [x] 4. Estados: carga (skeletons), vacío, sin imagen, error del contrato de fallo.
- [x] 5. Tests: adaptar los de contrato y agregar los del comportamiento nuevo (scroll-spy, detalle, contador del carrito).
- [x] 6. Verificación: build, tests, e2e con capturas desktop y mobile, y revisión visual del usuario.

## Evidencia / commits

- (se completa al avanzar cada tarea)

## Evidencia (2026-10-14)

- Commits: `aae579f` (seed idempotente + fotos de demo + permiso `/dev-media/**`), `9532b58` (rediseño del menú), `+` fix de la foto del combo. Los tres work units con su verificación.
- **Carrera real encontrada al verificar** (y no aceptada como "flake"): el scroll-spy creaba el `IntersectionObserver` dentro de un `useEffect` que leía los refs de las secciones en ese instante. Si el efecto corría en un commit anterior al montaje de las secciones, el observer quedaba observando nada y **no se recreaba** (sus deps no volvían a cambiar): las píldoras dejaban de seguir el scroll sin ningún error visible. Se manifestaba como 1 fallo cada ~6 corridas de la suite. Arreglado creando el observer una sola vez y observando cada sección desde su **ref callback**; 10 corridas consecutivas verdes después del fix (y 5 más tras la mejora del combo).
- **Tests**: 38/38 (9 archivos) con el contrato de pedido intacto (payload, validación, WhatsApp, fallback). Nuevos: `menu-redesign.test.tsx` (8) para píldoras/scroll-spy, hoja de detalle, barra y hoja del carrito, placeholders.
- **Verificación en navegador** (`~/.local/opt/e2e/menu-design.mjs`), 8/8 PASS: 7 tarjetas con 7 fotos reales, píldora activa que sigue la sección al scrollear, hoja de detalle que agrega, sidebar de carrito con total, **pedido real enviado y confirmado**, y en mobile (390×844) la barra flotante, la hoja del carrito y el detalle. Capturas en `/tmp/e2e-menu/`.
- **Mejora posterior al ver**: la foto del combo salía del primer componente, así que "Combo Clasica + Bebida" mostraba una gaseosa suelta. Ahora usa el componente más caro con imagen (el plato principal), con test que lo pincha.

## El bug de fondo: el vidrio no existía en ningún navegador (2026-10-14)

Al revisar el pedido del usuario ("agregar blur a las píldoras y el header para que no se mezclen con el fondo") medí los estilos computados con Playwright en vez de suponer, y apareció algo mucho más grande que el menú:

```
.topbar  backdropFilter: "none"
.nav-pill backdropFilter: "none"
--glass-blur → "blur(24px) saturate(160%)"   (la variable resolvía bien)
elemento sintético con backdrop-filter → "blur(8px)"  (el navegador lo soporta)
```

Causa: **el minificador de CSS conserva la última de dos declaraciones equivalentes**. En todo el proyecto cada regla declaraba `backdrop-filter` y después `-webkit-backdrop-filter`, así que el bundle salía **sólo con la prefijada**, que Chrome y Firefox ignoran. Es decir: **el efecto vidrio nunca se aplicó**, en el menú, el login, el dashboard, el admin y el KDS; nada fallaba y nada lo avisaba.

- Arreglo (`a97dd22`): la declaración prefijada pasa a ir **primero** y la estándar última, en los 10 pares de las 5 hojas (Safari viejo sigue encontrando su prefijo). Verificado en el navegador: `blur(24px) saturate(1.6)` computado en los 5 pantallas (login 2/2 elementos, dashboard 7/7, admin 7/7, kds 5/5).
- **Guard en el build**: el paso post-build compara declaraciones con y sin prefijo en el CSS emitido y rompe el build si hay menos sin prefijo que con prefijo (`glass guard ok: 12 sin prefijo, 12 prefijadas`). Probado por mutación: reintroducir el orden viejo en **una sola** regla hace fallar el build nombrándola.

## La barra unificada (`3e56fda`)

Con el blur restaurado quedaba el problema estructural del pedido original: header y píldoras eran **dos** elementos sticky con una ranura de 13px entre ellos, y el texto y los precios de los productos pasaban por esa ranura y por los huecos entre píldoras (visible en `review-desktop-antes.png`). Ahora son **una sola barra** pegada al borde superior, sangrada a los bordes del contenedor, con fondo, blur, línea y sombra propios: no queda ranura. El `scroll-margin-top` de las secciones se corrigió al alto medido de la barra (118px en todos los breakpoints) en lugar de valores estimados.

Capturas comparativas: `/tmp/e2e-menu/review-desktop-{antes,final}.png` y `review-mobile-{antes,final}.png`; pantallas completas en `/tmp/e2e-menu/` y `/tmp/e2e-glass/`.

## Segunda ronda de feedback del usuario (2026-10-14)

Tres pedidos concretos, cada uno con su commit y su medición:

1. **El carrito no debe abrirse al costado ni cambiar el tamaño de las tarjetas** (`bada328`). Al agregar un producto se montaba un sidebar sticky que pasaba la grilla a dos columnas y encogía cada tarjeta. Ahora agregar sólo actualiza la **barra flotante** (la misma en mobile y desktop, centrada, tope 560px) y abrir el pedido es una acción explícita que muestra un **panel superpuesto** desde el borde derecho (≥1024px) o la hoja inferior en mobile. Medido: tarjetas de 372px idénticas antes y después de agregar, grilla de 4 columnas intacta, panel de 440px a la derecha con el menú intacto detrás. Se eliminaron el sidebar, la regla de dos columnas, el `display: none` que ocultaba la barra en desktop y el hook `useMediaQuery` (quedó sin uso).
2. **Que el menú ocupe la pantalla y no quede apretado en el medio** (`881e341`). La causa era doble: el contenedor tenía `max-width: 1280px` **y** una regla pelada `main { max-width: 1100px }` de `Dashboard.css` que se filtraba (ver abajo). Ahora el contenedor llega a 1720px con gutter fluido y la grilla pasa a **4 columnas desde 1440px**: en un viewport de 1600px las tarjetas pasaron de **263px a 372px**.
3. **Halo cuadrado en las píldoras** (`881e341`). El usuario mandó una captura; la amplié 3× y confirmé el artefacto: la píldora tenía `backdrop-filter` y Chromium desenfoca el fondo en el **rectángulo** del border-box sin respetar el `border-radius`, dejando un halo cuadrado alrededor de la cápsula. Como las píldoras ya viven sobre la barra desenfocada, se quitó la propiedad y se subió un poco la opacidad del fondo.

### Fuga de CSS entre pantallas (encontrada midiendo)

El `max-width: 1100px` no estaba en `Menu.css`: venía de `Dashboard.css`, que declaraba selectores **pelados** (`main { … }`, `main section { … }`) que aplican a *cualquier* `<main>`/`<section>` del bundle compartido — el mismo tipo de fuga que ya había marcado el primer worker con `.topbar` y `.restaurant-name`. Arreglado scopando ambas reglas a `.dashboard-screen` (`fa66405`) y **blindado con un test nuevo**: `frontend/tests/css-scoping.test.ts` falla si alguna hoja de página declara un selector de elemento sin scope, nombrando archivo y selector. Verificado por mutación: agregar `section { … }` a `Menu.css` lo hace fallar con “Menu.css → section”.

### Calidad de la verificación (no del producto)

El script de e2e empezó a fallar de forma intermitente (2 de 4 corridas) en pasos distintos: el scroll-spy y los clicks del carrito. No era el producto: eran **sleeps fijos** contra animaciones y scroll suave asíncronos. Se reemplazaron por *polling* (`waitFor`/`clickReady`) y quedó **5 corridas seguidas limpias**. Vale registrarlo porque es el mismo patrón que ya nos mordió dos veces: cuando algo falla "a veces", hay que medirlo, no anotarlo como flaky.

### Nota de higiene

El borrado de `frontend/src/pages/menu/useMediaQuery.ts` (que quedó sin uso con el cambio del carrito) quedó incluido en `fa66405`, el commit de la fuga de CSS, en vez de en el del carrito: se había hecho `git rm` antes y el primer `git add` de la serie lo arrastró. No se reescribió la historia por un archivo; el estado del código es el correcto.

## Formato de plata (posterior)

Los precios de la carta se muestran con `formatARS` (`$ 3.200,00`), el mismo formato que el panel y el dashboard, en **las cinco superficies**: tarjeta, hoja de detalle, línea del carrito (multiplica por cantidad), total del carrito y barra pegada de abajo — más el total del mensaje de WhatsApp, que es el número que el local lee en el teléfono. El formateador vive en `frontend/src/format.ts`, mudado desde `pages/dashboard/format` (la carta no debería depender de la carpeta del dashboard para mostrar un precio; las tablas del panel ya lo importaban). Guardas: `frontend/tests/menu-prices.test.tsx` y `~/.local/opt/e2e/menu-prices.mjs`.
