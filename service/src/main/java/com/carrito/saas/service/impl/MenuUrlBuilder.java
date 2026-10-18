package com.carrito.saas.service.impl;

import org.springframework.stereotype.Service;

import com.carrito.saas.service.config.QrProperties;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;

/**
 * The SINGLE place in the codebase that builds a customer menu URL:
 * {@code <base>/menu/}.
 *
 * <p>The project no longer carries the business slug in the customer URL: the
 * deployment hosts exactly one business, so the menu resolves it server-side
 * ({@code GET /api/menu}, {@code POST /api/orders/menu}) and the anonymous
 * security rules permit {@code /menu/**}. The {@code slug} argument is kept
 * for signature compatibility with the QR/table templates but is no longer
 * encoded into the URL.</p>
 */
@Service
public class MenuUrlBuilder implements IMenuUrlBuilder {

	private final QrProperties qrProperties;

	public MenuUrlBuilder(QrProperties qrProperties) {
		this.qrProperties = qrProperties;
	}

	@Override
	public String buildMenuUrl(String slug) {

		String base = qrProperties.getPublicMenuUrl();

		// A trailing slash on the configured origin would produce a double one.
		String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;

		// Slugless contract: the printed QR points at a plain /menu/ and the
		// single business is resolved by the backend, never by the URL.
		return normalizedBase + "/menu/";
	}
}
