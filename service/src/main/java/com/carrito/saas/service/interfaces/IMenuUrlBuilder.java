package com.carrito.saas.service.interfaces;

public interface IMenuUrlBuilder {

	/**
	 * Builds the public menu URL a printed TABLE QR must encode:
	 * {@code <base>/menu/?table=<id>}.
	 *
	 * <p>The id lets the customer menu attribute the order to the scanned
	 * table (SALON + tableId), which the backend already validates against
	 * the resolved business.</p>
	 *
	 * @param slug the public slug of the business
	 * @param tableId the id of the printed table
	 * @return the absolute URL of the anonymous menu page carrying the table
	 */
	String buildTableMenuUrl(String slug, Long tableId);

	/**
	 * Builds the public menu URL a printed QR must encode:
	 * {@code <base>/menu/index.html?restaurant=<slug>}.
	 *
	 * @param slug the public slug of the business
	 * @return the absolute URL of the anonymous menu page
	 */
	String buildMenuUrl(String slug);
}
