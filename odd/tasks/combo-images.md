# Feature: combo-images

Imagen propia para los combos: alta/edición/borrado desde el panel (mismo flujo que los productos) y preferencia en el menú público — el combo muestra SU imagen si tiene, y si no, deriva la del componente más caro con imagen (comportamiento actual preservado).

## Specs

- **S1** — Pedido del usuario, verbatim: «de paso es algo que tambien tenemos que hacer, poder agregar una imagen cuando se crea/edita un combo».
- **S2** — Contexto que motivó el feature: el usuario subió una imagen (15:18, e2e-admin) esperando que el combo «Combo Clasica + Bebida» la mostrara; el upload fue para la Pizza Napolitana (otro producto) y el combo siguió mostrando la imagen derivada de la Clásica. Con imagen propia del combo, la dependencia de adivinar el componente desaparece.

## Estado del código verificado

- `Combo` entity: id, name, price, category, items, active — **sin imagen**.
- `ComboCreateDTO`/`ComboDTO`: sin imageUrl.
- El menú público deriva la imagen en el frontend (`mapCombosToProducts`, Menu.tsx): componente más caro con imagen (`0ff8a09`), con `normalizeName` para el match.
- El patrón a replicar existe para productos: `POST/DELETE /api/products/{id}/image` → `ImageStorageService.store/deleteByUrl` → `image_url` en la fila; serving público por `/api/images/**` (UploadedImagesConfig + permitAll en SecurityConfig).
- El flujo del ProductModal: se guarda el producto primero, la imagen viaja después (subir/borrar); un fallo de imagen no revierte el guardado.

## Tasks

- **T1** (S1) Backend — imagen en el modelo y endpoints: `Combo.image_url` (ddl-auto=update la crea), `imageUrl` en `ComboDTO`/`ComboCreateDTO`, `POST/DELETE /api/combos/{id}/image` (tenant check: combo ajeno → 404; tipo/tamaño → 400 español, igual que productos), storage bajo `uploads/combos/` servido por `/api/images/combos/**` (UploadedImagesConfig + permit ya cubre `/api/images/**`). RED-first: endpoint + payload del menú.
- **T2** (S1) Backend — el menú sirve la imagen del combo: `MenuServiceImpl`/`IComboMapper` exponen `imageUrl` del combo en el payload público.
- **T3** (S1) Frontend — ComboModal: picker con preview/cambiar/quitar (mismo patrón del ProductModal: guardar primero, imagen después). RED-first del contrato del picker.
- **T4** (S1) Frontend — `mapCombosToProducts`: si el payload del combo trae `imageUrl`, manda; si no, deriva (fallback actual). RED-first con payload con y sin imagen propia.
- **T5** Verificación: mvn enfocado + vitest + ensayo en vivo (subir imagen de combo, verla en el menú; quitarla y ver el fallback).

## Log

- **L1** — «de paso es algo que tambien tenemos que hacer, poder agregar una imagen cuando se crea/edita un combo» (2026-10-19, tras el diagnóstico de la imagen del combo: el upload del usuario fue a la Pizza Napolitana y el combo deriva la imagen de la Clásica).

## Progress

| Task | Estado | Evidencia |
| --- | --- | --- |
| T1 | hecho | `7b55aec` en develop: Combo.image_url + POST/DELETE /api/combos/{id}/image + serving /api/images/combos/**. RED: 3 fallos (200 vs 404 en el endpoint ausente) → GREEN 5/5; preservación 23/23 (product-image + CRUD de combos) |
| T2 | hecho | mismo commit: el payload del menú lleva imageUrl del combo vía IComboMapper (verificado en el ensayo en vivo) |
| T3 | hecho | `8330623`: picker en ComboModal (preview/Cambiar/Quitar/errores inline) con upload DESPUÉS del guardado; RED: 3 fallos → GREEN |
| T4 | hecho | mismo commit: mapCombosToProducts prefiere la imagen propia, fallback derivado; RED: el caso imagen-propia fallaba → GREEN; el caso fallback ya pasaba (preservación) |
| T5 | hecho | suite 284/284 + tsc; ensayo en vivo: upload 200 → payload del menú sirve /api/images/combos/a4ea77c4...png → DELETE 200 → payload imageUrl null (fallback). Limpieza: DELETE borra el archivo de disco |
