# UI Liquid Glass — rediseño completo de interfaz

**Estado:** activo
**Fecha inicio:** 2026-06-09
**Objetivo:** Rediseñar por completo la UI de las 5 pantallas de la app (menu, login, dashboard, admin, kds) con estilo liquid glass moderno, responsive, migrando el frontend de HTML/CSS/JS vanilla a React + Vite + TypeScript.

## Decisiones tomadas (usuario)

- Alcance: las 5 pantallas.
- Base técnica: migración a framework — React + Vite (+ TypeScript).
- KDS: sí cambia de look, adaptado (alto contraste, elementos grandes).
- Primero: propuesta visual de **login** como muestra para aprobar la dirección antes de continuar.

## Dirección visual (definida por el equipo)

- Glassmorphism translúcido: paneles con `backdrop-filter: blur`, bordes de 1px luz, sombras suaves.
- Fondo oscuro con gradientes/mesh difusos (aurora) como capa decorativa.
- Tipografía: Inter (consistente con la app actual), pesos 400–700.
- Acento: gradiente índigo→violeta (continuidad con el estilo actual), tokens de color como CSS custom properties compartidos.
- Radios amplios (16–24px), sombras `0 20px 60px`, micro-animaciones de entrada y hover.
- Responsive mobile-first: en pantallas chicas la card se ajusta al ancho con padding reducido.

## Tareas

- [x] 1. Scaffold Vite + React + TS en `frontend/` con design tokens base (colores, sombras, radios, glass).
- [x] 2. Pantalla Login en React con estilo liquid glass (muestra de propuesta) portando la lógica actual (slug, caché de restaurante, login, roles, recordarme, loading, errores).
- [x] 3. Aprobación humana de la propuesta visual de Login antes de continuar (aprobada por el usuario).
- [x] 4. Menú público (carta digital) en React con nuevo estilo + responsive.
- [x] 5. Dashboard (OWNER) en React con nuevo estilo, cards y charts.
- [x] 6. Admin en React con nuevo estilo.
- [x] 7. KDS en React con nuevo estilo adaptado (alto contraste, touch-friendly).
- [x] 8. Integración build→Spring: Vite buildea a `api/src/main/resources/static`, entry HTML en las 5 rutas legacy, ruteo por pathname, proxy de dev y assets buildados commiteados.
- [x] 9. Toolchain local (OpenJDK 21 + Maven 3.10 + PostgreSQL 17 por Linuxbrew) y base `carrito_db` con datos semilla.
- [x] 10. Verificación e2e real: navegador headless sobre las 5 pantallas + websocket STOMP + API por curl.
- [x] 11. Portar los 3 contratos JS del CI a Vitest en el frontend React (18 tests, con prueba de mutación).
- [x] 12. Actualizar `.github/workflows/ci.yml`: suite Vitest + guard de bundle no obsoleto.
- [x] 13. Limpieza del legacy HTML/CSS/JS y de los runners Node reemplazados, con re-verificación e2e posterior.

## Cierre

**Decisión final del usuario:** aceptar el cierre **sin review nativo**. El candidato (y su versión sin bundle) excede el budget del reviewer acotado: ~7.2k líneas cambiadas en 43 paths. No se creó autoridad en ninguno de los dos intentos, así que no queda estado de review pendiente ni autoridad que abandonar. La verificación real se sostiene en `mvn -Pfrontend verify` (57/57), Vitest (20/20), el e2e de navegador (5/5, sin errores de consola ni HTTP≥400) y la prueba de mutación de los contratos portados.

Reproducir el entorno: `pg_ctl -D /home/linuxbrew/.linuxbrew/var/postgresql@17 -l /tmp/pg.log start`, `psql -U admin -h 127.0.0.1 -d carrito_db -f docs/dev-seed.sql`, `mvn -Pfrontend package`, `java -jar api/target/api-0.0.1-SNAPSHOT.jar`.

## Decisiones del usuario (cierre)

- Review nativo: revisar un **slice acotado** (integración + CI + limpieza), no el diff completo del workspace.
- Seed de desarrollo: **versionado** en `docs/dev-seed.sql` (con advertencia de no correrlo en producción; los ids fijos chocan con los fixtures de los tests Java sobre schema limpio).
- UX del menú: **la primera categoría arranca abierta** (antes todas colapsadas), con test propio `frontend/tests/menu-categories.test.tsx` (RED observado antes de implementar) y el happy path portado actualizado al nuevo flujo sin perder aserciones.

## Restricción de entorno (resuelta)

- Este entorno no tenía Java, Maven ni Postgres. Se instalaron **sin sudo** por Linuxbrew: OpenJDK 21, Maven 3.10, PostgreSQL 17.
  - Cluster: `initdb -D /home/linuxbrew/.linuxbrew/var/postgresql@17 -E UTF-8 --locale=C.UTF-8` — ojo: `en_US.UTF-8` **no existe** en esta máquina y `initdb` falla con ese locale.
  - Arranque: `pg_ctl -D /home/linuxbrew/.linuxbrew/var/postgresql@17 -l /tmp/pg.log start`.
  - Base `carrito_db`, rol `admin/admin`; backend en `:9090` (`java -jar api/target/api-0.0.1-SNAPSHOT.jar`).
- Contrato de URLs impreso: el QR del backend se construye con `qr.public-menu-url` + `/menu/index.html?restaurant=<slug>` (`BusinessController.getCurrentBusinessQr` → `menuUrlBuilder`), y los redirects legacy usan `/login/login.html`, `/dashboard/index.html`, `/kds/index.html`, `/admin/admin.html`. Esas rutas siguen funcionando tras la migración (el build copia el shell de la SPA a cada una).

## Evidencia / commits

### Descubrimiento tardío: los contratos JS del CI ejecutan el legacy

`api/src/test/js/` tiene 3 contratos Node que corren en CI y ejecutan los archivos legacy:
- `menu-app.test.mjs` (259 líneas) → contrato de fallo de la carta digital sobre `menu/app.js` (sin slug → mensaje y STOP, sin fetch de `/api/menu/undefined`; fetch fallido/malformado → mensaje sin lista ni panel de pedido; happy path con carrito y submit).
- `frontend-origin.test.mjs` (102) → ningún asset en `static/` puede hardcodear el origen de la app (`localhost`/`127.0.0.1`); la API va relativa.
- `page-guard.test.mjs` (297) → guards inline de `kds/index.html` y `dashboard/index.html` + cadena de redirect de `login.js` (AC10–AC13: token nulo/dead, storage bloqueado, sin URLSearchParams, sin claim de roles).

Estado medido (2026-10-13): `menu-app` PASS, `frontend-origin` PASS, `page-guard` **FAIL — 7 fallos**: el build pisó los `index.html` de kds y dashboard, así que los guards inline que el contrato busca ya no existen. Es decir: el CI queda rojo hasta portar esos contratos a la implementación React.

Plan ajustado: (a) portar los 3 contratos al frontend React con Vitest + jsdom (testing-library), (b) adaptar el guard de origen a las fuentes nuevas, (c) recién entonces borrar el legacy y actualizar `.github/workflows/ci.yml`.

Branch: `feature/ui-liquid-glass` (desde `develop`).

- Tareas 1–2 — commit `c2a617c` *feat(frontend): bootstrap Vite + React + TS app with liquid glass login*: `frontend/` Vite + React + TS. `npm run build` → ✓ 18 modules, 0 errores TS (CSS 9.83 kB, JS 224.87 kB). Paridad funcional verificada: redirect por rol (OWNER/KITCHEN/ADMIN), claves `restaurantName_{slug}`, `savedUser`, `expiresAt`. Archivos clave: `frontend/src/styles/tokens.css`, `frontend/src/pages/Login.tsx`, `frontend/src/pages/Login.css`.
- Tarea 3 — aprobación humana de la muestra de Login (usuario).
- Tarea 5 — commit `95e3e27` *feat(frontend): add liquid glass owner dashboard*: `npm run build` → ✓ 36 modules, 0 errores TS (CSS 22.28 kB, JS 443.77 kB con chart.js). Portado: guard de sesión (token trimmed), 4 loaders (`/api/restaurants/{slug}/dashboard/today`, `.../orders/status-summary`, `.../analytics/top-products`, `.../analytics/sales-by-hour`), KPIs, estados de pedidos, top productos, chart Chart.js (npm, no CDN), expiración de JWT y logout. Archivos: `frontend/src/pages/Dashboard.tsx` + `Dashboard.css`, `frontend/src/pages/dashboard/{api,auth,format,KpiCards,OrderStatusCards,SalesChart,TopProducts}.ts(x)`.
  - **Hallazgo verificado**: `dashboard/index.html` legacy nunca llamaba a `initDashboard()` (solo `admin/admin.js:81` lo hacía) → la página legacy quedaba en ceros. El port en React carga al montar: corrección deliberada, registrada en el mensaje del commit.
- Tarea 6 — commit `51cc885` *feat(frontend): add liquid glass admin panel*: `npm run build` → ✓ 48 modules, 0 errores TS (CSS 30.27 kB, JS 455.29 kB). Portado: guard de sesión, sidebar con vistas, sección dashboard embebida reutilizando componentes vía hook nuevo `useDashboardData`, tablas de productos/categorías/combos con toggle, delete, drag reorder, rename inline, modal de creación, modal de confirmación y toasts. Archivos: `frontend/src/pages/Admin.tsx` + `Admin.css`, `frontend/src/pages/admin/{api,DashboardSection,ProductsTable,CategoriesTable,CombosTable,ConfirmModal,CreateCategoryModal,toast,ToastHost}`, `frontend/src/pages/dashboard/useDashboardData.ts`.
  - **Deviations**: SortableJS (CDN) → drag & drop HTML5 nativo (mismo endpoint/payload; sin reorder por touch); el 404 legacy que reemplazaba `document.body.innerHTML` ahora es un mensaje renderizado.
  - **Quirks legacy preservados**: toggle de producto siempre manda `activo=false`, delete sin confirmación, logout del admin hace `localStorage.clear()` (borra `savedUser` del login nuevo).
- Tarea 7 — commit `58f84df` *feat(frontend): add liquid glass kitchen display*: `npm run build` → ✓ 76 modules, 0 errores TS (CSS 39.92 kB, JS 488.48 kB). Portado: guard de sesión, columnas por estado, reloj 1s, audios ding/ready con unlock por primer gesto (copiados a `frontend/public/`), drag/swipe (right >80px avanza, left en NEW abre cancelación), long-press 1500ms, modal de cancelación con motivos desde `/api/cancellation-reasons`, toasts, fullscreen, PATCH `/api/orders/{id}/status?status=`, `/api/business/orders/active`, WebSocket STOMP `/topic/orders/{slug}` con reconexión 5s. Archivos: `frontend/src/pages/Kds.tsx` + `Kds.css`, `frontend/src/pages/kds/{api,audio,useKdsOrders,OrderCard,CancelOrderModal,toast,KdsToastHost}`, `frontend/public/{ding,ready}.mp3`.
  - **Deviations**: SockJS + stompjs (CDN) → `@stomp/stompjs` sobre el transporte WebSocket crudo del mismo endpoint SockJS (`/ws/orders/websocket`); se descartan los fallbacks XHR/iframe; el polling comentado del legacy queda deshabilitado (código muerto).
  - **Riesgo abierto**: todo el CSS viaja en un bundle y hay fuga de selectores sueltos entre vistas (`.topbar`/`main` de Dashboard.css, `.restaurant-name` de Menu.css); KDS lo neutraliza con overrides scoped — conviene namespacing o CSS Modules.

### Verificación final (2026-10-13)

- **Java:** `mvn -B verify` → **57/57 tests, BUILD SUCCESS** (con base limpia; el único fallo previo era contaminación de mi propio seed, no del cambio: `TenantScopedQueryContractTests.seedFixtures` chocaba con `combos_pkey` porque el seed inserta `combos.id=1`).
- **Frontend:** `npm test` → **4 archivos, 18/18 tests**. `npm run build` → 0 errores TS.
- **Bundle reproducible:** `npm run build:spring` + `git diff --exit-code -- api/src/main/resources/static` → sin diferencias (el paso nuevo de CI funciona).
- **e2e navegador (Playwright/Chromium, post-limpieza):** menú, login→redirect, dashboard (KPI), admin, KDS con pedido creado por la API — **5/5 PASS**, cero errores de consola y cero HTTP ≥ 400.
- **Websocket:** cliente STOMP propio conecta a `/ws/orders/websocket`, suscribe `/topic/orders/demo` y recibe el pedido publicado por la API.
- **Bug de integración encontrado por el e2e:** `SecurityConfig` no permitía `/assets/**` → el HTML servía 200 y el bundle 403; arreglado en `c81e24e`.
- **UX heredada detectada (no regresión):** las categorías del menú arrancan colapsadas (el legacy también: `.category-content { max-height: 0 }`), así que la carta se ve vacía hasta tocar una categoría. Candidato a mejora de UX, pendiente de decisión del usuario.

### Commits (branch `feature/ui-liquid-glass`, 12 en total)

`c2a617c` scaffold+login · `3813d68` menú · `95e3e27` dashboard · `51cc885` admin · `58f84df` kds · `c6bc61d` build→static+rutas · `c81e24e` permitAll de assets · `7693484` assets buildados · `7d62448` docs · `f3dc280` contratos a Vitest · `c7faee6` CI · `959c8ac` limpieza legacy · `4c015ea` docs finales.

### Review nativo (RDD on): stop por budget

Preflight acotado al slice elegido (integración + CI + limpieza, `baseRef=58f84df`): el lineage se creó (`review-57124a42342c772e`, 47 paths) pero el START devolvió:

```
code: lens_context_budget_exceeded  (phase: preflight, mutation_outcome: not_started)
```

Sin autoridad creada: nada que abandonar ni reparar. Causa medible: los **assets generados commiteados** (`api/src/main/resources/static/assets/*.js`, ~470 KB minificados ≈ 150k tokens) entran en cualquier rango que termine en HEAD, así que el candidato no entra en el budget de las 4 lenses.

Continuación documentada por el propio stop: reducir el alcance del candidato (una secuencia encadenada de commits más chicos) o deshabilitar el switch (`D`). Con el bundle versionado, un candidato source-only no es contiguo: la única forma real de restaurar la revisabilidad es sacar los artefactos generados de Git y buildearlos al empaquetar (decisión de entrega, del usuario).

### Segundo intento de review: tampoco entra (y la lección es otra)

Tras desversionar el bundle (commit `613072c`), el candidato quedó **source-only** (43 paths) y el START volvió a frenar igual:

```
code: lens_context_budget_exceeded  (mutation_outcome: not_started, lineage review-80897a67b13caf11)
```

Es decir: **el bundle no era la causa principal**. El slice source-only tiene ~2.161 líneas agregadas + ~5.044 borradas (≈7.2k líneas) y no entra en el budget de la política acotada del reviewer. También probé seleccionar lenses por el input del START (`lenses: "risk"`) y el facade lo rechaza limpio: `native-start-input-invalid / unknown-field / lineage_created: false`.

Consecuencia estructural: como el candidato es siempre `[baseRef, HEAD]`, con HEAD fijo sólo se puede revisar la **cola** de la rama; el trabajo grande no puede partirse retroactivamente. La continuación documentada por el propio stop es entregar esto como una **secuencia encadenada de commits/PRs chicos, cada uno bajo el budget** — es un cambio de modelo de entrega, no un retoque.
