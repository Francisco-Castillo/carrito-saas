# Aislamiento por negocio: cerrar los agujeros detectados

**Estado:** activo
**Fecha inicio:** 2026-10-14
**Objetivo:** Cerrar los cuatro agujeros de aislamiento/aislamiento de superficie detectados al implementar los endpoints del admin, sin romper los flujos públicos (carta digital y QR) ni el panel.

## Orden de severidad (medido, no supuesto)

1. **Fuga real de datos de negocio** (verificada en vivo 2026-10-14, sin token): `GET /api/restaurants/{slug}/dashboard/today`, `/{slug}/orders/status-summary`, `/{slug}/analytics/top-products` y `/{slug}/analytics/sales-by-hour` responden **200 con datos del negocio** (pedidos, facturación, ticket promedio, tiempo de cocina, ventas por hora). El slug es público (va en el QR impreso), así que hoy cualquiera lee los KPIs de cualquier negocio. Además `POST /api/restaurants/{slug}/users` es alcanzable sin token (devolvió 500 con body vacío, es decir llegó al controller) — requiere confirmar con un test si un payload válido crea usuarios.
   Causa: `SecurityConfig.java:84` permite `/api/restaurants/**` completo, y el frontend del dashboard (`frontend/src/pages/dashboard/api.ts:40`) llama esos endpoints **sin header de autorización** (sólo el admin manda Bearer). El único matcher realmente necesario es `/api/restaurants/slug/**` (`SecurityConfig.java:81`), que cubre menú público y login.
2. **Tres agujeros de escritura cross-tenant**, hoy sin llamadores en el repo (ni backend, ni tests, ni frontend): `PUT /api/{id}`, `POST /api/productos`, `POST /api/combos`. Al no tener llamadores, cerrarlos no rompe nada.

## Agujeros (todos verificados leyendo el código)

1. **`PUT /api/{id}` sin scope de negocio.** `ProductController#actualizar` → `ProductServiceImpl.actualizarProducto` (`service/.../ProductServiceImpl.java:154-168`) carga el producto con un `findById` crudo: cualquier usuario autenticado puede modificar el producto de **otro** negocio pasando un id ajeno, e incluso reapuntarlo a una categoría de otro negocio (el `categoryId` del DTO no se valida). Mapping raro colgado de `@RequestMapping("/api")`.
2. **`POST /api/productos` sin validar dueño de la categoría.** `crearProducto` toma `categoryId` del request sin comprobar que la categoría sea del negocio del token: se puede crear producto dentro de la categoría de otro negocio.
3. **`ComboServiceImpl.crearCombo` sin categoría ni ownership.** Nunca setea `Combo.category` (nullable=false en la DB) y busca los productos por id sin chequear dueño: hoy la creación de combos por API falla y no está scopeada.
4. **`/api/restaurants/**` es `permitAll`.** Verificado en vivo (2026-10-14) con el backend corriendo y **sin token**: `GET /api/restaurants/{id}/products` devuelve **200 con cuerpo vacío para cualquier id** (incluso uno inexistente), porque el controller es un stub (`ProductController#getProductsByRestaurant` hace `return null` con la llamada al service comentada), y `GET /api/restaurants/slug/{slug}/products` devuelve **500**. Es decir: **no hay fuga de datos efectiva hoy** — lo que hay es (a) un matcher demasiado amplio, que hace público por defecto cualquier endpoint futuro bajo ese prefijo, y (b) un endpoint muerto que responde 200/null cuando debería ser 404. Severidad menor que los tres agujeros de escritura, pero se cierra igual.

## Hechos del mapeo de llamadores (2026-10-14)

- **Nadie llama** a `PUT /api/{id}`, `POST /api/productos` ni `POST /api/combos` (backend, tests, frontend): son rutas muertas. El admin React usa sólo los endpoints nuevos scopeados.
- `ComboCreateDTO` **no tiene `categoryId`**, así que `crearCombo` no puede setear la categoría sin cambiar el payload (y `IComboMapperImpl.toDTO` haría NPE si la categoría quedara null).
- Existen los helpers de scoping: `ProductRepository.findByIdAndCategory_Business_Id` y `findByCategory_Business_Id`.
- Ningún test actual pinea la conducta insegura: cerrar estos agujeros no rompe la suite.

## Reglas de la casa a respetar

- El negocio se deriva siempre del JWT (`ISecurityService#getCurrentBusinessId`), nunca de un parámetro del request.
- Fila ajena o inexistente → **404** (nunca un éxito silencioso ni un 500 genérico). Reusar `BusinessException` con `NOT_FOUND` / `CONFLICT` como en `CategoryServiceImpl` y en los endpoints del commit `cdaa4d0`.
- No hay method security ni `@PreAuthorize` en el repo: el ownership se aplica en la capa de servicio. Mantener ese estilo.
- Los tests de la casa: `BusinessAuthSeed.seedOwner`, `@SpringBootTest @Transactional`, MockMvc con el `FilterChainProxy` real, y casos A/B de dos negocios (ver `CrossTenantStockIsolationTests` y `AdminProductAndComboEndpointTests`).

## Tareas

- [ ] 1. (Prioridad 2) `PUT /api/{id}`: scope del producto al negocio del token y validación de dueño de la categoría destino. Tests: producto ajeno → 404, categoría ajena → 404/400, edición propia sigue funcionando.
- [ ] 2. (Prioridad 2) `POST /api/productos`: validar que la categoría destino sea del negocio del token. Tests: categoría ajena → 404/400, creación propia sigue funcionando.
- [ ] 3. (Prioridad 3) `crearCombo`: resolver y setear la categoría (del negocio) y scopear la búsqueda de productos. Tests: combo propio se crea con su categoría, producto ajeno → falla, categoría ajena → falla.
- [x] 4. **Prioridad 1** — `SecurityConfig`: reemplazar el `permitAll` amplio de `/api/restaurants/**` por los matchers exactos que la carta pública y el QR necesitan; decidir qué hacer con el stub `GET /restaurants/{id}/products` (borrarlo o devolver 404 en vez de 200/null) y con el 500 de la variante por slug. Verificar que la carta digital sigue funcionando sin token.
- [ ] 5. Verificación: `mvn verify` en base aislada + e2e de navegador (menú público, login, dashboard, admin con productos/combos, KDS) + websocket.

## Evidencia / commits

- **Tarea 4 — commit `fa51a37` *fix(api): require authentication for the business dashboard endpoints***: matcher `/api/restaurants/**` eliminado (queda sólo `/api/restaurants/slug/**`), y `frontend/src/pages/dashboard/api.ts` ahora manda `Authorization: Bearer`. Tests nuevos: `DashboardAnonymousAccessTests` (3: anónimo 403 en los cuatro GET + POST users; con token del owner 200; slug y menú siguen públicos) y `frontend/tests/dashboard-api-auth.test.ts` (2: header presente, URLs intactas). RED observado antes del fix (200 anónimos en vivo y header ausente).
  - Verificación independiente del orquestador, **en vivo y en navegador** (`~/.local/opt/e2e/`): sin token → 403 en los cuatro endpoints y en `POST /users`, 200 en `/api/restaurants/slug/demo-e2e` y `/api/menu/demo-e2e`; con token → 200. E2E de las 5 pantallas **5/5 PASS** (sin ningún 4xx/5xx), y con un pedido creado a propósito el dashboard mostró `Pedidos hoy = 2` con las cuatro llamadas autenticadas en 200 — o sea, el header nuevo trae datos reales, no ceros silenciosos. E2E de admin **6/6 PASS** y websocket STOMP OK.
- Herramientas de verificación persistentes: `~/.local/opt/e2e/{screens.mjs,admin-products.mjs,ws-orders.mjs,dash-check.mjs}` (Playwright + Chromium instalados ahí, fuera de `/tmp`, que se limpió con el reinicio).
