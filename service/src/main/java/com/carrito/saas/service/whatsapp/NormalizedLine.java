package com.carrito.saas.service.whatsapp;

import java.util.Objects;

/**
 * One matched order line produced by {@link TextOrderNormalizer} (T6a of
 * {@code odd/tasks/whatsapp-inbound.md}). Pure data: exactly the fields the
 * {@code OrderItemDTO} needs to become an order line in T7 — a product id XOR
 * a combo id, a quantity — plus the catalog name as matched and the phrase
 * exactly as the customer typed it, for the operator view and the T6b
 * persistence rows.
 *
 * <p>The constructor enforces the XOR: a line carries a product id or a combo
 * id, never both, never neither.</p>
 */
public record NormalizedLine(Long productId, Long comboId, String name, int quantity, String rawPhrase) {

	public NormalizedLine {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(rawPhrase, "rawPhrase");
		if ((productId == null) == (comboId == null)) {
			throw new IllegalArgumentException("a matched line carries exactly one of productId or comboId");
		}
		if (quantity < 1) {
			throw new IllegalArgumentException("quantity must be >= 1");
		}
	}
}
