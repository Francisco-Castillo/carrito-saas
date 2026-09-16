package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.carrito.saas.service.config.QrProperties;
import com.carrito.saas.service.impl.MenuUrlBuilder;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;

/**
 * Unit contract of the SINGLE place that builds a customer menu URL:
 * {@code <base>/menu/index.html?restaurant=<slug>}.
 *
 * <p>This URL shape is the contract between the printed QR and the static
 * menu page ({@code app.js} reads {@code ?restaurant=<slug>}); nothing else in
 * the codebase may assemble it.</p>
 */
class MenuUrlBuilderTests {

	@Test
	void buildsThePublicMenuUrlFromTheDefaultLocalBase() {

		QrProperties properties = new QrProperties();
		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant"))
				.isEqualTo("http://localhost:9090/menu/index.html?restaurant=my-restaurant");
	}

	@Test
	void buildsThePublicMenuUrlFromAConfiguredBase() {

		QrProperties properties = new QrProperties();
		properties.setPublicMenuUrl("https://carta.midominio.com");

		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant"))
				.isEqualTo("https://carta.midominio.com/menu/index.html?restaurant=my-restaurant");
	}

	@Test
	void toleratesATrailingSlashOnTheConfiguredBase() {

		QrProperties properties = new QrProperties();
		properties.setPublicMenuUrl("https://carta.midominio.com/");

		IMenuUrlBuilder builder = new MenuUrlBuilder(properties);

		assertThat(builder.buildMenuUrl("my-restaurant"))
				.isEqualTo("https://carta.midominio.com/menu/index.html?restaurant=my-restaurant");
	}
}
