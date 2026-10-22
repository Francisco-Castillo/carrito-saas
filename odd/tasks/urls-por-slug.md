# URLs por slug: el negocio vuelve a la URL

**Estado:** en curso (2026-10-21)
**Objetivo:** Volver a poner el slug del local en las URLs — que el usuario pidió sacar (login orgánico) y reconoce como error de enfoque — con el formato limpio `/{slug}/...` en vez del viejo `/slug:{slug}`.

## Specs

- S1: "mira e2e-admin sigue entrando por url a la plataforma" + "antes se usaba asi /slug:e2e-demo, lo que seria es volver a usarlos pero de esta manera /e2e-demo" — el slug vuelve a la URL como prefijo limpio.
- S2: Decisión del usuario: **todos los paneles de negocio** viven bajo el slug — `/{slug}/admin`, `/{slug}/dashboard`, `/{slug}/kds`, `/{slug}/pedidos`.
- S3: Decisión del usuario: **`/{slug}` a secas muestra la carta pública** del local (el menú que hoy vive en `/menu/{slug}`).
- S4: Decisión del usuario: **las URLs limpias viejas redirigen** a su equivalente con slug (`/admin` → `/{slug}/admin` con el slug de la sesión; sin sesión → login) — los bookmarks no se rompen.
- S5: `/platform` y `/login` no llevan slug (el operador no tiene negocio). `/menu/{slug}` redirige a `/{slug}`.
- S6: El slug del path es canónico en las vistas de negocio: el guard exige que la sesión pertenezca a ese negocio (slug de sesión igual al del path); sin sesión → login; sesión de plataforma → `/platform`; slug distinto → login (la sesión pertenece a otro negocio). Las llamadas de datos de los paneles usan el slug de la sesión (igual que hoy).

## Tasks

- [x] T1 (S2, S3, S5): `view.ts` — `resolveView(pathname)` devuelve `{view, slug, legacy}`: `/{slug}` → carta pública, `/{slug}/admin|dashboard|kds|pedidos` → paneles con slug del path, paths limpios viejos y `/menu/{slug}` marcados legacy; `getView()` queda como wrapper de compat. Tests: view-resolution.test.ts (25) + view-routing actualizado (un slug-shaped suelto ya no es "desconocido": es la carta pública).
- [x] T2 (S4, S6): `guardRedirect(resolution, session)` — slug del path canónico; sesión de otro negocio → login; legacy → equivalente con el slug de sesión; `landingForRole(role, slug)` → `/{slug}/admin|kds|pedidos`, ADMIN → `/platform` (sin slug); respuesta sin slug de negocio → se queda en el login con "Rol no permitido". `sessionRole()` en auth.ts; Menu recibe el slug del path como prop (fallback `?restaurant=` para QRs viejos). Tests: view-guards.test.tsx (18) + login-redirect/login ajustados.
- [x] T3: Backend: `SlugViewController` (`/{slug}` → shell de carta, `/{slug}/{panel:[a-z0-9-]+}` → shell raíz SPA) + SecurityConfig: permitAll de los mismos patrones DESPUÉS de un catch `authenticated` para `/api/**` y `/ws/**` (preserva el 401 anónimo de rutas API desconocidas). Los segmentos reservados los enruta el controller a sus shells (su mapping de orden 0 pisa los literales de SpaRoutingConfig — mismo resultado). Aprendizaje clave: los patterns con lookahead NO matchean en el requestMatcher de Security 7 y MockMvc no ejecuta forwards — asserts de status en tests HTTP, contenido en e2e. Tests: SlugRoutesHttpTests 8/8 + regresión (SpaRouting 21/21, PlatformPrivilege 6/6, HttpErrorContract 7/7, PublicPathErrorStatus 10/10).
- [x] T4: Verificación completa: backend 457 tests 0 fallos (incluye la regresión del 405 corregida), frontend 421/421, build:spring OK, e2e en navegador urls-slug.spec.ts + view-guards.spec.ts 2/2 contra el jar final. Serving de producción verificado por curl: /{slug} 200 (carta), /{slug}/{panel} 200, desconocido 404, /api 401, /platform y /admin 200.

## Cierre

**Feature completa (2026-10-21).** Pendiente fuera de alcance: PR/merge (decisión del usuario).

## Aprendizajes

- A1: El panel de shell dinámico con {panel:.+} colisiona con POST/PUT sobre paths de 2 segmentos no mapeados (405 en vez del 404 pinneado por AdminProductAndComboEndpointTests para POST /api/productos): el shell sólo se sirve para los 4 paneles reales.
- A2: /index.html NO está servida por el resource handler (404 en vivo, verificado) aunque esté en permitAll — la shell de /{slug}/{panel} forwardea a /dashboard/index.html, que sí se sirve; el SPA resuelve vista+slug desde el pathname así que da igual qué entry copia responde.
- A3: Recurrencia de L4 (plataforma-altas): `mvn -pl api package` sin `-am` arma el fat jar con artefactos viejos de ~/.m2 (security) — el serving nuevo funciona y el login del operador da 403. Única vía segura: clean package del reactor o -pl api -am.
- A4: Corrección post-entrega (reporte del usuario: "aparece por /kds y después /demo-e2e/kds"): los links internos del panel y del dashboard apuntaban a paths limpios (/kds, /pedidos) — doble recarga con la URL intermedia sin slug visible (el guard los redirige). Arreglado: los links llevan el slug de la sesión (`/{slug}/kds`, `/{slug}/pedidos`) — una sola navegación, sin URL intermedia. De paso: el fixture seedPanelSession mintea un token con exp futura — el token estático viejo era "expirado" para checkAuth() y logout() limpiaba la sesión a mitad del render del Dashboard (el test viejo pasaba por casualidad: el fallback /pedidos matcheaba el estado post-logout). Nota honesta: la recarga completa entre vistas es inherente a la arquitectura (no hay client router); el flash de carga sigue existiendo pero ya nunca muestra una URL sin slug.

## Log

- L1: Solicitud del usuario (2026-10-21), verbatim: "yo cometi el error de pedir que se deje de usar el slug en las urs, el enfoque era equivocado, antes se usaba asi /slug:e2e-demo, lo que seria es volver a usarlos pero de esta manera /e2e-demo".
- L2: Decisiones del usuario (2026-10-21): (a) todos los paneles bajo el slug; (b) /{slug} a secas = carta pública; (c) URLs limpias viejas redirigen.
