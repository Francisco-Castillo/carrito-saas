package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.carrito.saas.service.config.QrProperties;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;

/**
 * Runtime binding contract of {@code QrProperties}.
 *
 * <p>The public QR base URL is NOT known today: it must be configurable with a
 * local default and settable WITHOUT recompiling, through the
 * {@code QR_PUBLIC_MENU_URL} environment variable wired into
 * {@code application.yml} as
 * {@code qr.public-menu-url: ${QR_PUBLIC_MENU_URL:http://localhost:9090}}.</p>
 *
 * <p>This test boots the REAL application context (component scan, YAML,
 * configuration-properties binding) with a NON-default override and asserts the
 * bound value: asserting only the {@code localhost} fallback would also pass if
 * binding were completely broken, which is why the override is mandatory
 * evidence.</p>
 */
@SpringBootTest(properties = "QR_PUBLIC_MENU_URL=http://menu.example.test:8443")
class QrPropertiesBindingTests {

	private static final String NON_DEFAULT_BASE = "http://menu.example.test:8443";

	@Autowired
	private QrProperties qrProperties;

	@Autowired
	private IMenuUrlBuilder menuUrlBuilder;

	@Test
	void nonDefaultPublicMenuUrlIsBoundAtRuntime() {

		assertThat(qrProperties.getPublicMenuUrl())
				.as("the QR_PUBLIC_MENU_URL override must reach QrProperties through the real "
						+ "application context (a fallback-only assertion would not prove binding)")
				.isEqualTo(NON_DEFAULT_BASE);
	}

	@Test
	void menuUrlBuilderUsesTheBoundValue() {

		assertThat(menuUrlBuilder.buildMenuUrl("binding-check-slug"))
				.isEqualTo(NON_DEFAULT_BASE + "/menu/index.html?restaurant=binding-check-slug");
	}
}
