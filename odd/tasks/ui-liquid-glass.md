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
- [ ] 8. Integración build→Spring: configurar Vite para servir/buildear hacia los static de `api/` y verificar end-to-end. EN CURSO.
- [ ] 9. Limpieza: retirar los HTML/CSS/JS legacy reemplazados.

## Restricción de entorno (integración/e2e)

- Este entorno NO tiene Java, Maven ni Postgres (`java`/`mvn`/`docker` ausentes): no se puede levantar el backend Spring ni la base, por lo que el e2e real contra la API no puede ejecutarse acá. Verificación estática + dev proxy + checklist manual para el entorno del usuario.
- Contrato de URLs impreso: el QR del backend se construye con `qr.public-menu-url` + `/menu/index.html?restaurant=<slug>` (`BusinessController.getCurrentBusinessQr` → `menuUrlBuilder`), y los redirects legacy usan `/login/login.html`, `/dashboard/index.html`, `/kds/index.html`, `/admin/admin.html`. Esas rutas deben seguir funcionando tras la migración.
- Puerto del backend: 9090 (application.yml).

## Evidencia / commits

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
