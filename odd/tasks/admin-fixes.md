# Admin: fixes de comportamiento y endpoints faltantes

**Estado:** cerrado (2026-10-13)
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
- [x] 4. Frontend admin: mandar el Bearer en productos/combos, toggle con `activo=!active` y delete con `ConfirmModal` (incluye mostrar el 409 legible).
- [x] 5. Backend: `GET /api/products`, `PUT /api/products/{id}/activar`, `DELETE /api/products/{id}` y `GET /api/combos`, autenticados y scopeados por el negocio del JWT, con tests.

## Otros agujeros de seguridad detectados (NO tocados, fuera de alcance)

1. **`PUT /api/{id}` (actualizarProducto) no está scopeado**: cualquier usuario autenticado puede modificar el producto de otro negocio por id crudo, e incluso reapuntarlo a una categoría ajena. `ProductServiceImpl:154-168` usa `findById`.
2. **`POST /api/productos` (crearProducto) no valida dueño de la categoría**: se puede crear un producto dentro de la categoría de otro negocio.
3. **`ComboServiceImpl.crearCombo`** nunca setea `combo.category` (nullable=false en la DB) y resuelve los ids de productos sin chequear dueño: la creación de combos por API hoy fallaría y no está scopeada.
4. **`/api/restaurants/**` es `permitAll`**, así que `GET /api/restaurants/{id}/products` es público y enumerable por id.

## Notas de entorno para los tests del backend

- Los tests corren contra la base configurada, no una aislada. Verificado: `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/carrito_ci mvn -B test` sobre una base descartable → 66 + 6 tests verdes, sin tocar `carrito_db`.
- La suite necesita un JDK 21 (Lombok se rompe con 27). Usar `source ~/.local/opt/jvm-env.sh` (Temurin 21 del usuario); el `openjdk 27` que brew había traído como dependencia de Maven ya fue desinstalado.
- El worker observó un drift de `combos_id_seq` (secuencia por debajo de las filas existentes) que se resolvió solo en la corrida siguiente.

## Evidencia / commits

- Tareas 1–2 — commit `6f745d9` *fix(frontend): correct two inherited defects in the admin panel*: test-first con RED observado (los dos casos de logout fallaban por `savedUser` borrado; el caso de vista fallaba con el botón presente en dashboard). `npm test` → 6 archivos, **23/23**. `frontend/tests/admin-panel.test.tsx` nuevo: logout con y sin slug (borra exactamente las 5 claves y respeta una clave ajena `theme`), y "+ Nuevo" ausente en dashboard/products/combos y presente en categories abriendo el modal.
- Tarea 3 y 5 — commit `cdaa4d0` *feat(api): add the owner product and combo endpoints the admin calls*: `AdminProductAndComboEndpointTests` (9 tests) RED 8/9 fallando contra rutas inexistentes → GREEN 9/9. Suite api completa **66/66** en schema limpio. Verificado en vivo con curl: sin token 403; `GET /api/products` y `GET /api/combos` scopeados y con items; `PUT activar` → 200 con `active:false`; `DELETE` de producto en combo → **409** "El producto está siendo usado por un combo y no puede eliminarse"; `DELETE` libre → 204; inexistente → 404. Los tests semillan dos negocios y verifican que un producto ajeno nunca aparece y que el delete rechazado deja las filas de combo intactas.

## Cierre

- Tarea 4 — commit `dac59b6` *fix(frontend): point the admin product and combo screens at the real endpoints*: 5 tests nuevos escritos primero (RED: sin header de auth, `activo=false` fijo, DELETE sin diálogo, sin mensaje del 409, combos sin token) → GREEN. `npm test` → 7 archivos, **28/28**.
- **Verificación e2e en navegador** (`/tmp/pw/e2e-admin.mjs`, contra el backend real y el negocio semilla `demo-e2e`), 6/6 PASS:
  1. la tabla lista los 7 productos del negocio del JWT (activos y el inactivo) — captura `6-admin-productos.png`;
  2. el toggle activa un producto inactivo (Brownie pasa de Inactivo a Activo): `activo=!active` funciona contra el backend;
  3. el delete pide confirmación y al cancelar con Escape el producto sigue;
  4. borrar un producto que está en un combo → el mensaje del backend (*"El producto está siendo usado por un combo y no puede eliminarse"*) se ve en el toast y `Clasica` sigue en la tabla — captura `7-admin-409.png`;
  5. borrar un producto libre → 204 y la tabla se recarga sin él;
  6. la tabla de combos lista `Combo Clasica + Bebida` con su precio (captura `8-admin-combos.png`).
  Único "problema" detectado por el script: el navegador loguea el 409 como error de red — es el rechazo intencional.
- Datos del e2e restaurados después de correr: seed re-aplicado (re-inserta `Agua mineral`) y `Brownie` devuelto a inactivo. Los datos propios del usuario (`demo-pizzeria`) nunca se tocaron.

### Pendientes que quedaron fuera de alcance (agujeros de seguridad del backend)

Ver la lista en la sección anterior: `PUT /api/{id}` sin scope de negocio, `POST /api/productos` sin validar dueño de la categoría, `ComboServiceImpl.crearCombo` sin categoría ni ownership, y `/api/restaurants/**` público y enumerable. Ninguno se tocó.
