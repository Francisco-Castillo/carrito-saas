package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.carrito.saas.service.config.QrProperties;
import com.carrito.saas.service.impl.MenuUrlBuilder;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;

/**
 * Unit contract of the SINGLE place that builds a customer menu URL:
 * {@code <base>/{slug}}.
 *
 * <p>S3 (odd/tasks/retire-menu-route.md): the business slug IS encoded in the
 * URL — the only public menu address is the business's own {@code /{slug}}
 * (and {@code /{slug}?table=<id>} for a table); the legacy {@code /menu/}
 * slugless contract is retired. Nothing else in the codebase may assemble
 * this URL.</p>
 */
class MenuUrlBuilderTests {

	@Test
	void buildsThePublicMenuUrlFromTheDefaultLocalBase() {

		QrProperties properties = new QrProperties();
		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant"))
				.isEqualTo("http://localhost:9090/my-restaurant");
	}

	@Test
	void buildsThePublicMenuUrlFromAConfiguredBase() {

		QrProperties properties = new QrProperties();
		properties.setPublicMenuUrl("https://carta.midominio.com");

		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant"))
				.isEqualTo("https://carta.midominio.com/my-restaurant");
	}

	@Test
	void toleratesATrailingSlashOnTheConfiguredBase() {

		QrProperties properties = new QrProperties();
		properties.setPublicMenuUrl("https://carta.midominio.com/");

		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant"))
				.isEqualTo("https://carta.midominio.com/my-restaurant");
	}

	@Test
	void buildsTheTableMenuUrlWithTheTableIdOnTheDefaultLocalBase() {

		QrProperties properties = new QrProperties();
		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildTableMenuUrl("my-restaurant", 42L))
				.isEqualTo("http://localhost:9090/my-restaurant?table=42");
	}

	@Test
	void buildsTheTableMenuUrlFromAConfiguredBaseAndToleratesATrailingSlash() {

		QrProperties properties = new QrProperties();
		properties.setPublicMenuUrl("https://carta.midominio.com/");

		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildTableMenuUrl("my-restaurant", 7L))
				.isEqualTo("https://carta.midominio.com/my-restaurant?table=7");
	}

	// ------------------------------------------------------------------
	// QR por sucursal (multi-sucursal, odd/tasks/multi-sucursal.md, S3/T6):
	// la URL pública de UNA sucursal lleva ?sucursal=<id> — id NUMÉRICO,
	// nunca un slug inventado de sucursal.
	// ------------------------------------------------------------------

	@Test
	void buildsTheBranchMenuUrlWithTheSucursalIdOnTheDefaultLocalBase() {

		QrProperties properties = new QrProperties();
		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant", 7L))
				.isEqualTo("http://localhost:9090/my-restaurant?sucursal=7");
	}

	@Test
	void buildsTheBranchMenuUrlFromAConfiguredBaseAndToleratesATrailingSlash() {

		QrProperties properties = new QrProperties();
		properties.setPublicMenuUrl("https://carta.midominio.com/");

		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant", 42L))
				.isEqualTo("https://carta.midominio.com/my-restaurant?sucursal=42");
	}

	@Test
	void appendsTheSucursalWithAmpersandWhenTheBaseAlreadyCarriesAQuery() {

		// El overload delega en buildMenuUrl(slug) y decide el separador según
		// si esa URL ya trae query. Hoy la base {slug} nunca lo trae ("?");
		// la rama "&" queda para una URL combinada futura (p.ej. mesa +
		// sucursal) — se simula acá sobrescribiendo la base, que es exactamente
		// el punto donde el overload lee la query.
		IMenuUrlBuilder builder = new MenuUrlBuilder(new QrProperties()) {
			@Override
			public String buildMenuUrl(String slug) {
				return "https://carta.midominio.com/my-restaurant?table=3";
			}
		};

		assertThat(builder.buildMenuUrl("my-restaurant", 7L))
				.isEqualTo("https://carta.midominio.com/my-restaurant?table=3&sucursal=7");
	}
}
