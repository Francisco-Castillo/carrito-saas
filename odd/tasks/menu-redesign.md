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
