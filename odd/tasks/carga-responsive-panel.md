# Carga + responsive móvil del panel (admin + pedidos)

Llevar el sistema de skeletons del dashboard al resto del panel y hacer una
mejora real de UX responsive en móviles, para Admin y Pedidos (el KDS queda
para una ronda 2).

## Specs

- S1: "añadir el efecto de load que tiene los componentes de dashboard en el
  resto de las secciones del panel" — las secciones del admin (tablas de
  Productos/Categorías/Combos, pestañas de Local, QR/Mesas) muestran skeletons
  mientras cargan, con el mismo shimmer visual del dashboard.
- S2: "hacer mejorar de verdad el diseño/ux responsive en moviles" — el panel
  admin y la consola de pedidos se usan bien en un celular: navegación táctil,
  modales utilizables, sin scroll horizontal innecesario, targets de toque
  correctos.
- S3: Alcance elegido por el dueño: "Admin + Pedidos (recomendado)". El KDS
  (cola de cocina, fullscreen) queda FUERA — ronda 2.

## Tasks

- T1 (S1): Skeleton compartido — ✅ commit d4849ef: `.skeleton*` movidos a
  `skeleton.css` compartido, importado por Dashboard/Admin/Orders.
- T2 (S1): Skeletons en tablas del admin — ✅ commit 542d9bc: `LoadingPanel`
  compartido (role=status + aria-label) en ProductsTable, CategoriesTable y
  CombosTable; spec con GET pendiente.
- T3 (S1): Skeletons en Local — ✅ commit b43ddec: LocalSection y
  QrTablesSection con LoadingPanel en vez de `return null`.
- T4 (S2): Responsive admin — ✅ commit f3444ae: modales `.toast-modal` casi
  de pantalla completa en <560px (dvh + scroll interno — antes los formularios
  largos desbordaban sin poder guardar/cerrar) y tabs de Local scrollables
  táctiles (44px targets).
- T5 (S2): Responsive pedidos — ✅ commit f9825b4: modales del POS/detalle
  dvh-aware, close de 44px, `.modal-content` de cancelación con scroll
  (compartido con el KDS, solo <559px).
- T6 (S1,S2): Verificación — ✅ vitest 50 archivos / 369 tests, build OK,
  Playwright e2e 30/30 (mobile Pixel 7 / tablet / desktop), css-scoping OK.
  Residuo: tests/debug-loading.test.tsx (borrado, nunca se commiteó);
  frontend/test-results/ agregado a .gitignore.

## Log

- L1: "Bueno arranquemos por añadir el efecto de load que tiene los
  componentes de dashboar en el resto de las secciones del panel, y hacer
  mejorar de verdad el diseño/ux responsive en moviles"
- L2: Alcance elegido en la pregunta de alcance: "Admin + Pedidos
  (recomendado)" — KDS fuera (ronda 2).
- L3: Rama de feature `feat/carga-responsive-panel` desde develop (las otras
  sesiones siguen empujando develop; merge a develop al cierre, deploy vía
  main como siempre).
