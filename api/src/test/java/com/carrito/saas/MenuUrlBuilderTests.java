package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.carrito.saas.service.config.QrProperties;
import com.carrito.saas.service.impl.MenuUrlBuilder;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;

/**
 * Unit contract of the SINGLE place that builds a customer menu URL:
 * {@code <base>/menu/}.
 *
 * <p>Slugless contract: the deployment hosts exactly one business, the printed
 * QR points at a plain /menu/ and the backend resolves the business itself;
 * nothing else in the codebase may assemble this URL.</p>
 */
class MenuUrlBuilderTests {

	@Test
	void buildsThePublicMenuUrlFromTheDefaultLocalBase() {

		QrProperties properties = new QrProperties();
		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant"))
				.isEqualTo("http://localhost:9090/menu/");
	}

	@Test
	void buildsThePublicMenuUrlFromAConfiguredBase() {

		QrProperties properties = new QrProperties();
		properties.setPublicMenuUrl("https://carta.midominio.com");

		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant"))
				.isEqualTo("https://carta.midominio.com/menu/");
	}

	@Test
	void toleratesATrailingSlashOnTheConfiguredBase() {

		QrProperties properties = new QrProperties();
		properties.setPublicMenuUrl("https://carta.midominio.com/");

		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant"))
				.isEqualTo("https://carta.midominio.com/menu/");
	}

	@Test
	void buildsTheTableMenuUrlWithTheTableIdOnTheDefaultLocalBase() {

		QrProperties properties = new QrProperties();
		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildTableMenuUrl("my-restaurant", 42L))
				.isEqualTo("http://localhost:9090/menu/?table=42");
	}

	@Test
	void buildsTheTableMenuUrlFromAConfiguredBaseAndToleratesATrailingSlash() {

		QrProperties properties = new QrProperties();
		properties.setPublicMenuUrl("https://carta.midominio.com/");

		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildTableMenuUrl("my-restaurant", 7L))
				.isEqualTo("https://carta.midominio.com/menu/?table=7");
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
				.isEqualTo("http://localhost:9090/menu/?sucursal=7");
	}

	@Test
	void buildsTheBranchMenuUrlFromAConfiguredBaseAndToleratesATrailingSlash() {

		QrProperties properties = new QrProperties();
		properties.setPublicMenuUrl("https://carta.midominio.com/");

		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant", 42L))
				.isEqualTo("https://carta.midominio.com/menu/?sucursal=42");
	}

	@Test
	void appendsTheSucursalWithAmpersandWhenTheBaseAlreadyCarriesAQuery() {

		// El overload delega en buildMenuUrl(slug) y decide el separador según
		// si esa URL ya trae query. Hoy la base slugless nunca la trae ("?");
		// la rama "&" queda para una URL combinada futura (p.ej. mesa +
		// sucursal) — se simula acá sobrescribiendo la base, que es exactamente
		// el punto donde el overload lee la query.
		IMenuUrlBuilder builder = new MenuUrlBuilder(new QrProperties()) {
			@Override
			public String buildMenuUrl(String slug) {
				return "https://carta.midominio.com/menu/?table=3";
			}
		};

		assertThat(builder.buildMenuUrl("my-restaurant", 7L))
				.isEqualTo("https://carta.midominio.com/menu/?table=3&sucursal=7");
	}
}
