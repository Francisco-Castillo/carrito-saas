package com.carrito.saas.service.whatsapp;

import java.time.Instant;

/**
 * Port for one customer message entering through a messaging channel.
 *
 * <p>This is the boundary that keeps the rest of the system from knowing
 * about Meta: downstream tasks of {@code odd/tasks/whatsapp-inbound.md}
 * (phone resolution, idempotency, proposal persistence, normalization)
 * consume this record, never the provider payload.</p>
 *
 * @param channel    channel identifier, e.g. {@code whatsapp}
 * @param externalId provider message id (the Meta {@code wamid...}); the
 *                   natural idempotency key
 * @param fromPhone  sender phone as the provider sent it, without normalization
 * @param text       message text, intact as the customer typed it
 * @param receivedAt instant the message was accepted by this system
 */
public record InboundMessage(
		String channel,
		String externalId,
		String fromPhone,
		String text,
		Instant receivedAt) {
}
