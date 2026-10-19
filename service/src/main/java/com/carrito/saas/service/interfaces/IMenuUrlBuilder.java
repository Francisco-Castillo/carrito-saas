package com.carrito.saas.service.interfaces;

public interface IMenuUrlBuilder {

	/**
	 * Builds the public menu URL a printed QR must encode:
	 * {@code <base>/menu/index.html?restaurant=<slug>}.
	 *
	 * @param slug the public slug of the business
	 * @return the absolute URL of the anonymous menu page
	 */
	String buildMenuUrl(String slug);
}
