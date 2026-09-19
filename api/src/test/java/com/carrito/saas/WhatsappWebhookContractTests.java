package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.api.WhatsappWebhookController;
import com.carrito.saas.config.MetaWebhookJsonMapper;
import com.carrito.saas.service.config.WhatsappProperties;
import com.carrito.saas.service.whatsapp.IInboundMessageHandler;
import com.carrito.saas.service.whatsapp.InboundMessage;
import com.carrito.saas.service.whatsapp.MetaInboundMessageTranslator;
import com.carrito.saas.service.whatsapp.MetaWebhookPayload;

/**
 * Contract tests for the WhatsApp Cloud API inbound webhook — T1 (channel
 * contract) and T2 (explicit security rule) of
 * {@code odd/tasks/whatsapp-inbound.md}.
 *
 * <p>Every request here is anonymous: no Authorization header, no session.
 * The known app secret and verify token are seeded through test properties,
 * and the expected HMAC is computed in the test with the JDK directly — NOT
 * with the production verifier — so the happy path proves the algorithm, not
 * that the code agrees with itself.</p>
 *
 * <p>The recording {@link IInboundMessageHandler} is injected with
 * {@code @MockitoBean} (org.springframework.test.context.bean.override.mockito),
 * the bean-override annotation this Spring Boot version provides; it replaces
 * the production {@code PersistingInboundMessageHandler} (T5) so these
 * channel-contract tests observe the hand-off boundary in isolation,
 * without touching the database.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy}: {@code @AutoConfigureMockMvc} is not available in
 * this Spring Boot version's test starter (see
 * {@code AnonymousMenuOrderSecurityTests}).</p>
 */
@SpringBootTest(properties = {
		"whatsapp.app-secret=test-app-secret",
		"whatsapp.verify-token=test-verify-token"
})
class WhatsappWebhookContractTests {

	private static final String APP_SECRET = "test-app-secret";
	private static final String WRONG_APP_SECRET = "attacker-secret";
	private static final String VERIFY_TOKEN = "test-verify-token";
	private static final String CHALLENGE = "1158201444";
	private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

	/**
	 * Fixture for the Meta Cloud API "messages" webhook payload. Keep it
	 * BYTE-IDENTICAL to the {@code PAYLOAD} constant in
	 * {@code odd/tasks/tools/whatsapp-simulator.mjs} — the simulator signs
	 * and posts exactly these bytes, so both exercise the same verification
	 * path. The two literals can be compared with:
	 * {@code node odd/tasks/tools/whatsapp-simulator.mjs --print-payload}
	 * against this text block (both reproduce the same byte sequence).
	 */
	private static final String META_TEXT_MESSAGE_PAYLOAD = """
			{
			  "object": "whatsapp_business_account",
			  "entry": [
			    {
			      "id": "1111111111",
			      "changes": [
			        {
			          "field": "messages",
			          "value": {
			            "messaging_product": "whatsapp",
			            "metadata": {
			              "display_phone_number": "5491122334455",
			              "phone_number_id": "2222222222"
			            },
			            "contacts": [
			              {
			                "profile": {
			                  "name": "Juan"
			                },
			                "wa_id": "5491122334455"
			              }
			            ],
			            "messages": [
			              {
			                "from": "5491122334455",
			                "id": "wamid.HBg...",
			                "timestamp": "1726000000",
			                "type": "text",
			                "text": {
			                  "body": "hola quiero 2 milanesas con papas y una coca"
			                }
			              }
			            ]
			          }
			        }
			      ]
			    }
			  ]
			}""";

	/** Non-text message: Out #9 of the feature document says only type text. */
	private static final String META_IMAGE_MESSAGE_PAYLOAD = """
			{
			  "object": "whatsapp_business_account",
			  "entry": [
			    {
			      "id": "1111111111",
			      "changes": [
			        {
			          "field": "messages",
			          "value": {
			            "messaging_product": "whatsapp",
			            "messages": [
			              {
			                "from": "5491122334455",
			                "id": "wamid.IMG",
			                "timestamp": "1726000000",
			                "type": "image",
			                "image": { "caption": "hola", "id": "img-1" }
			              }
			            ]
			          }
			        }
			      ]
			    }
			  ]
			}""";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private MetaWebhookJsonMapper metaWebhookJsonMapper;

	@MockitoBean
	private IInboundMessageHandler inboundMessageHandler;

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	// ---------------------------------------------------------------- T1

	@Test
	void anonymousGetHandshakeWithCorrectTokenEchoesExactChallenge() throws Exception {

		mockMvc.perform(get("/api/whatsapp/webhook")
						.queryParam("hub.mode", "subscribe")
						.queryParam("hub.verify_token", VERIFY_TOKEN)
						.queryParam("hub.challenge", CHALLENGE))
				.andExpect(status().isOk())
				.andExpect(content().string(CHALLENGE))
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
	}

	@Test
	void handshakeWithWrongOrMissingVerifyTokenIsRejected() throws Exception {

		mockMvc.perform(get("/api/whatsapp/webhook")
						.queryParam("hub.mode", "subscribe")
						.queryParam("hub.verify_token", "wrong-token")
						.queryParam("hub.challenge", CHALLENGE))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/api/whatsapp/webhook")
						.queryParam("hub.mode", "subscribe")
						.queryParam("hub.challenge", CHALLENGE))
				.andExpect(status().isForbidden());
	}

	@Test
	void wellSignedMetaTextMessageProducesInboundMessageWithIntactText() throws Exception {

		byte[] body = META_TEXT_MESSAGE_PAYLOAD.getBytes(StandardCharsets.UTF_8);

		mockMvc.perform(post("/api/whatsapp/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header(SIGNATURE_HEADER, sha256Signature(APP_SECRET, body))
						.content(body))
				.andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));

		ArgumentCaptor<InboundMessage> captor = ArgumentCaptor.forClass(InboundMessage.class);
		verify(inboundMessageHandler).handle(captor.capture());

		InboundMessage message = captor.getValue();
		assertThat(message.channel()).isEqualTo("whatsapp");
		assertThat(message.externalId()).isEqualTo("wamid.HBg...");
		assertThat(message.fromPhone()).isEqualTo("5491122334455");
		assertThat(message.text()).isEqualTo("hola quiero 2 milanesas con papas y una coca");
		assertThat(message.receivedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
	}

	@Test
	void badlySignedPayloadIsRejectedWithoutProducingMessage() throws Exception {

		byte[] body = META_TEXT_MESSAGE_PAYLOAD.getBytes(StandardCharsets.UTF_8);

		// Signed with a different secret: same payload, wrong origin.
		mockMvc.perform(post("/api/whatsapp/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header(SIGNATURE_HEADER, sha256Signature(WRONG_APP_SECRET, body))
						.content(body))
				.andExpect(status().isForbidden())
				.andExpect(content().string(WhatsappWebhookController.INVALID_SIGNATURE));

		verifyNoInteractions(inboundMessageHandler);
	}

	@Test
	void payloadWithWrongObjectProducesNoMessageButResponds200() throws Exception {

		String wrongObject = META_TEXT_MESSAGE_PAYLOAD.replace("whatsapp_business_account", "instagram");
		byte[] body = wrongObject.getBytes(StandardCharsets.UTF_8);

		mockMvc.perform(post("/api/whatsapp/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header(SIGNATURE_HEADER, sha256Signature(APP_SECRET, body))
						.content(body))
				.andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));

		verifyNoInteractions(inboundMessageHandler);
	}

	@Test
	void changeWithFieldOtherThanMessagesProducesNoMessageButResponds200() throws Exception {

		String wrongField = META_TEXT_MESSAGE_PAYLOAD.replace("\"field\": \"messages\"", "\"field\": \"account_update\"");
		byte[] body = wrongField.getBytes(StandardCharsets.UTF_8);

		mockMvc.perform(post("/api/whatsapp/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header(SIGNATURE_HEADER, sha256Signature(APP_SECRET, body))
						.content(body))
				.andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));

		verifyNoInteractions(inboundMessageHandler);
	}

	@Test
	void nonTextMessageTypeProducesNoMessageButResponds200() throws Exception {

		byte[] body = META_IMAGE_MESSAGE_PAYLOAD.getBytes(StandardCharsets.UTF_8);

		mockMvc.perform(post("/api/whatsapp/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header(SIGNATURE_HEADER, sha256Signature(APP_SECRET, body))
						.content(body))
				.andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));

		verifyNoInteractions(inboundMessageHandler);
	}

	@Test
	void correctlySignedButMalformedJsonIsRejectedWith400WithoutProducingMessage() throws Exception {

		// A 500 here would make the provider retry a permanently broken body:
		// GlobalExceptionHandler maps every exception to 500, so the controller
		// must answer 400 itself.
		byte[] body = "not json at all".getBytes(StandardCharsets.UTF_8);

		mockMvc.perform(post("/api/whatsapp/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header(SIGNATURE_HEADER, sha256Signature(APP_SECRET, body))
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(WhatsappWebhookController.INVALID_PAYLOAD));

		verifyNoInteractions(inboundMessageHandler);
	}

	// ---------------------------------------------------------------- T2

	@Test
	void anonymousPostWithoutSignatureIsRejectedByTheControllerNotByTheFilterChain() throws Exception {

		byte[] body = META_TEXT_MESSAGE_PAYLOAD.getBytes(StandardCharsets.UTF_8);

		// No X-Hub-Signature-256 header. The route is permitAll in the security
		// filter chain (T2's explicit rule), so a 403 here can only come from
		// the controller's own signature check — a filter-chain rejection would
		// carry an empty body, ours carries INVALID_SIGNATURE. Authorization for
		// this route is the signature, not a session.
		mockMvc.perform(post("/api/whatsapp/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isForbidden())
				.andExpect(content().string(WhatsappWebhookController.INVALID_SIGNATURE));

		verifyNoInteractions(inboundMessageHandler);
	}

	// ------------------------------------------------------------------

	/**
	 * F1 of the independent verification: a blank credential must be refused
	 * at startup, not discovered as a 500 on the first signed POST. These are
	 * plain unit tests over {@link WhatsappProperties#validate()} — the
	 * failure is asserted on the method directly, NOT by expecting a
	 * {@code @SpringBootTest} context to fail to boot.
	 */
	@Test
	void blankAppSecretIsRefusedByConfigurationValidation() {

		WhatsappProperties properties = new WhatsappProperties();
		properties.setAppSecret("   ");
		properties.setVerifyToken("some-token");

		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("whatsapp.app-secret");
	}

	@Test
	void blankVerifyTokenIsRefusedByConfigurationValidation() {

		WhatsappProperties properties = new WhatsappProperties();
		properties.setAppSecret("some-secret");
		properties.setVerifyToken("");

		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("whatsapp.verify-token");
	}

	@Test
	void nullCredentialIsRefusedByConfigurationValidation() {

		WhatsappProperties properties = new WhatsappProperties();
		properties.setAppSecret(null);
		properties.setVerifyToken("some-token");

		assertThatThrownBy(properties::validate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("whatsapp.app-secret");
	}

	@Test
	void populatedConfigurationPassesValidation() {

		WhatsappProperties properties = new WhatsappProperties();
		properties.setAppSecret("some-secret");
		properties.setVerifyToken("some-token");

		assertThatCode(properties::validate).doesNotThrowAnyException();
	}

	// ---------------------------------------------------------------- F2

	/**
	 * F2 of the independent verification: the security rule must cover ONLY
	 * the webhook path, not the whole {@code /api/whatsapp/**} prefix. A
	 * test-only endpoint registered under that prefix (but NOT the webhook)
	 * must stay anonymous-rejected; if anyone re-widens the rule to the
	 * prefix, this probe turns silently anonymous and this test fails.
	 */
	@TestConfiguration
	static class NonWebhookProbeConfig {

		@RestController
		static class NonWebhookProbeController {

			@GetMapping("/api/whatsapp/not-the-webhook")
			public String probe() {
				return "PROBE-REACHED";
			}
		}
	}

	@Test
	void nonWebhookEndpointUnderThePrefixStaysAnonymousRejected() throws Exception {

		// The narrowing must not break the webhook itself.
		mockMvc.perform(get("/api/whatsapp/webhook")
					.queryParam("hub.mode", "subscribe")
					.queryParam("hub.verify_token", VERIFY_TOKEN)
					.queryParam("hub.challenge", CHALLENGE))
			.andExpect(status().isOk())
			.andExpect(content().string(CHALLENGE));

		// ...but anything else under /api/whatsapp/ must NOT inherit the
		// permitAll. Anonymous means no signature is even possible, so a 403
		// here is the only safe answer.
		mockMvc.perform(get("/api/whatsapp/not-the-webhook"))
				.andExpect(status().isForbidden())
				.andExpect(content().string(""));

		mockMvc.perform(post("/api/whatsapp/not-the-webhook")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(content().string(""));
	}

	// ------------------------------------------------------------------ F4

	/**
	 * F4 of the independent verification: with an annotation-free payload
	 * record tree, the wire name of every component is derived from its name
	 * by {@code SNAKE_CASE} in {@link MetaWebhookJsonMapper}, so renaming a
	 * component is a silent wire-contract change. This test deserializes the
	 * Meta fixture through the PRODUCTION mapper (injected, not rebuilt here:
	 * the SNAKE_CASE configuration must live in exactly one place) and pins
	 * every wire field the payload carries.
	 *
	 * <p>Honest scope: this catches OUR typos and renames. It does NOT
	 * validate that our transcription of Meta's contract is correct (open gap
	 * 19 of the feature document) — if the real payload differs, this test is
	 * consistently wrong with the code.</p>
	 */
	@Test
	void metaPayloadWireNamesArePinnedThroughTheProductionMapper() {

		MetaWebhookPayload payload = metaWebhookJsonMapper
				.readPayload(META_TEXT_MESSAGE_PAYLOAD.getBytes(StandardCharsets.UTF_8));

		assertThat(payload.object()).isEqualTo("whatsapp_business_account");

		assertThat(payload.entry()).hasSize(1);
		MetaWebhookPayload.Entry entry = payload.entry().get(0);
		// entry[].id wire name
		assertThat(entry.id()).isEqualTo("1111111111");

		assertThat(entry.changes()).hasSize(1);
		MetaWebhookPayload.Change change = entry.changes().get(0);
		// entry[].changes[].field wire name
		assertThat(change.field()).isEqualTo("messages");

		MetaWebhookPayload.Value value = change.value();
		// messaging_product wire name
		assertThat(value.messagingProduct()).isEqualTo("whatsapp");

		// metadata.display_phone_number / metadata.phone_number_id wire names
		assertThat(value.metadata().displayPhoneNumber()).isEqualTo("5491122334455");
		assertThat(value.metadata().phoneNumberId()).isEqualTo("2222222222");

		assertThat(value.contacts()).hasSize(1);
		// contacts[].wa_id and contacts[].profile.name wire names
		assertThat(value.contacts().get(0).waId()).isEqualTo("5491122334455");
		assertThat(value.contacts().get(0).profile().name()).isEqualTo("Juan");

		assertThat(value.messages()).hasSize(1);
		MetaWebhookPayload.Message message = value.messages().get(0);
		// messages[].id / from / timestamp / type / text.body wire names
		assertThat(message.id()).isEqualTo("wamid.HBg...");
		assertThat(message.from()).isEqualTo("5491122334455");
		assertThat(message.timestamp()).isEqualTo("1726000000");
		assertThat(message.type()).isEqualTo("text");
		assertThat(message.text().body())
				.isEqualTo("hola quiero 2 milanesas con papas y una coca");
	}

	// ------------------------------------------------------------------

	// ------------------------------------------------------------------ T6c

	/**
	 * T6c: the customer display name Meta sends in
	 * {@code contacts[].profile.name} travels through the port. The full
	 * signed-POST flow must hand the handler an {@link InboundMessage}
	 * carrying it.
	 */
	@Test
	void wellSignedMetaTextMessageCarriesTheCustomerProfileNameToThePort() throws Exception {

		byte[] body = META_TEXT_MESSAGE_PAYLOAD.getBytes(StandardCharsets.UTF_8);

		mockMvc.perform(post("/api/whatsapp/webhook")
					.contentType(MediaType.APPLICATION_JSON)
					.header(SIGNATURE_HEADER, sha256Signature(APP_SECRET, body))
					.content(body))
				.andExpect(status().isOk());

		ArgumentCaptor<InboundMessage> captor = ArgumentCaptor.forClass(InboundMessage.class);
		verify(inboundMessageHandler).handle(captor.capture());
		assertThat(captor.getValue().customerName()).isEqualTo("Juan");
	}

	/**
	 * T6c: the payload path for the customer name is VERIFIED, not assumed —
	 * {@code MetaWebhookPayload.Value} carries {@code contacts[]}, each
	 * {@code Contact} carries a {@code Profile}, and {@code Profile} carries
	 * {@code name}; the F4 wire-name test above pins exactly those wire names
	 * against this same fixture through the production mapper.
	 *
	 * <p>Not every payload carries a name: a notification without
	 * {@code contacts} must translate to a NULL customer name and must NOT
	 * throw — that is the ordinary case in production.</p>
	 */
	@Test
	void payloadWithoutContactsLeavesTheCustomerNameNull() {

		MetaWebhookPayload payload = metaWebhookJsonMapper
				.readPayload(minimalMessagesPayload("").getBytes(StandardCharsets.UTF_8));

		InboundMessage message = new MetaInboundMessageTranslator().translate(payload).orElseThrow();
		assertThat(message.customerName()).isNull();
	}

	/** A contact without {@code profile} also leaves the name NULL. */
	@Test
	void payloadWithoutProfileLeavesTheCustomerNameNull() {

		String contacts = "\"contacts\": [ { \"wa_id\": \"5491122334455\" } ],";
		MetaWebhookPayload payload = metaWebhookJsonMapper
				.readPayload(minimalMessagesPayload(contacts).getBytes(StandardCharsets.UTF_8));

		InboundMessage message = new MetaInboundMessageTranslator().translate(payload).orElseThrow();
		assertThat(message.customerName()).isNull();
	}

	/** A profile without {@code name} also leaves the name NULL. */
	@Test
	void payloadWithoutProfileNameLeavesTheCustomerNameNull() {

		String contacts = "\"contacts\": [ { \"profile\": {}, \"wa_id\": \"5491122334455\" } ],";
		MetaWebhookPayload payload = metaWebhookJsonMapper
				.readPayload(minimalMessagesPayload(contacts).getBytes(StandardCharsets.UTF_8));

		InboundMessage message = new MetaInboundMessageTranslator().translate(payload).orElseThrow();
		assertThat(message.customerName()).isNull();
	}

	/**
	 * A BLANK profile name is treated as absent: an empty string is no draft
	 * default, and "does this payload carry a name" stays a NULL check for
	 * T7. Decision pinned here so it cannot drift silently.
	 */
	@Test
	void payloadWithBlankProfileNameLeavesTheCustomerNameNull() {

		String contacts = "\"contacts\": [ { \"profile\": { \"name\": \"   \" }, "
				+ "\"wa_id\": \"5491122334455\" } ],";
		MetaWebhookPayload payload = metaWebhookJsonMapper
				.readPayload(minimalMessagesPayload(contacts).getBytes(StandardCharsets.UTF_8));

		InboundMessage message = new MetaInboundMessageTranslator().translate(payload).orElseThrow();
		assertThat(message.customerName()).isNull();
	}

	// ------------------------------------------------------------------

	/**
	 * Minimal well-formed "messages" notification with the given contacts
	 * block spliced in (possibly empty), so the absence cases above differ
	 * from the fixture in EXACTLY one dimension.
	 */
	private static String minimalMessagesPayload(String contactsBlock) {

		return """
				{
				  "object": "whatsapp_business_account",
				  "entry": [
				    {
				      "changes": [
				        {
				          "field": "messages",
				          "value": {
				            "messaging_product": "whatsapp",
				            %s
				            "messages": [
				              {
				                "from": "5491122334455",
				                "id": "wamid.MIN",
				                "type": "text",
				                "text": { "body": "hola" }
				              }
				            ]
				          }
				        }
				      ]
				    }
				  ]
				}""".formatted(contactsBlock);
	}

	/**
	 * Computes the expected signature in the test with the JDK directly, so
	 * the happy path does not reuse the production signer.
	 */
	private static String sha256Signature(String appSecret, byte[] body) throws Exception {

		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
	}
}
