# Admin: fixes de comportamiento y endpoints faltantes

**Estado:** activo
**Fecha inicio:** 2026-10-13
**Objetivo:** Cerrar los pendientes heredados del admin legacy detectados al portar la UI a React, y decidir qué hacer con los endpoints que el panel llama y el backend no expone.

## Hallazgo principal (verificado contra el backend corriendo)

Endpoints que llama el admin portado vs. los que existen:

| Llamada del admin | Estado real |
| --- | --- |
| `GET /api/categorias` | ✅ `CategoryController` `@GetMapping` |
| `POST /api/categorias` | ✅ |
| `PUT /api/categorias/{id}` | ✅ |
| `PUT /api/categorias/orden` | ✅ |
| `GET /api/combos` | ❌ `ComboController` sólo tiene `@PostMapping` |
| `GET /api/products?businessId=1` | ❌ no existe → 403 (cae en `anyRequest().authenticated()`) |
| `PUT /api/products/{id}/activar?activo=` | ❌ no existe |
| `DELETE /api/products/{id}` | ❌ no existe: no hay `DeleteMapping` de productos en el backend |

Existe en cambio `GET /api/restaurants/{restaurantId}/products` y `GET /api/restaurants/slug/{slug}/products` (paginados) y `POST /api/productos` / `PUT /api/{id}`.

Consecuencia: **la tabla de productos y la de combos del admin nunca funcionaron** (el legacy tampoco: llamaba a esas mismas rutas). Es la misma clase de bug que `dashboard/index.html`, que nunca llamaba a `initDashboard()`.

Nota de seguridad relacionada: el legacy (y el port fiel) llama a `/api/products` y `/api/combos` **sin header de autorización** y con `businessId=1` hardcodeado; las rutas de categorías sí mandan el Bearer.

## Tareas

- [x] 1. Logout del admin: dejar de hacer `localStorage.clear()` y borrar sólo la sesión (token/role/username/expiresAt/businessId), para no llevarse el `savedUser` que guarda el login. Con test.
- [x] 2. Botón "+ Nuevo": abrir el modal de crear categoría sólo desde la vista de categorías (el legacy lo cableaba desde cualquier vista). Con test.
- [x] 3. Decisión del usuario sobre productos/combos: **implementar los endpoints faltantes** (elegido por el usuario).
- [ ] 4. (según 3) Toggle de producto: mandar el estado destino (`activo=!active`) en vez del `activo=false` fijo del legacy.
- [ ] 5. (según 3) Delete de producto con confirmación (reusar `ConfirmModal`).

## Evidencia / commits

- Tareas 1–2 — commit `6f745d9` *fix(frontend): correct two inherited defects in the admin panel*: test-first con RED observado (los dos casos de logout fallaban por `savedUser` borrado; el caso de vista fallaba con el botón presente en dashboard). `npm test` → 6 archivos, **23/23**. `frontend/tests/admin-panel.test.tsx` nuevo: logout con y sin slug (borra exactamente las 5 claves y respeta una clave ajena `theme`), y "+ Nuevo" ausente en dashboard/products/combos y presente en categories abriendo el modal.
- Tarea 3 — decisión del usuario: implementar `GET /api/products`, `PUT /api/products/{id}/activar`, `DELETE /api/products/{id}` y `GET /api/combos`, autenticados y scopeados por el negocio del JWT (no se cuelgan de `/api/restaurants/**`, que hoy es `permitAll` y enumerable).
