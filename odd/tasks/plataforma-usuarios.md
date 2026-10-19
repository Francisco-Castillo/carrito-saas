# Panel de operador: gestión de usuarios del local

**Estado:** en curso (2026-10-19)
**Objetivo:** El operador (`/platform`) gestiona los usuarios de cualquier local: listado, alta con rol, cambio de rol, baja lógica y reset de clave. Continuación de plataforma-altas (#63) y plataforma-editar-baja (#66).

## Specs

- S1: "con usuarios del local esta bien" — el operador gestiona los usuarios del local desde su panel.
- S2: La gestión vive en **`/platform`** (operador de plataforma, rol `PLATFORM_ADMIN`), sobre cualquier local — no en `/admin` (el dueño no gestiona su equipo en este feature).
- S3: Acciones: **listado** (usuarios del local con rol y estado), **alta con rol**, **cambio de rol** (ej. KITCHEN → OWNER) y **baja / reset de clave**.
- S4: La baja es **lógica** (`users.active`): el usuario inactivo no puede loguearse, el historial de pedidos queda intacto; prohibido el borrado físico.
- S5: Convenciones: test-first (RED observado), migraciones idempotentes en `docs/migrations/`, errores legibles en español, work-unit commits convencionales. Toda la API queda detrás de la barrera `ROLE_PLATFORM_ADMIN` existente.

## Decisiones de diseño

- Columna nueva `users.active BOOLEAN NOT NULL DEFAULT TRUE` (mismo patrón que `businesses.active`).
- API nueva bajo `/api/platform/clients/{id}/users`:
  - `GET` — listado del local (username, email, rol, active).
  - `POST` — alta con rol (username, email, password, role); adopta las validaciones del endpoint de negocio existente.
  - `PUT /{username}` — cambio de rol del membership; opcionalmente reactivación.
  - `DELETE /{username}` — baja lógica (idempotente).
  - `POST /{username}/password` — reset de clave.
- El login bloquea usuarios inactivos: `CustomUserDetailsService` marca `disabled(!active)` — el AuthenticationManager responde credenciales inválidas sin revelar el motivo.
- Regla de protección: un local SIEMPRE conserva al menos un OWNER activo (el panel no puede dejar un local sin dueño).

## Tareas

- [x] T1 (S4, S5): `users.active` (NOT NULL, default TRUE) + migración `2026-10-19-users-active.sql` + `CustomUserDetailsService` marca disabled → 401 indistinguible de clave incorrecta. Tests: `UserDeactivationLoginHttpTests` 2/2. Commit `037a50a`.
- [x] T2 (S3, S5): `GET .../users` — listado del local (username, email, rol, active), ordenado por alta. Tests: `PlatformUsersListingHttpTests` 2/2. Commit `16d59cf`.
- [x] T3 (S3, S5): `POST .../users` — alta con rol (bcrypt, cuenta ACTIVE, membership). Tests: `PlatformUsersAltaHttpTests` 6/6 (login inmediato del creado, duplicados 409, rol inexistente 400 sin filas, 404). Commit `5703ad2`.
- [x] T4 (S3, S5): `PUT` cambio de rol + `DELETE` baja lógica — regla del último OWNER activo (409 legible, nada cambia en ambas rutas), 404 usuario fuera del local, reactivación por `active`. Tests: `PlatformUsersRoleAndDeactivationHttpTests` 7/7. Commit `cac63aa`.
- [x] T5 (S3, S5): `POST .../{username}/password` — reset de clave de cualquier usuario del local (bcrypt, ≥8; 404 slug-scoped — un usuario de OTRO negocio responde 404 con hash intacto). Tests: `PlatformUsersPasswordResetHttpTests` 4/4 (login con la clave nueva, hash viejo invalidado, 400 no toca el hash). Commit `b7df816`.
- [x] T6 (S1-S3): Frontend `/platform`: acción Usuarios por cliente → modal con listado (roles + badge de baja), alta con rol (select) y baja por fila con errores inline. Tests: `platform-panel` 11/11; suite completa 279/279. Commit `9fa8101`.
- [x] T7 (S5): Cierre del doc con evidencia. Commit `docs(odd)` final.

## Log

- L1: Solicitud del usuario (2026-10-19), verbatim: "con usuarios del local esta bien" (respuesta al candidato propuesto tras cerrar #67).
- L2: Decisiones del usuario (2026-10-19, cuestionario): (a) en `/platform` (operador); (b) todas las acciones: listado + alta con rol + cambiar rol + baja/reset; (c) baja lógica (recomendado).
- L3: Hallazgos (2026-10-19): JSONPath filter expressions devuelven LISTA (leer `[0]` del filter falla — asertar `containsExactly` sobre la lista); el peer reportó error de compilación de mi WIP en curso (DTO aún inexistente en árbol) — válido mientras duró la edición.
- L4: Evidencia de cierre (2026-10-19): commits sobre `feat/platform-users` (base develop 1c3a280) — `037a50a` (T1), `16d59cf` (T2), `5703ad2` (T3), `cac63aa` (T4), `b7df816` (T5), `9fa8101` (T6). Verificaciones: backend 19 tests en foco verdes (2+2+6+7+4) y regresión completa LIMPIA (0 errores — la higiene de roles de #64 eliminó los 50 pre-existentes); frontend 11/11 en foco y 279/279 suite completa. Pendiente fuera de alcance: push/PR (decisión del usuario), y rebuild del jar de dev por el peer para servir el feature.

## Log

- L1: Solicitud del usuario (2026-10-19), verbatim: "con usuarios del local esta bien" (respuesta al candidato propuesto tras cerrar #67).
- L2: Decisiones del usuario (2026-10-19, cuestionario): (a) en `/platform` (operador); (b) todas las acciones: listado + alta con rol + cambiar rol + baja/reset; (c) baja lógica (recomendado).
