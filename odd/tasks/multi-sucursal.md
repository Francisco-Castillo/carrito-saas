# Multi-sucursal

**Estado:** fase 1 cerrada (2026-10-24), fases 2-4 pendientes
**Rama:** `feat/multi-sucursal`
**Slice:** `a6de1f3` (707 líneas, una unidad: entidad + CRUD + tests + doc). Las 44 commits que ve el harness contra `origin/main` son historia previa de `develop`; el feature arrancó limpio desde ahí.
**Origen:** L1 (pedido original del usuario, verbatim).

## Specs

- **S1** (modelo): «No sé, recomiendame» — el diseño lo define este documento: entidad `Branch` debajo de `Business`. `Business` queda como tenant (frontera de seguridad intacta: `TenantListener`, `getCurrentBusinessId()`, JWT con `businessId`); `Branch` (sucursal) vive debajo y agrupa lo por-sucursal.
- **S2** (carta): «Compartida» — «Una sola carta definida a nivel grupo; cada sucursal puede desactivar productos específicos (disponibilidad por sucursal)». La carta (productos, categorías, combos, opciones) queda a nivel `Business`; la disponibilidad por sucursal es extensión futura, no de esta feature.
- **S3** (operación): «Básico + WhatsApp/QR» — «Pedidos, KDS y horarios aislados por sucursal; usuario dueño gestiona todas» + «cada sucursal con su propio número de WhatsApp y QR de menú propio».
- **S4** (acceso público): «Link de grupo con selector» — «Un solo link del grupo donde el cliente elige la sucursal y luego pide».

## Diseño

| Nivel | Vive ahí |
|---|---|
| `Business` (tenant, sin cambios) | Carta, usuarios/roles, settings compartidos, slug del grupo |
| `Branch` (nueva) | Pedidos (f2), horarios (f2), WhatsApp (f3), QR (f3), nombre, dirección, activo |
| JWT (f2) | `branchId` activo; dueño cambia de sucursal, personal fijado a la suya |

Tabla `branches`: la crea Hibernate (`ddl-auto: update`, tabla nueva). Migración manual idempotente en `docs/migrations/` siembra una sucursal default por negocio existente.

## Tasks

- **T1** (S1) `repository`: entidad `Branch` + `BranchRepository` + migración de sucursal default por negocio existente. Commit: `a6de1f3`
- **T2** (S1) `service`+`api`: DTO, mapper, `IBranchService`/`BranchServiceImpl`, `BranchController` `/api/business/branches` (GET/POST/PUT/DELETE, baja lógica, id ajeno → 404, scoping por tenant del JWT). Commit: `a6de1f3` (mismo slice: unidad única, RED observado antes de implementar; suite completa verde)
- **T3** (S3) fase 2: contexto de sucursal (JWT/panel) + scoping de pedidos y KDS por sucursal. Commit: `cb32947` (JWT branchId + BranchContext + select endpoint; pedidos con branch_id + checkout con branchId opcional; KDS/consola/estado/cancelación scopeados; fallback: una activa se resuelve sola, varias → 400, ninguna → vista negocio; suite completa verde; RED conductual no observado limpio, anotado en el commit)
- **T4** (S3) fase 2: horarios por sucursal. Commit: (pendiente)
- **T5** (S3) fase 3: WhatsApp por sucursal. Commit: (pendiente)
- **T6** (S3) fase 3: QR de menú por sucursal. Commit: (pendiente)
- **T7** (S4) fase 4: link de grupo con selector de sucursal y checkout contra la elegida. Commit: (pendiente)

## Log

- **L1** «podriamos implementar el multi-sucursal de un de un negocio ?» — pedido original. Decisiones del usuario (cuestionario): modelo «No sé, recomiendame» ⇒ Branch (este doc); carta «Compartida»; operación «Básico + WhatsApp/QR»; acceso «Link de grupo con selector». Autorizado arrancar por fase 1.
- **L2** Fase 1 implementada y verificada (`a6de1f3`): `BranchEndpointTests` 11/11 (RED observado antes), suite completa de los 4 módulos verde.
- **L3** T3 implementada y verificada (`cb32947`): `BranchOrdersScopingHttpTests` 9/9, suite completa verde. Nota honesta: RED conductual no se observó limpio (dos runs murieron por compilación antes de los asserts); los tests fijan el comportamiento.
- **L4** Revisión nativa RDD del slice fase 1: lineage `review-806654a46d5e32a4` (medium), lente reliability capturado; lente risk bloqueado por defecto determinista del relay (reviewer-empty-output, stopReason length ×3). Usuario eligió reintentar más tarde (memoria odd/multi-sucursal/review).
- **L5** T4 implementada y verificada (fase 2): horarios y fechas especiales por sucursal — entidades `BusinessHours`/`BusinessDateException` con `@ManyToOne(LAZY)` opcional a Branch y únicas nombradas ampliadas con `branch_id`; migración idempotente `docs/migrations/2026-10-28-branch-hours.sql` (backfill a la sucursal default, swap de únicas + FKs); queries de repositorio con sucursal opcional; `isOpen(businessId, branchId, now)`; perfil leído/escrito scopeado a la sucursal del JWT (claim > única activa > null > 400); menú público sirve las horas de la sucursal default. `BranchHoursHttpTests` 4/4 (RED conductual observado: 3 failures + 1 error antes de implementar), suite completa verde. Nota de diseño: la query opcional incluye `OR h.branch IS NULL` — las filas legacy a nivel negocio las heredan las sucursales que no definieron las suyas; sin esa cláusula, los pedidos con sucursal default de negocios migrados por Hibernate (filas branch NULL) quedarían "cerrados" y rompería `BranchOrdersScopingHttpTests`. La DB de dev conserva la única vieja de 3 columnas hasta correr la migración; los fixtures evitan colisiones (business, day, position) hasta entonces.
