# Panel de operador: alta de clientes y sus locales

**Estado:** en curso (2026-02-14)
**Objetivo:** Panel de operador de plataforma para dar de alta clientes (dueño + negocio/local) desde un solo flujo, protegido por un rol de plataforma.

## Specs

- S1: "necesito que crees un panel para mi donde pueda dar de alta a los clientes y sus locales" — un panel de operador de la plataforma con listado de clientes y formulario de alta (cliente = usuario dueño + su local/negocio).
- S2: Decisión del usuario: **1 local = 1 cliente, como el modelo actual** (sin migración de modelo; cada `Business` sigue siendo independiente).
- S3: Decisión del usuario: **rol de plataforma nuevo** (`PLATFORM_ADMIN` en la tabla `roles`); sólo ese rol accede al panel y a los endpoints `/api/platform/**`.
- S4: Decisión del usuario: **el operador carga usuario y clave del cliente en el alta**; se crea el `User` owner con password bcrypt + `BusinessUser` OWNER y el cliente ya puede loguearse a su panel admin.
- S5: Convenciones del repo: contraseñas con `PasswordEncoder` (BCrypt) existente; IDs de `businesses`/`users` manuales (no `IDENTITY` para `businesses`); migraciones en `docs/migrations/` con nombres de constraint explícitos; seed en `docs/dev-seed.sql`.

## Tareas

- [x] T1 (S3, S5): Seguridad: `/api/platform/**` exige `ROLE_PLATFORM_ADMIN`; rol en `docs/dev-seed.sql` + migración `docs/migrations/2026-10-19-platform-admin-role.sql`. Login de operador sin membresía (token sin businessId; JwtFilter autentica sin TenantContext). Tests: `PlatformPrivilegeHttpTests` (6/6 verdes). Commit `3d61c70`.
- [x] T2 (S1, S4, S5): `POST /api/platform/clients`: alta atómica de `Business` + `User` owner + `BusinessUser` OWNER en una transacción. Unicidades con 409 legible (slug/username/email) y 400 para validaciones (slug shape, teléfono, password ≥8); rollback sin filas. `businesses_id_seq` en `schema.sql` + `docs/migrations/2026-10-19-businesses-id-sequence.sql` para los ids de businesses. Tests: `PlatformClientsHttpTests` (7/7 verdes, incluye prueba S4: el owner se loguea). Commit `76d32ef` (en rama `feat/platform-clients-panel`; incidente de working tree compartido resuelto — ver Log L3).
- [x] T3 (S1, S5): `GET /api/platform/clients`: listado de todos los locales, más viejo primero, con su cuenta owner cuando existe (null si no). `BusinessUserRepository.findOwnerMemberships` + `listClients` en el servicio. Tests: `PlatformClientsHttpTests` 9/9 (listado + orden + local sin dueño). Commit `7c46b15`.
- [x] T4 (S1, S3, S4): Frontend: vista `/platform` con guard por token (sin slug: el operador no tiene negocio), listado y formulario de alta con errores inline. `PLATFORM_ADMIN` redirige a `/platform` (regla ANTES que ADMIN: includes colisiona). Tests vitest: `platform-panel` + `view-routing` + `login-redirect` 29/29; suite frontend completa 269/269. Commit `a810a2c`.
- [x] T5 (S3, S5): Docs: migraciones incluidas en T1/T2 (`2026-10-19-platform-admin-role.sql`, `2026-10-19-businesses-id-sequence.sql`, seed en `dev-seed.sql`); `docs/` no tiene README — este doc es el cierre del feature (convención del repo, `docs(odd)`). Commit `docs(odd)` final.

## Log

- L4: Evidencia de cierre (2026-10-19): commits sobre `feat/platform-clients-panel` — `3d61c70` (T1 barrera+login), `76d32ef` (T2 alta), `7c46b15` (T3 listado), `a810a2c` (T4 frontend). Verificaciones: backend 9/9 `PlatformClientsHttpTests`, 5/5 `PlatformPrivilegeHttpTests`... 6/6, frontend 29/29 foco y 269/269 suite; regresión backend completa 348 tests con solo los 50 errores pre-existentes (`uq_roles_name`, entorno compartido, verificados en árbol limpio). Pendiente fuera de alcance: PR/merge (decisión del usuario).

- L1: Solicitud del usuario (2026-02-14), verbatim: "necesito que crees un panel para mi donde pueda dar de alta a los clientes y sus locales".
- L2: Decisiones del usuario (2026-02-14): (a) no, 1 local = 1 cliente como hoy; (b) rol de plataforma nuevo; (c) sí, el operador carga usuario y clave del cliente en el alta.
- L3: Incidente de working tree compartido (2026-10-19): la sesión par (feature "bloqueo de pedidos con local cerrado") comparte checkout; mi commit T2 aterrizó en su rama. Resuelto sin tocar working tree: `git branch -f` (mi rama → 76d32ef) + `git reset 3d61c70` (su rama, WIP intacto). Acordado: no cambiar checkout hasta que el par avise su commit. Los 3 fallos de `OrderClosedHoursValidationTests` en la suite son WIP del par, no regresión de este feature. Trabajo de T3 en disco sin commitear mientras tanto.
