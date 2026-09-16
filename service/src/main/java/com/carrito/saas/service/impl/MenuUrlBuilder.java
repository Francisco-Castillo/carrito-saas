package com.carrito.saas.service.impl;

import org.springframework.stereotype.Service;

import com.carrito.saas.service.config.QrProperties;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;

/**
 * The SINGLE place in the codebase that builds a customer menu URL:
 * {@code <base>/menu/index.html?restaurant=<slug>}.
 *
 * <p>The static menu page reads the business from the query string
 * ({@code app.js#getRestaurantSlug}), and the anonymous security rules permit
 * {@code /menu/**}, so this URL works for anyone scanning the printed QR.</p>
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

		return normalizedBase + "/menu/index.html?restaurant=" + slug;
	}
}
