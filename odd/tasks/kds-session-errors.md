# kds-session-errors

Cierre del pendiente registrado de `odd/tasks/kds-access.md`: "kds.js no maneja 401/403: se traga el error y dibuja columnas vacías. Es el caso profundo detrás de 'pantalla muerta'". El KDS ya está portado a React (`frontend/src/pages/kds/`); el defecto sobrevivió al puerto: el `catch` de `refresh()` en `useKdsOrders.ts` se traga todo error que no sea `RestaurantNotFoundError`, y con un token vencido el tablero arranca vacío sin ninguna señal. El guard de sesión corre una sola vez al montar, así que un token que expira en pleno turno deja el panel "funcionando" con todas las llamadas fallando.

## Specs

- S1 (pending registrado, verbatim): "kds.js no maneja 401/403: se traga el error y dibuja columnas vacías. Es el caso profundo detrás de \"pantalla muerta\"."
- S2: un 401 o 403 en `loadOrders` (GET /api/business/orders/active) se reporta con una señal explícita propia (`UnauthenticatedError`), nunca con el error genérico "Error cargando pedidos".
- S3: un 401 o 403 en `advanceOrder`, `loadCancellationReasons` o `cancelOrder` también se reporta con la misma señal explícita, sin toasts genéricos que oculten la causa.
- S4: `useKdsOrders` traduce la señal a un estado fatal propio (`"unauthenticated"`), distinto de `"restaurant-not-found"`, tanto en la carga inicial como en el avance de estado.
- S5: la página KDS, con ese fatal, limpia la sesión (token + slug, misma semántica que `logoutKds`) y vuelve al login. La navegación vive a nivel página, nunca en el scope del hook/módulo (incidente histórico de bucle de redirect).
- S6 (no-regresión): los errores que NO son de sesión conservan el comportamiento actual — 500/red en la carga conservan los datos en pantalla sin fatal; 409 en `advanceOrder` sigue siendo toast warning con el mensaje del servidor y `false`; 404 de restaurante sigue siendo su fatal propio.

## Tasks

| ID | Specs | Ruta | Commit |
| --- | --- | --- | --- |
| T1 | S2, S3, S6 | inline (RED-first: frontend/tests/kds-session-errors.test.ts) | a429d97 |
| T2 | S4, S6 | inline (RED-first: frontend/tests/kds-session-fatal.test.tsx) | a9b0666 |
| T3 | S5 | inline (RED-first: frontend/tests/kds-session-redirect.test.tsx) | f46a6f6 |
| T4 | S6 | regresión frontend completa + cierre | este commit |

## Log

- L1 (usuario): "que quedo pendiente ?" — relevamiento; el KDS quedó entre los pendientes registrados.
- L2 (usuario): "y puedo por ejemplo delegarte a ti el milti-tenant y a otra sesion lo del kds ?" + "si" — delegación original a otra sesión; worktree multi-tenant: `/home/rodrigo/work/carrito-saas-mt` (feature `cancel-order-contract`, cerrado).
- L3 (usuario): "al final no abri otra sesion solo eres tu" — el eje KDS vuelve a esta sesión. Elección del usuario: "Arranco por kds.js 401/403". Worktree propio: `/home/rodrigo/work/carrito-saas-kds`, rama `fix/kds-error-handling` (base `origin/develop` 5b5231c).
- L4 (resultado): 3 commits (T1 a429d97, T2 a9b0666, T3 f46a6f6). RED observado en los tres: T1 5/6 fallando antes del arreglo (y los `catch` propios de `advanceOrder`/`loadCancellationReasons` tuvieron que re-lanzar la señal nueva); T2 3 fallos + la unhandled rejection del `.then` sin catch; T3 el logout no se disparaba. Verificación final: suite frontend 499/499, `tsc --noEmit` limpio. Los 2 fallos de `build-artifacts.test.ts` en la primera corrida completa eran ambientales (worktree sin bundle: CI usa `build:spring`, que copia las entradas legacy — `npm run build` a secas no alcanza).
