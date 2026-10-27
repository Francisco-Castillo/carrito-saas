# retire-menu-route

Jubilación completa de la ruta `/menu` (decisión del usuario, opción 3 de 3): la única URL pública de la carta pasa a ser `/{slug}` del local (y `/{slug}?table=<id>` para mesa). Los QRs ya impresos que apuntan a `/menu/...` dejan de resolver — el usuario aceptó explícitamente esa consecuencia ("solo con un plan de reimpresión coordinado"); la reimpresión es acción del usuario después del deploy.

## Specs

- S1 (decisión del usuario, verbatim): "3" — jubilación completa de `/menu`: quitar rutas legacy y el slugless.
- S2: la carta pública sólo se sirve en `/{slug}` (slug del local) y en `/{slug}?table=<id>` (QR de mesa, odd/tasks/pedido-qr-mesa.md). `/{slug}` sigue sirviendo la misma shell del SPA (hoy hace forward a la copia en `static/menu/index.html`; pasa a servir la shell raíz — misma bytes).
- S3: la generación de URLs de carta (única fuente: `MenuUrlBuilder`) produce `/{slug}` para el QR del local y `/{slug}?table=<id>` para el QR de mesa. `BusinessController.getCurrentBusinessQr` usa esa URL.
- S4: los endpoints públicos slugless se eliminan: `GET /api/menu` (sin slug) y `POST /api/orders/menu` (sin slug), junto con `SingleBusinessResolver`. `GET /api/menu/{slug}` y `POST /api/orders/menu/{slug}` siguen existiendo. ENMIENDA (L5): el endpoint de mesa pasa a ser slug-scoped — `GET /api/menu/{slug}/table/{id}` reemplaza a `GET /api/menu/table/{id}` — porque `getPublicTable` era el último uso del resolver y el scoping por slug preserva el fail-closed de 0 o >1 negocios (opción B de la consulta al worker); el SPA lo consume desde T6 y los QRs de mesa se reimprimen igual que los del local.
- S5: el enrutado de `/menu*` muere: `SpaRoutingConfig` sin el forward de `/menu`; `SlugViewController` trata `menu` como segmento jubilado (404, como infra); no se copia más `static/menu/index.html` (la copia raíz `index.html` es la shell de la carta); `SecurityConfig` sin permitAll de `/menu/**`, `/menu`, `/menu/` ni el POST slugless de pedidos.
- S6: el SPA ya no resuelve `/menu` ni `/menu/{slug}`: sin la ruta legacy de menú en `view.ts` (regex, NO_SLUG_ROUTES, `?view=menu`), sin la rama del guard, y `Menu.tsx`/`orders/api.ts` sin ramas slugless (slug siempre presente; la vista carta sin slug no existe).
- S7: el slug `menu` queda reservado para siempre (no puede existir un local con slug "menu"): en el SPA (ya está en RESERVED_SEGMENTS) y en el backend (404).
- S8 (no-regresión): todo lo que no es la ruta `/menu` queda igual — paneles legacy (`/admin`, `/dashboard`, `/kds`, `/pedidos`), `/login`, `/platform`, `/{slug}/{panel}`, el contrato de errores públicos 4xx, el endpoint de mesa y los paneles del SPA.

Consecuencia operativa (fuera de este slice, acción del usuario): reimprimir TODOS los QRs (local y mesas) después del deploy de esta rama; los viejos dan 404.

## Tasks

| ID | Specs | Ruta | Commit |
| --- | --- | --- | --- |
| T1 | — | este doc + mirror Engram + todo | este commit |
| T2 | S3 | RED-first: MenuUrlBuilderTests, QrPdfTemplateUrlTests, TableQrServiceLinkTests | 347d5ef |
| T3 | S4, S5 (security) | RED-first: baja de slugless + SingleBusinessResolver; ajuste de tests slugless | |
| T4 | S5, S7 (routing) | SpaRoutingConfig, SlugViewController, copy-legacy-entries.mjs; SpaRoutingHttpTests | |
| T5 | S6, S7 (SPA) | RED-first: view.ts + view-routing.test.ts | |
| T6 | S6 | Menu.tsx, orders/api.ts + tests de contrato de carta | |
| T7 | S8 | regresión completa backend+frontend, cierre | |

## Log

- L1 (usuario): "que quedo pendiente ?" + "al final no abri otra sesion solo eres tu" + "dale segui" — sesión única a cargo de los ejes multi-tenant (cerrado: cancel-order-contract) y KDS (cerrado: kds-session-errors, diagnóstico /menu/null, .gitignore).
- L2 (usuario): "perfecto, ya que traes el tema de /menu debo decirte que ahora no lo usamos entramos a la carta desde /slugh del local" — la entrada real de la carta es /{slug}.
- L3 (asistente): tres opciones (solo contexto / migrar generación / jubilación completa). Consecuencia declarada de la 3: rompe QRs ya impresos.
- L4 (usuario): "3" — jubilación completa, con reimpresión de QRs como acción del usuario post-deploy.
- L5 (coordinación): el worker de T3 halló que la premisa "SingleBusinessResolver queda sin usos" era falsa — getPublicTable (GET /api/menu/table/{id}, mantenido por S4) lo usa. Decisión: opción B, endpoint slug-scoped GET /api/menu/{slug}/table/{id}; descartada A (debilita el aislamiento: 200 con identidad de mesa ajena en deployments multi-negocio) y C (conservar el resolver contradice la jubilación). S4 enmendada arriba; T6 actualiza el fetch del SPA; la reimpresión de QRs de mesa ya estaba en el plan.
