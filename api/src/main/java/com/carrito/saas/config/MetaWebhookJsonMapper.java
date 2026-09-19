package com.carrito.saas.config;

import org.springframework.stereotype.Component;

import com.carrito.saas.service.whatsapp.MetaWebhookPayload;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single place where the Meta webhook wire-name strategy lives.
 *
 * <p>{@link MetaWebhookPayload} is deliberately annotation-free (the
 * {@code service} module has no compile-scope Jackson), so the wire names are
 * derived from the record component names by
 * {@link PropertyNamingStrategies#SNAKE_CASE}. That configuration used to be
 * built inside the webhook controller, which made it unreachable for tests:
 * nothing else could deserialize with the PRODUCTION naming strategy, so a
 * renamed record component changed the wire contract silently. Centralizing
 * the mapper here lets the contract tests deserialize the Meta fixture
 * through exactly the same configuration production uses — see
 * {@code metaPayloadWireNamesArePinnedThroughTheProductionMapper} in
 * {@code WhatsappWebhookContractTests}.</p>
 *
 * <p>Do not duplicate the {@code SNAKE_CASE} setup anywhere else.</p>
 */
@Component
public class MetaWebhookJsonMapper {

	private final JsonMapper mapper = JsonMapper.builder()
			.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	/**
	 * Deserializes a raw webhook body into the payload record tree using the
	 * production wire-name strategy. Throws {@code tools.jackson.core.JacksonException}
	 * (unchecked) for a syntactically invalid body; the controller maps that
	 * to a 400.
	 */
	public MetaWebhookPayload readPayload(byte[] rawBody) {
		return mapper.readerFor(MetaWebhookPayload.class).readValue(rawBody);
	}
}
