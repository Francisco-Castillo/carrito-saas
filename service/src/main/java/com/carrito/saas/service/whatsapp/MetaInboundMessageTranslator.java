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
								customerName(change.value(), message.from()),
								message.text().body(),
								Instant.now()));
					}
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * The customer display name from {@code contacts[].profile.name}, or
	 * {@code null} when the payload does not carry one. The absence of the
	 * name is an ORDINARY case, never an error: no {@code contacts}, a
	 * contact without {@code profile}, or a profile without {@code name} all
	 * yield {@code null} and none of them throws.
	 *
	 * <p>The contact whose {@code wa_id} matches the message's {@code from}
	 * is preferred; otherwise the first contact is used. A BLANK name is
	 * treated as absent: an empty string is no draft default.</p>
	 */
	private String customerName(MetaWebhookPayload.Value value, String from) {

		if (value == null || value.contacts() == null) {
			return null;
		}
		MetaWebhookPayload.Contact chosen = null;
		for (MetaWebhookPayload.Contact contact : value.contacts()) {
			if (contact == null) {
				continue;
			}
			if (from != null && from.equals(contact.waId())) {
				chosen = contact;
				break;
			}
			if (chosen == null) {
				chosen = contact;
			}
		}
		if (chosen == null || chosen.profile() == null) {
			return null;
		}
		String name = chosen.profile().name();
		return name == null || name.isBlank() ? null : name;
	}

	private boolean isTextMessage(MetaWebhookPayload.Message message) {
		return message != null
				&& TEXT_TYPE.equals(message.type())
				&& message.text() != null
				&& message.text().body() != null
				&& !message.text().body().isBlank();
	}
}
