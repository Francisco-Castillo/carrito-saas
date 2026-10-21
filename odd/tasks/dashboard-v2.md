# Dashboard v2 — reordenar métricas y mejorar UX

## Specs
- S1: Agregar gráfico de "Ventas últimos 7 días" (endpoint backend nuevo `/analytics/sales-last-7-days`, datos día por día con revenue).
- S2: Quitar la sección "Pedidos activos" (OrderStatusCards) del dashboard y de la sección embebida del admin.
- S3: Auto-refresh periódico de los datos del dashboard sin recargar la página.
- S4: Estados de carga: skeletons mientras cargan las métricas.
- S5: Manejo de errores visible: mensajes claros si la API falla en vez de dejar 0 en silencio.
- S6: Pulir estilo general y responsive del dashboard.
- S7: Tendencias de productos: productos que más suben y más bajan usando /api/trends/top-up y /api/trends/top-down (ProductTrendDTO: productId, productName, trendPercentage, trendDirection).
- S8: Comparación vs. semana anterior en el gráfico semanal: línea de los 7 días previos + % de variación (endpoint backend nuevo).
- S9: Aviso de pedidos en vivo: badge en la topbar con pedidos activos + sonido al entrar un pedido vía WebSocket /topic/orders/{slug} (patrón useOrdersFeed).

## Tasks
- T1 (S1) Backend: query, service, DTO, controller para ventas de los últimos 7 días. ✅ commit: 75a8e1b feat(analytics)
- T2 (S1) Backend: test HTTP del endpoint nuevo (ruta autenticada, 200 con datos/empty). ✅ commit: 2351d31 test(analytics) — 2/2 verde
- T3 (S2) Frontend: eliminar OrderStatusCards (Dashboard.tsx, DashboardSection.tsx, useDashboardData, CSS). ✅ commit: 9f2b035 refactor(dashboard)
- T4 (S1) Frontend: componente SalesByDayChart + api.ts + integración en Dashboard y DashboardSection. ✅ commit: d083cd4 feat(dashboard)
- T5 (S3) Auto-refresh: refetch periódico (60s, salta con pestaña oculta) en useDashboardData. ✅ commit: fbf5237 feat(dashboard)
- T6 (S4,S5) Estados de carga y error visibles en las secciones (skeletons, error por sección, empty honesto). ✅ commit: 10d50b5 feat(dashboard)
- T7 (S6) Pulido CSS + responsive (alturas de chart en móvil, padding denso). ✅ commit: e2f4669 style(dashboard). Verificación: build ✓, lint sin errores nuevos, suite backend 413 tests 0 fallos.
- T8 (S8) Backend: endpoint ventas de la semana anterior + test. ✅ commit: 86a68d1 feat(analytics) — 3/3 verde (el test atrapó una ventana de 8 días, corregida a 7)
- T9 (S8) Frontend: dataset comparativo en SalesByDayChart + % variación. ✅ commit: 7bde13d feat(dashboard)
- T10 (S7) Frontend: componente ProductTrends (suben/bajan) + api + integración. ✅ commit: afb777d feat(dashboard)
- T11 (S9) Frontend: indicador en vivo (WS + badge activos + sonido + refetch al entrar pedido). ✅ commit: d1137b1 feat(dashboard)
- T12 Verificación completa. ✅ Frontend: build ✓, lint sin errores, 315/315 tests (fix 7a26db1 elimina 4 excepciones no capturadas de unlockAudio en jsdom). Backend: 414 tests, 0 fallos.

## Log
- L1 (pedido original): "vamos a trabajar en el dashboard" → "agregar nuevas funcionalidades, quitar la de pedidos activos, y mejorar el diseño/UX". Features elegidas: Ventas últimos 7 días, Auto-refresh. UX: pulir estilo, estados de carga, manejo de errores visible, responsive.
- L2: 7 commits en la rama del worktree; endpoint nuevo GET /api/restaurants/{slug}/analytics/sales-last-7-days; semántica de revenue igual a sales-by-hour (SUM(total) sin filtro de estado).
- L3: Usuario pidió "¿qué más podemos agregar que sea útil?" y eligió: Tendencias de productos, Comparar semana anterior, Aviso de pedidos en vivo (descartó Predicción vs real). Ventas demo cargadas en DB local (ids 991xxx) para ver el dashboard con datos.
