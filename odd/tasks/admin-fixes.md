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
- [ ] 4. Frontend admin: mandar el Bearer en productos/combos, toggle con `activo=!active` y delete con `ConfirmModal` (incluye mostrar el 409 legible). EN CURSO.
- [x] 5. Backend: `GET /api/products`, `PUT /api/products/{id}/activar`, `DELETE /api/products/{id}` y `GET /api/combos`, autenticados y scopeados por el negocio del JWT, con tests.

## Otros agujeros de seguridad detectados (NO tocados, fuera de alcance)

1. **`PUT /api/{id}` (actualizarProducto) no está scopeado**: cualquier usuario autenticado puede modificar el producto de otro negocio por id crudo, e incluso reapuntarlo a una categoría ajena. `ProductServiceImpl:154-168` usa `findById`.
2. **`POST /api/productos` (crearProducto) no valida dueño de la categoría**: se puede crear un producto dentro de la categoría de otro negocio.
3. **`ComboServiceImpl.crearCombo`** nunca setea `combo.category` (nullable=false en la DB) y resuelve los ids de productos sin chequear dueño: la creación de combos por API hoy fallaría y no está scopeada.
4. **`/api/restaurants/**` es `permitAll`**, así que `GET /api/restaurants/{id}/products` es público y enumerable por id.

## Notas de entorno para los tests del backend

- Los tests corren contra la base **viva** `carrito_db`, no una aislada: con datos semilla cargados chocan con fixtures de ids fijos (p. ej. `TenantScopedQueryContractTests` contra `combos_pkey`). Conviene correrlos sobre schema limpio.
- El JDK por defecto de la máquina es 27 y **rompe Lombok**; la suite necesita `JAVA_HOME` apuntando a JDK 21.
- El worker observó un drift de `combos_id_seq` (secuencia por debajo de las filas existentes) que se resolvió solo en la corrida siguiente.

## Evidencia / commits

- Tareas 1–2 — commit `6f745d9` *fix(frontend): correct two inherited defects in the admin panel*: test-first con RED observado (los dos casos de logout fallaban por `savedUser` borrado; el caso de vista fallaba con el botón presente en dashboard). `npm test` → 6 archivos, **23/23**. `frontend/tests/admin-panel.test.tsx` nuevo: logout con y sin slug (borra exactamente las 5 claves y respeta una clave ajena `theme`), y "+ Nuevo" ausente en dashboard/products/combos y presente en categories abriendo el modal.
- Tarea 3 y 5 — commit `cdaa4d0` *feat(api): add the owner product and combo endpoints the admin calls*: `AdminProductAndComboEndpointTests` (9 tests) RED 8/9 fallando contra rutas inexistentes → GREEN 9/9. Suite api completa **66/66** en schema limpio. Verificado en vivo con curl: sin token 403; `GET /api/products` y `GET /api/combos` scopeados y con items; `PUT activar` → 200 con `active:false`; `DELETE` de producto en combo → **409** "El producto está siendo usado por un combo y no puede eliminarse"; `DELETE` libre → 204; inexistente → 404. Los tests semillan dos negocios y verifican que un producto ajeno nunca aparece y que el delete rechazado deja las filas de combo intactas.
