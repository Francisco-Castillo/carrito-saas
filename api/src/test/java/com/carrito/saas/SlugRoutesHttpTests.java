package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * HTTP contract of the slug-driven SPA routes (odd/tasks/urls-por-slug.md,
 * S2/S3/S5): the business lives back in the URL with the clean format
 * /{slug}/...
 *
 * <p>The SHELLS are public (an anonymous visitor must see the public menu of
 * a locale, and the panel shells render before the client-side guard
 * decides): {@code /{slug}} forwards the public menu shell, {@code /{slug}/
 * panel} the root SPA shell. The DATA behind the panels stays gated by token
 * and role in its own endpoints — this suite pins only who reaches the
 * shell.</p>
 *
 * <p>MockMvc no ejecuta los forwards (sólo el container real re-despacha),
 * así que las aserciones son de STATUS, igual que el resto de las suites de
 * shells; el contenido real lo cubre el e2e en navegador
 * (frontend/e2e/urls-slug.spec.ts). Mismo estilo que
 * {@code PlatformPrivilegeHttpTests}: la cadena de seguridad real, sin
 * autenticación para las shells.</p>
 */
@SpringBootTest
@Transactional
class SlugRoutesHttpTests {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	@Test
	@DisplayName("root-level static files are NOT shadowed by the slug route (favicon.ico 200)")
	void rootLevelStaticFilesAreServedByTheResourceHandler() throws Exception {

		// BUG 2026-10-22: GET /{slug} matcheaba CUALQUIER segmento único —
		// incluidos ding.mp3, ready.mp3, favicon.svg y favicon.ico — y el
		// controller respondía 404 (el shape del slug rechaza el punto) ANTES
		// de que el resource handler sirviera el archivo. El ding del pedido
		// nuevo dejó de sonar en producción: el <audio> pedía /ding.mp3 y
		// recibía 404 (NotSupportedError en play()). El patrón debe limitarse
		// a slugs reales y dejar caer los paths con extensión al resource
		// handler. favicon.ico está versionado en static/ → sirve en CI.
		mockMvc.perform(get("/favicon.ico"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("anonymous GET /{slug} serves the public menu shell (200)")
	void anonymousSlugServesMenuShell() throws Exception {

		mockMvc.perform(get("/e2e-demo"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("anonymous GET /{slug}/ with trailing slash serves the menu shell too")
	void anonymousSlugWithTrailingSlashServesMenuShell() throws Exception {

		mockMvc.perform(get("/e2e-demo/"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("anonymous GET /{slug}/admin serves the SPA panel shell (200)")
	void anonymousSlugPanelServesSpaShell() throws Exception {

		mockMvc.perform(get("/e2e-demo/admin"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("anonymous GET /{slug}/kds and /{slug}/pedidos serve the SPA shell (200)")
	void anonymousSlugPanelsServeSpaShell() throws Exception {

		mockMvc.perform(get("/e2e-demo/kds"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/e2e-demo/pedidos"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("anonymous GET /{slug}/unknown-panel answers 404 (only the four real panels serve a shell)")
	void anonymousUnknownPanelFallsBackToSpaShell() throws Exception {

		mockMvc.perform(get("/e2e-demo/xyz"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("reserved /api keeps its anonymous 401 (the authenticated catch precedes the slug permitAll)")
	void reservedApiKeepsAnonymousUnauthorized() throws Exception {

		mockMvc.perform(get("/api"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("/platform keeps serving its literal shell (200, unchanged)")
	void platformShellUnchanged() throws Exception {

		mockMvc.perform(get("/platform"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("an uppercase path is not a slug: it keeps the authenticated-only contract (401)")
	void uppercasePathIsNotASlug() throws Exception {

		// Ni el permitAll del slug (sólo minúsculas) ni hoy por hoy: un path
		// que no matchea ninguna regla cae a anyRequest.authenticated → 401
		// anónimo, exactamente como antes del feature.
		mockMvc.perform(get("/E2E-Demo"))
				.andExpect(status().isUnauthorized());
	}
}
