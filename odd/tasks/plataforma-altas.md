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

- [ ] T1 (S3, S5): Seguridad: `/api/platform/**` exige `ROLE_PLATFORM_ADMIN`; rol en `docs/dev-seed.sql` + migración `docs/migrations/`. Tests HTTP (403 para OWNER, 200 para PLATFORM_ADMIN, 401 anónimo). Commit.
- [ ] T2 (S1, S4, S5): `POST /api/platform/clients`: alta atómica de `Business` (name, slug, phone) + `User` (username, email, password) + `BusinessUser` OWNER. Validaciones de unicidad (slug, phone, email, username) con 400 legible. Commit.
- [ ] T3 (S1, S5): `GET /api/platform/clients`: listado de clientes con su local (nombre, slug, teléfono, usuario owner, fecha alta). Commit.
- [ ] T4 (S1, S3, S4): Frontend: nueva vista de operador (acceso por rol de plataforma): login reutilizado, listado de clientes y formulario de alta con errores legibles. Tests vitest. Commit.
- [ ] T5 (S3, S5): Docs: actualizar docs del panel / README con el flujo de operador. Commit.

## Log

- L1: Solicitud del usuario (2026-02-14), verbatim: "necesito que crees un panel para mi donde pueda dar de alta a los clientes y sus locales".
- L2: Decisiones del usuario (2026-02-14): (a) no, 1 local = 1 cliente como hoy; (b) rol de plataforma nuevo; (c) sí, el operador carga usuario y clave del cliente en el alta.
