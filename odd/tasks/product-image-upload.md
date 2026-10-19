# Product image upload

El cliente (dueño del negocio) puede subir una imagen para cada producto desde
el modal de crear/editar del panel admin. El menú público ya renderiza
`Product.imageUrl`.

## Specs

- S1: "Vamos a permitirle al cliente que pueda subir una imagen de su producto" — el modal de producto (crear y editar) tiene un selector de imagen con vista previa.
- S2: Subida por archivo al servidor (decisión del usuario, no URL ni nube).
- S3: La imagen se sirve públicamente para la carta digital (sin token), como ya lo es `/dev-media/**`.
- S4: Solo el dueño del producto puede subir/cambiar/borrar su imagen (tenant scoping por JWT, igual que el resto del CRUD de productos).
- S5: Validación del lado servidor: solo jpeg/png/webp, tamaño máximo acotado; rechazo con 400 y mensaje en español.
- S6: La imagen es opcional: crear un producto sin imagen sigue funcionando exactamente igual que hoy.

## Tasks

- T1 (S2,S3,S5): Backend — `ImageStorageService`, config `app.uploads.dir`, endpoint `POST /api/products/{id}/image` y `DELETE /api/products/{id}/image`, servir `GET /api/images/**` público, límites multipart en application.yml.
- T2 (S1,S2,S6): Frontend — ProductModal: input de archivo, preview, quitar imagen, subida tras crear/editar, errores inline.
- T3 (S4,S5): Tests backend — seguridad (401 anónimo, 404 producto ajeno), happy path upload/replace/delete, rechazo de tipo inválido.
- T4: Frontend build + tests existentes verdes.

## Log

- L1: "quiero que trabajes en el crud de los productos, ahora mismo al crear un producto no se hay opcion de añadir una imagen para ese producto. Vamos a permitirle al cliente que pueda subir una imagen de su pruducto."
- L2: Decisión de usuario: subida de archivo al servidor (elegido entre archivo/url/nube).
- L3: Verificado: ProductImageEndpointTests 8/8 verdes; tsc + vite build limpios; suite completa del api module con 50 errores PREEXISTENTES (clases que insertan rol OWNER directo contra la BD de dev con seed; verificado idéntico en HEAD limpio con git stash).
