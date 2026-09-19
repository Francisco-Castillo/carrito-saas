package com.carrito.saas.service.whatsapp;

import java.util.List;

/**
 * Shape of the Meta Cloud API inbound webhook payload.
 *
 * <p><strong>Deliberately annotation-free.</strong> The {@code service} module
 * has no compile-scope Jackson (it only reaches the classpath at runtime via
 * jjwt), so the wire-name mapping is NOT expressed with {@code @JsonProperty}
 * here. Instead, the caller that parses this payload (the webhook controller
 * in the {@code api} module, which does have compile-scope Jackson) applies a
 * dedicated {@code PropertyNamingStrategies.SNAKE_CASE} reader over these
 * records. Consequence: every record component name must be the camelCase
 * form of the wire name ({@code messagingProduct} &harr;
 * {@code messaging_product}, {@code phoneNumberId} &harr;
 * {@code phone_number_id}, {@code waId} &harr; {@code wa_id}); renaming a
 * component changes the wire contract and must be reflected in the tests and
 * the simulator.</p>
 *
 * <p>The payload transcription comes from
 * {@code odd/tasks/whatsapp-inbound.md}, which itself notes it was written
 * from memory and must be contrasted with the official Cloud API docs before
 * connecting for real. If Meta differs, only this record tree and the
 * fixture/simulator change — {@link InboundMessage} is the port that absorbs
 * the difference.</p>
 */
public record MetaWebhookPayload(String object, List<Entry> entry) {

	public record Entry(String id, List<Change> changes) {
	}

	public record Change(String field, Value value) {
	}

	public record Value(String messagingProduct, Metadata metadata, List<Contact> contacts,
			List<Message> messages) {
	}

	public record Metadata(String displayPhoneNumber, String phoneNumberId) {
	}

	public record Contact(Profile profile, String waId) {
	}

	public record Profile(String name) {
	}

	public record Message(String from, String id, String timestamp, String type, Text text) {
	}

	public record Text(String body) {
	}
}
