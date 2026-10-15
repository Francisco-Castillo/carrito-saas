# Panel de administración: rediseño y CRUD

Origen: observación del usuario (2026-10-15) sobre el panel real, en el dev server:

> en sí toda esta page está fea, el sidebar es feo, la tabla de productos también y le falta un
> buscador, el botón de crear un producto y el de editar etc, lo mismo para categorías, y combos

## Inventario medido antes de tocar nada

Navegador + DOM reales, logueado como dueño en `/admin/`:

| Vista | Botones visibles | Inputs (buscador) | Columnas |
|---|---|---|---|
| Dashboard | — | — | — |
| **Productos** | `ON/OFF` y `🗑` por fila | **ninguno** | Nombre, Precio, Stock, Estado, **(sin encabezado)** |
| **Categorías** | `+ Nuevo`, `Desactivar` | **ninguno** | Nombre, Estado, Acciones |
| **Combos** | **ninguno** | **ninguno** | Nombre, Precio |
| Local | turnos, fechas, guardar | varios | — |

No es que estén "feos": **faltan**. No hay crear ni editar productos, y Combos es una tabla de
sólo lectura.

## Qué soporta el backend

| Recurso | Endpoints | Falta |
|---|---|---|
| Productos | listar (scoped), crear, actualizar, borrar, activar/desactivar | — |
| Categorías | listar, crear, actualizar, ordenar | — |
| Combos | listar, crear | **actualizar** → unidad de backend |

## Decisiones tomadas

- **La marca del sidebar**: `Admin.tsx:95` tiene `<h2>SDE</h2>` hardcodeado (y `Login.tsx:282`
  dice "Powered by SDE Group"). En un multi-tenant, el panel de un negocio mostrando el nombre
  del **proveedor** en lugar del **negocio** es un error de producto: el sidebar pasa a mostrar
  el nombre del negocio, que ya se obtiene con `GET /api/business/profile`. Reversible.
- **Crear y editar = modal**, reusando el patrón que ya existe (`CreateCategoryModal`,
  `ConfirmModal`).
- **Buscador = filtrado en cliente** sobre la lista ya cargada. La API no tiene parámetro de
  búsqueda: queda anotado que un catálogo grande va a necesitar búsqueda en servidor.
- **El título de la vista**: hoy es `view.toUpperCase()` (`Admin.tsx:119`) sobre la **clave
  interna** del código, así que el panel dice `PRODUCTS` mientras el sidebar dice `Productos`.
  Pasa a ser el label en español.
- **El lenguaje visual no cambia** (aurora + vidrio + acento índigo→violeta): el problema es la
  ejecución del panel, no el estilo. Los emoji del nav se reemplazan por iconos **SVG inline**
  (sin dependencias nuevas ni CDN).
- **Aviso para el que toque tablas**: el CSS de tablas es compartido y esto ya nos mordió — una
  regla anclada a una sola pantalla dejó al panel sin espaciado (PR #37). Cualquier cambio tiene
  que verificar las OTRAS pantallas que usan tablas (dashboard, KDS, carta).

## Unidades

1. **Shell y sidebar** + piel compartida de tabla (`feat/admin-shell`).
2. **Productos**: buscador, crear, editar, encabezado de acciones (`feat/admin-products-crud`).
3. **Categorías**: buscador y editar.
4. **Combos, backend**: `PUT` con validación y tests.
5. **Combos, panel**: buscador, crear, editar.

## Verificación

- Capturas antes/después de cada vista. Las de "antes" de este inventario están en
  `/tmp/e2e-menu/70-admin-*.png`.
- Medición de layout con el arnés de e2e (`admin-spacing.mjs`), porque ningún test unitario ve
  layout.
- Suites: frontend 134/134, backend 225/225.
- Regresión explícita en las otras pantallas que usan tablas.
