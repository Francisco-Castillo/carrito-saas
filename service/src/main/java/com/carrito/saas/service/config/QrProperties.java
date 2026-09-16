package com.carrito.saas.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the public QR entry point.
 *
 * <p>This completes the previously abandoned intent (the commented-out
 * {@code QrProperties} fields in {@code TableServiceImpl} and
 * {@code OpenPdfQrPdfServiceImpl}): the public base URL the printed QR must
 * point at is configurable, not hardcoded.</p>
 *
 * <p>The value is wired in {@code application.yml} as
 * {@code qr.public-menu-url: ${QR_PUBLIC_MENU_URL:http://localhost:9090}}, so it
 * can be changed per environment without recompiling.</p>
 */
@Component
@ConfigurationProperties(prefix = "qr")
public class QrProperties {

	/**
	 * Public origin of the menu, e.g. {@code https://carta.midominio.com}. The
	 * default only serves local development: for a QR to work off-device this
	 * must be the real public origin, set through {@code QR_PUBLIC_MENU_URL}.
	 */
	private String publicMenuUrl = "http://localhost:9090";

	public String getPublicMenuUrl() {
		return publicMenuUrl;
	}

	public void setPublicMenuUrl(String publicMenuUrl) {
		this.publicMenuUrl = publicMenuUrl;
	}
}
