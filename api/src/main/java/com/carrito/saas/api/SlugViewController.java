package com.carrito.saas.api;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

/**
 * Forwards dinámicos por slug de local (odd/tasks/urls-por-slug.md, S2/S3):
 * el negocio vuelve a vivir en la URL con el formato limpio /{slug}/...
 * - /{slug} a secas → shell de la carta pública del local (S3).
 * - /{slug}/{panel} → shell raíz de la SPA, que resuelve vista + slug desde
 *   el pathname (S2). Un panel desconocido cae al login del SPA — mismo
 *   fallback que en dev.
 *
 * Los segmentos reservados NO van excluidos del patrón (el matcher de
 * seguridad de Spring no soporta lookahead en requestMatchers(String) y este
 * controller, al tener orden 0, PISA los forwards literales de
 * SpaRoutingConfig): el controller los enruta él mismo —
 * - Vistas conocidas (menu/admin/dashboard/kds/pedidos/platform/login) →
 *   forward a su shell, replicando exactamente lo que hacía
 *   SpaRoutingConfig con sus literales.
 * - Infraestructura (api/assets/ws/dev-media/uploads/actuator) → 404, como
 *   hoy (anónimos ya los frena el catch authenticated de SecurityConfig).
 *
 * Seguridad: SecurityConfig da permitAll a /{slug:[a-z0-9-]+} y
 * /{slug:[a-z0-9-]+}/{panel:...} DESPUÉS de un catch authenticated para
 * /api/** y /ws/** — las shells son públicas; los DATOS del negocio siguen
 * gateados por token y rol en sus endpoints.
 */
@Controller
public class SlugViewController {

	/** Vistas conocidas: segmento → shell que servía SpaRoutingConfig. */
	private static final Map<String, String> RESERVED_SHELLS = Map.of(
			"menu", "/menu/index.html",
			"admin", "/admin/admin.html",
			"dashboard", "/dashboard/index.html",
			"kds", "/kds/index.html",
			"pedidos", "/pedidos/index.html",
			"platform", "/platform/index.html",
			"login", "/login/login.html");

	/** Infraestructura: nunca sirve shell — 404 como hoy. */
	private static final Set<String> INFRA = Set.of(
			"api", "assets", "ws", "dev-media", "uploads", "actuator");

	/** El slug va en el PATRÓN del mapping (/{slug:[a-z0-9-]+}), no como
	 * validación post-match: un path con punto (ding.mp3, favicon.svg,
	 * favicon.ico) NUNCA matchea el controller y cae al resource handler de
	 * Spring — antes el controller le ganaba y respondía 404, sombreando
	 * TODO archivo estático de raíz (el ding del pedido nuevo dejó de sonar
	 * en producción: bug 2026-10-22). El check post-match queda para el
	 * caso infra, que igual debe responder 404 y no servir shell. */

	@GetMapping({ "/{slug:[a-z0-9-]+}", "/{slug:[a-z0-9-]+}/" })
	public String slugMenu(@PathVariable("slug") String slug) {
		if (RESERVED_SHELLS.containsKey(slug)) {
			return "forward:" + RESERVED_SHELLS.get(slug);
		}
		if (INFRA.contains(slug)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return "forward:/menu/index.html";
	}

	@GetMapping({ "/{slug:[a-z0-9-]+}/{panel:admin|dashboard|kds|pedidos}",
			"/{slug:[a-z0-9-]+}/{panel:admin|dashboard|kds|pedidos}/" })
	public String slugPanel(@PathVariable("slug") String slug, @PathVariable("panel") String panel) {
		if (RESERVED_SHELLS.containsKey(slug)) {
			return "forward:" + RESERVED_SHELLS.get(slug);
		}
		if (INFRA.contains(slug)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		// El panel SÍ se valida en el patrón: sólo los 4 paneles reales sirven
		// shell — un panel desconocido (o POST/PUT sobre /{slug}/{panel}, p.ej.
		// el camino viejo POST /api/productos) cae al 404 de siempre en vez de
		// un 405 por colisión de método.
		// La shell es la del dashboard (el build copia la misma raíz en cada
		// entry) y el SPA resuelve vista+slug desde el pathname, así que da
		// igual qué entry copia la sirva.
		return "forward:/dashboard/index.html";
	}
}
