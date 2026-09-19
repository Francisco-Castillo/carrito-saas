package com.carrito.saas.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.config.MetaWebhookJsonMapper;
import com.carrito.saas.service.whatsapp.IInboundMessageHandler;
import com.carrito.saas.service.whatsapp.InboundMessage;
import com.carrito.saas.service.whatsapp.MetaInboundMessageTranslator;
import com.carrito.saas.service.whatsapp.MetaWebhookPayload;
import com.carrito.saas.service.whatsapp.WhatsappSignatureVerifier;
import tools.jackson.core.JacksonException;

/**
 * WhatsApp Cloud API inbound webhook: GET subscription handshake and POST
 * message entry point.
 *
 * <p><strong>Origin is the signature, not a session.</strong> The route is
 * deliberately {@code permitAll} in {@code SecurityConfig}; its authorization
 * is the {@code X-Hub-Signature-256} HMAC verified here over the RAW request
 * bytes BEFORE parsing. Parsing first would re-serialize the JSON and lose
 * the exact bytes Meta signed, turning the check into decoration.</p>
 *
 * <p>The payload is parsed with a dedicated {@code SNAKE_CASE} reader because
 * {@link MetaWebhookPayload} is annotation-free: the {@code service} module
 * has no compile-scope Jackson, so the wire-name mapping lives in
 * {@link MetaWebhookJsonMapper}, where Jackson 3 (tools.jackson) is on the
 * compile classpath. Keeping it as an injectable component — instead of
 * building it here — is what lets the contract tests pin the wire names
 * against the production configuration.</p>
 */
@RestController
@RequestMapping("/api/whatsapp")
public class WhatsappWebhookController {

	private static final Logger log = LoggerFactory.getLogger(WhatsappWebhookController.class);

	public static final String EVENT_RECEIVED = "EVENT_RECEIVED";

	/**
	 * Body of the 403 the controller itself answers when the signature does
	 * not verify. A 403 from the Spring Security filter chain comes back with
	 * an empty body, so this body is what distinguishes "rejected by our
	 * authorization check" from "rejected by the filter chain" in tests.
	 */
	public static final String INVALID_SIGNATURE = "invalid signature";

	/**
	 * Body of the 400 for a correctly signed but syntactically invalid body.
	 * Must not be a 500: {@code GlobalExceptionHandler} maps any exception to
	 * 500 and the provider would retry a permanently malformed payload
	 * forever.
	 */
	public static final String INVALID_PAYLOAD = "invalid payload";

	private static final String SUBSCRIBE_MODE = "subscribe";
	private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

	private final WhatsappSignatureVerifier signatureVerifier;
	private final MetaInboundMessageTranslator translator;
	private final ObjectProvider<IInboundMessageHandler> handlers;
	private final MetaWebhookJsonMapper metaWebhookJsonMapper;

	public WhatsappWebhookController(WhatsappSignatureVerifier signatureVerifier,
			MetaInboundMessageTranslator translator,
			ObjectProvider<IInboundMessageHandler> handlers,
			MetaWebhookJsonMapper metaWebhookJsonMapper) {

		this.signatureVerifier = signatureVerifier;
		this.translator = translator;
		this.handlers = handlers;
		this.metaWebhookJsonMapper = metaWebhookJsonMapper;
	}

	/**
	 * One-time subscription handshake Meta calls when (re)registering the
	 * webhook: echo {@code hub.challenge} exactly when
	 * {@code hub.verify_token} matches, 403 otherwise.
	 */
	@GetMapping("/webhook")
	public ResponseEntity<String> verifySubscription(
			@RequestParam(name = "hub.mode", required = false) String mode,
			@RequestParam(name = "hub.verify_token", required = false) String verifyToken,
			@RequestParam(name = "hub.challenge", required = false) String challenge) {

		if (SUBSCRIBE_MODE.equals(mode) && signatureVerifier.verifyVerifyToken(verifyToken)) {
			return ResponseEntity.ok()
					.contentType(MediaType.TEXT_PLAIN)
					.body(challenge == null ? "" : challenge);
		}
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

	/**
	 * Inbound message entry point. Reads the body as raw bytes, verifies the
	 * HMAC over those bytes, and only then parses. A payload that verifies but
	 * is not a text message produces no message and still answers 200.
	 *
	 * <p><strong>HTTP semantics after the message is accepted (T4/T5).</strong>
	 * The hand-off distinguishes two failure kinds: a BUSINESS outcome — the
	 * handler could not resolve the phone or interpret the text — is persisted
	 * by the handler as a {@code FAILED} proposal and answered with 200;
	 * an INFRASTRUCTURE failure — no handler registered (a wiring defect, open
	 * gap 9) or a handler that throws, e.g. the database being unreachable
	 * (open gap 16) — propagates and is answered with 500 so the provider
	 * retries. A 200 over an unpersisted message would lose it without a
	 * trace; a 500 over a message the system can never accept would make the
	 * provider retry forever.</p>
	 */
	@PostMapping("/webhook")
	public ResponseEntity<String> receive(
			@RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
			@RequestBody(required = false) byte[] rawBody) {

		if (!signatureVerifier.verifySignature(rawBody, signature)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(INVALID_SIGNATURE);
		}

		MetaWebhookPayload payload;
		try {
			payload = metaWebhookJsonMapper.readPayload(rawBody);
		} catch (JacksonException malformed) {
			return ResponseEntity.badRequest().body(INVALID_PAYLOAD);
		}

		translator.translate(payload).ifPresent(this::handOff);
		return ResponseEntity.ok().body(EVENT_RECEIVED);
	}

	private void handOff(InboundMessage message) {
		List<IInboundMessageHandler> registered = handlers.orderedStream().toList();
		if (registered.isEmpty()) {
			// Open gap 9: accepting the message with a 200 and discarding it
			// would be success with silent loss. No handler is a wiring defect
			// and must be loud.
			throw new IllegalStateException(
					"No IInboundMessageHandler registered: the inbound message cannot be processed");
		}
		for (IInboundMessageHandler handler : registered) {
			// Open gap 16: a handler failure must NOT be caught into a 200.
			// Infrastructure failures (e.g. the database being unreachable)
			// answer 500 so the provider retries; business outcomes are
			// persisted by the handler and never reach this as an exception.
			handler.handle(message);
		}
	}
}
