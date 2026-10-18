package com.carrito.saas;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Clean-URL contract of the SPA entry paths: the app itself redirects to
 * /admin, /dashboard, /kds, /login and /menu, so those bare paths (with and
 * without the trailing slash, which Spring MVC no longer matches implicitly)
 * must serve the same shell the legacy static files serve. They are public
 * pages: an anonymous request must render them, not 401.
 *
 * <p>MockMvc assembles the real security {@link FilterChainProxy} (same
 * pattern as {@code PanelErrorStatusHttpTests}). MockMvc does not execute
 * container dispatches, so the clean paths assert the forward to the shell
 * file; the legacy paths assert the real shell bytes through the resource
 * handler. The rendered end-to-end behavior is verified in a browser.</p>
 *
 * <p>The shell is the generated frontend bundle: it only exists after the
 * {@code -Pfrontend} build (CI and the package build always run it). On a
 * fresh checkout without that build there is no SPA to serve, so the tests
 * skip instead of failing.</p>
 */
@SpringBootTest
@EnabledIf("spaEntryPresent")
class SpaRoutingHttpTests {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	private MockMvc mockMvc;

	static boolean spaEntryPresent() {
		return new ClassPathResource("static/admin/admin.html").exists();
	}

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/admin", "/admin/", "/dashboard", "/dashboard/", "/kds", "/kds/", "/login", "/login/",
			"/menu", "/menu/", "/pedidos", "/pedidos/" })
	void cleanPathsForwardToTheLegacyShell(String path) throws Exception {
		mockMvc.perform(get(path))
				.andExpect(status().isOk())
				.andExpect(forwardedUrl(legacyShellFor(path)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/admin/admin.html", "/dashboard/index.html", "/kds/index.html", "/login/login.html",
			"/menu/index.html", "/pedidos/index.html" })
	void legacyPathsServeTheSpaShellAnonymously(String path) throws Exception {
		mockMvc.perform(get(path))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<div id=\"root\">")));
	}

	private static String legacyShellFor(String cleanPath) {
		String base = cleanPath.endsWith("/") ? cleanPath.substring(0, cleanPath.length() - 1) : cleanPath;
		return switch (base) {
			case "/admin" -> "/admin/admin.html";
			case "/dashboard" -> "/dashboard/index.html";
			case "/kds" -> "/kds/index.html";
			case "/login" -> "/login/login.html";
			case "/menu" -> "/menu/index.html";
			case "/pedidos" -> "/pedidos/index.html";
			default -> throw new IllegalArgumentException("unexpected path: " + cleanPath);
		};
	}
}
