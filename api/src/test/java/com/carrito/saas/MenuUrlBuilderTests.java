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
}
