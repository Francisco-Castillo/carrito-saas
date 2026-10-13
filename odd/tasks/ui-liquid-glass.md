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
- [ ] 5. Dashboard (OWNER) en React con nuevo estilo, cards y charts. EN CURSO: delegado a gentle-ai-worker.
- [ ] 6. Admin en React con nuevo estilo.
- [ ] 7. KDS en React con nuevo estilo adaptado (alto contraste, touch-friendly).
- [ ] 8. Integración build→Spring: configurar Vite para servir/buildear hacia los static de `api/` y verificar end-to-end.
- [ ] 9. Limpieza: retirar los HTML/CSS/JS legacy reemplazados.

## Evidencia / commits

Branch: `feature/ui-liquid-glass` (desde `develop`).

- Tareas 1–2 — commit `c2a617c` *feat(frontend): bootstrap Vite + React + TS app with liquid glass login*: `frontend/` Vite + React + TS. `npm run build` → ✓ 18 modules, 0 errores TS (CSS 9.83 kB, JS 224.87 kB). Paridad funcional verificada: redirect por rol (OWNER/KITCHEN/ADMIN), claves `restaurantName_{slug}`, `savedUser`, `expiresAt`. Archivos clave: `frontend/src/styles/tokens.css`, `frontend/src/pages/Login.tsx`, `frontend/src/pages/Login.css`.
- Tarea 3 — aprobación humana de la muestra de Login (usuario).
- Tarea 4 — commit `3813d68` *feat(frontend): add liquid glass public menu screen*: `npm run build` → ✓ 23 modules, 0 errores TS (CSS 19.62 kB, JS 235.16 kB). Endpoints: GET `/api/restaurants/slug/{slug}`, GET `/api/menu/{slug}`, POST `/api/orders/menu/{slug}`. Paridad legacy: combos como pseudo-productos, agrupación por categoría con acordeón, carrito keyed por id con localStorage `cart`, orden de validación (carrito→nombre→dirección DELIVERY), fallback WhatsApp, copy en español idéntico. Archivos: `frontend/src/view.ts`, `frontend/src/App.tsx`, `frontend/src/pages/Menu.tsx` + `Menu.css`, `frontend/src/pages/menu/{types,ProductCard,CartPanel}.tsx`.
