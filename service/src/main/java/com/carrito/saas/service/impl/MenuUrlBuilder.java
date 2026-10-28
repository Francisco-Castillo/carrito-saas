package com.carrito.saas.service.impl;

import org.springframework.stereotype.Service;

import com.carrito.saas.service.config.QrProperties;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;

/**
 * The SINGLE place in the codebase that builds a customer menu URL:
 * {@code <base>/{slug}}.
 *
 * <p>S3 (odd/tasks/retire-menu-route.md): the public menu is served only at
 * the business's own {@code /{slug}} (and {@code /{slug}?table=<id>} for a
 * table); the legacy slugless {@code /menu/} contract is retired, and the
 * {@code slug} argument IS encoded into the URL.</p>
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

		// S3: the business slug is the public menu address; the legacy /menu/
		// route is retired.
		return normalizedBase + "/" + slug;
	}

	@Override
	public String buildTableMenuUrl(String slug, Long tableId) {

		// Same {slug} base; the table id lets the customer menu attribute
		// the order to the scanned table (SALON + tableId).
		return buildMenuUrl(slug) + "?table=" + tableId;
	}

	@Override
	public String buildMenuUrl(String slug, Long branchId) {

		// Misma base {slug} que el QR del grupo; la sucursal viaja como el
		// parámetro público ?sucursal=<id> (multi-sucursal, S3/T6): id NUMÉRICO,
		// nunca un slug de sucursal inventado. El separador se adapta si la URL
		// ya lleva query (p. ej. una URL combinada ?table= + ?sucursal=).
		String base = buildMenuUrl(slug);
		String separator = base.contains("?") ? "&" : "?";
		return base + separator + "sucursal=" + branchId;
	}
}
