package com.carrito.saas.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Clean URLs for the SPA entry paths: the app itself redirects to /admin,
 * /dashboard, /kds, /login and /menu, and users type them, so those bare
 * paths must serve the same shell the legacy static files serve. A forward
 * (not a redirect) keeps the clean URL in the address bar.
 *
 * <p>Spring MVC no longer matches the trailing slash implicitly (removed in
 * Spring 6), so both variants are registered. The legacy .html paths keep
 * working unchanged: the printed QR codes point at /menu/index.html and old
 * bookmarks keep resolving.</p>
 *
 * <p>Serving the shell needs no authentication: SecurityConfig permits the
 * same clean paths it already permits for the static files
 * ({@code SpaRoutingHttpTests} pins it).</p>
 */
@Configuration
public class SpaRoutingConfig implements WebMvcConfigurer {

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		forward(registry, "/admin", "/admin/admin.html");
		forward(registry, "/dashboard", "/dashboard/index.html");
		forward(registry, "/kds", "/kds/index.html");
		forward(registry, "/login", "/login/login.html");
		forward(registry, "/menu", "/menu/index.html");
	}

	private void forward(ViewControllerRegistry registry, String cleanPath, String shell) {
		registry.addViewController(cleanPath).setViewName("forward:" + shell);
		registry.addViewController(cleanPath + "/").setViewName("forward:" + shell);
	}
}
