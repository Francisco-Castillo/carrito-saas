package com.carrito.saas.service.whatsapp;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Translates a Meta Cloud API webhook payload into the channel-agnostic
 * {@link InboundMessage} port.
 *
 * <p>Translation is deliberately narrow (Out #9 of the feature document):
 * only {@code type: "text"} messages become an {@link InboundMessage}.
 * Everything else — a wrong {@code object}, a {@code changes[].field} other
 * than {@code messages}, a non-text message, or a blank text — yields
 * "nothing to do", which the caller answers with 200 so the provider does
 * not retry.</p>
 *
 * <p>Only the first text message of a notification is translated. Meta sends
 * one message per webhook notification in practice; if a notification ever
 * carries several, the extras are dropped here and the limitation is visible
 * in exactly one place.</p>
 */
@Component
public class MetaInboundMessageTranslator {

	private static final String META_OBJECT = "whatsapp_business_account";
	private static final String MESSAGES_FIELD = "messages";
	private static final String TEXT_TYPE = "text";
	private static final String WHATSAPP_CHANNEL = "whatsapp";

	public Optional<InboundMessage> translate(MetaWebhookPayload payload) {
		if (payload == null || !META_OBJECT.equals(payload.object()) || payload.entry() == null) {
			return Optional.empty();
		}
		for (MetaWebhookPayload.Entry entry : payload.entry()) {
			if (entry == null || entry.changes() == null) {
				continue;
			}
			for (MetaWebhookPayload.Change change : entry.changes()) {
				if (change == null || !MESSAGES_FIELD.equals(change.field())
						|| change.value() == null || change.value().messages() == null) {
					continue;
				}
				for (MetaWebhookPayload.Message message : change.value().messages()) {
					if (isTextMessage(message)) {
						return Optional.of(new InboundMessage(
								WHATSAPP_CHANNEL,
								message.id(),
								message.from(),
								message.text().body(),
								Instant.now()));
					}
				}
			}
		}
		return Optional.empty();
	}

	private boolean isTextMessage(MetaWebhookPayload.Message message) {
		return message != null
				&& TEXT_TYPE.equals(message.type())
				&& message.text() != null
				&& message.text().body() != null
				&& !message.text().body().isBlank();
	}
}
