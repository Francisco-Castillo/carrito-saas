# Panel de operador: editar y dar de baja clientes

**Estado:** en curso (2026-10-19)
**Objetivo:** Extender el panel de operador (`/platform`, PR #63 mergeado) con edición de clientes, baja lógica reversible, reset de clave del dueño y carta pública caída para locales de baja.

## Specs

- S1: "si editar y dar de baja esta bien" — extiende el panel de operador del feature plataforma-altas (PR #63) con edición y baja de clientes.
- S2: Edición **solo campos seguros**: nombre, teléfono y email del dueño. El slug NO se edita (es la identidad pública: QR y URLs del menú; cambiarlo rompe los QR impresos).
- S3: Baja **lógica y reversible**: el local deja de operar pero los datos históricos (pedidos, ventas) quedan intactos. Prohibido el borrado físico.
- S4: **Reset de clave del dueño** desde el panel (para cuando el cliente la olvida): el operador define la nueva clave (≥8, bcrypt).
- S5: Con el local de baja, **la carta pública también cae**: `/api/menu/{slug}`, `/api/restaurants/slug/{slug}` y el alta de pedidos por slug (`POST /api/orders/menu/{slug}`) responden 404. El historial no se toca.
- S6: Convenciones: migraciones en `docs/migrations/` idempotentes; test-first (RED observado); commits work-unit convencionales; errores legibles en español (mismo contrato que #63).

## Decisiones de diseño (del operador, siguiendo S1-S5)

- Columna nueva `businesses.active BOOLEAN NOT NULL DEFAULT TRUE` (baja = `active=false`; reactivar = `active=true`).
- `PUT /api/platform/clients/{id}`: edita name/phone/ownerEmail y acepta `active` opcional (reactivación). 404 si el id no existe; 409 si el email choca con otro usuario.
- `DELETE /api/platform/clients/{id}`: baja lógica. Idempotente: bajar un local ya de baja responde 200 sin cambios; 404 sólo si el id no existe.
- `POST /api/platform/clients/{id}/password`: reset de clave del dueño.
- Los lookups PÚBLICOS por slug tratan `active=false` como no encontrado (404); los scopes internos del panel del dueño quedan intactos.

## Tareas

- [x] T1 (S3, S6): Campo `Business.active` (NOT NULL, default TRUE) + migración `docs/migrations/2026-10-19-businesses-active.sql` idempotente. Compilación verificada. Commit `c5be7b5`.
- [x] T2 (S2, S6): `PUT /api/platform/clients/{id}` — edición de campos seguros (name/phone/ownerEmail, parcial; slug y ownerUsername inmutables) + reactivación vía `active`. 404/409/400 con mensajes del contrato. Tests: `PlatformClientEditHttpTests` 8/8 (slug inmutable verbatim, auto-email aceptado, atomicidad del 409). Regresión completa: 356 tests, sin fallos nuevos. Commit `e722e5f`.
- [ ] T3 (S3, S6): `DELETE /api/platform/clients/{id}` — baja lógica idempotente. Tests RED-first. Commit.
- [x] T4 (S4, S6): `POST /api/platform/clients/{id}/password` — reset de clave del dueño (bcrypt, ≥8 igual que el alta). Tests: `PlatformClientPasswordResetHttpTests` 5/5 (login inmediato con la clave nueva, hash viejo invalidado, 400 no toca el hash, 404 sin dueño). Commit `418b7c6`.
- [x] T5 (S5, S6): Menú público (`/api/menu/{slug}` + slugless), lookup de restaurante (`/api/restaurants/slug/{slug}` en ProductController) y alta de pedidos (`POST /api/orders/menu/{slug}`) responden 404 para locales de baja — el gate va ANTES del check de horarios del peer (baja ≠ cerrado). Scopes internos del dueño intactos. Tests: `PlatformDeactivationPublicContractHttpTests` 3/3. Regresión: 368 tests sin fallos nuevos. Commit `8c2a28e`.
- [x] T6 (S1, S2, S3, S4): Frontend `/platform`: acciones por fila — Editar (modal con campos seguros precargados, slug read-only como nota), Dar de baja (con confirmación previa al DELETE, S3), Reactivar (badge honesto "de baja") y Nueva clave. Errores del backend inline; el listado se refresca tras cada acción. Tests vitest: `platform-panel` 8/8; suite completa 274/274. Commit `226dafc`.
- [x] T7 (S6): Cierre: doc actualizado con evidencia por tarea. Commit `docs(odd)` final.

## Log

- L1: Solicitud del usuario (2026-10-19), verbatim: "si editar y dar de baja esta bien" (respuesta a "¿Algo más sobre el panel — por ejemplo, edición/baja de clientes en un próximo feature?").
- L2: Decisiones del usuario (2026-10-19, cuestionario): (a) solo campos seguros, slug NO; (b) baja lógica (recomendado); (c) sí, reset de clave; (d) menú público también abajo.
- L3: Hallazgos de implementación (2026-10-19): ddl-auto:update NO agregó la columna `active` a la DB existente al arrancar ("column b1_0.active does not exist") — provisionada por `schema.sql` (patrón de `businesses_id_seq`). El lookup público `/api/restaurants/slug/{slug}` lo sirve `ProductController` (no `BusinessController`) — el gate del lookup va ahí.
- L4: Evidencia de cierre (2026-10-19): commits sobre `feat/platform-clients-edit-baja` (base develop 2dd7949) — `43cde00` (T1), `e722e5f` (T2), `1afd2e3` (T3), `418b7c6` (T4), `8c2a28e` (T5), `226dafc` (T6). Verificaciones: backend 8/8+4/4+5/5+3/3 en foco y 368 tests de regresión sin fallos nuevos (50 pre-existentes `uq_roles_name`, entorno local compartido); frontend 8/8 en foco y 274/274 suite completa. Pendiente fuera de alcance: push/PR (decisión del usuario).
