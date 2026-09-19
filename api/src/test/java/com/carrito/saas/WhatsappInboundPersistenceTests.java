package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.api.WhatsappWebhookController;
import com.carrito.saas.config.MetaWebhookJsonMapper;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.service.whatsapp.IBusinessPhoneResolver;
import com.carrito.saas.service.whatsapp.IInboundMessageHandler;
import com.carrito.saas.service.whatsapp.InboundMessage;
import com.carrito.saas.service.whatsapp.MetaInboundMessageTranslator;
import com.carrito.saas.service.whatsapp.PersistingInboundMessageHandler;
import com.carrito.saas.service.whatsapp.PhoneResolution;
import com.carrito.saas.service.whatsapp.WhatsappSignatureVerifier;

/**
 * T4 (idempotency by Meta's {@code messageId}) + T5 (the
 * {@code OrderProposal} record) of {@code odd/tasks/whatsapp-inbound.md},
 * landing together because the entity is BOTH the inbound record and the
 * idempotency table.
 *
 * <p>The obligations discharged here, from the T1/T2 verification findings:
 * (a) a missing handler is a LOUD failure, not a silent discard with a 200
 * (open gap 9); (b) a handler that throws never ends in 200 — the provider
 * gets a 500 and retries (open gap 16). The two failure kinds are kept
 * apart: "I could not resolve this phone" is a business outcome (a FAILED
 * proposal + 200), "the database is unreachable" is an infrastructure
 * failure (nothing persisted + 500, so Meta retries).</p>
 *
 * <p>MockMvc is assembled manually with the real security filter chain (the
 * repo pattern); every request is anonymous and signed with the seeded app
 * secret via the JDK directly, as in {@code WhatsappWebhookContractTests}.</p>
 */
@SpringBootTest(properties = {
		"whatsapp.app-secret=test-app-secret",
		"whatsapp.verify-token=test-verify-token"
})
@Transactional
class WhatsappInboundPersistenceTests {

	private static final String APP_SECRET = "test-app-secret";
	private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

	/** What Meta sends and the seeded business owns (canonical E.164 digits). */
	private static final String META_SENDER = "5491122334455";

	/** A phone no business owns. */
	private static final String UNKNOWN_SENDER = "5499999999999";

	private static final Long BUSINESS_ID = 987_900_001L;

	/**
	 * Fixture byte-identical to the one in {@code WhatsappWebhookContractTests}
	 * and to the simulator payload.
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

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private MetaWebhookJsonMapper metaWebhookJsonMapper;

	/** The production handler, once T5 registers it. Absent before T5: RED. */
	@Autowired
	private ObjectProvider<IInboundMessageHandler> handlerProvider;

	@Autowired
	private OrderProposalRepository proposalRepository;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private OrderRepository orderRepository;

	/**
	 * Open gap 32: the ONLY {@code SimpMessagingTemplate} in the repo belongs
	 * to the order path ({@code OrderController}); the inbound path must never
	 * broadcast. The mock replaces the real bean so any future broadcast from
	 * the inbound path fails the {@code verifyNoInteractions} pin below
	 * loudly, instead of silently reaching an anonymous WebSocket topic.
	 */
	@MockitoBean
	private SimpMessagingTemplate messagingTemplate;

	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager entityManager;

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	// ------------------------------------------------------------- T4 idempotency

	/**
	 * AC4: the same signed payload POSTed twice produces exactly ONE proposal
	 * and two 200 responses. Meta retries deliveries; a retry must neither
	 * reprocess nor duplicate.
	 */
	@Test
	void sameSignedPayloadPostedTwiceProducesExactlyOneProposalAndTwo200s() throws Exception {

		seedBusinessWithMetaSenderPhone();

		postSigned(META_TEXT_MESSAGE_PAYLOAD).andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));
		postSigned(META_TEXT_MESSAGE_PAYLOAD).andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));

		assertThat(proposalRepository.findByMessageId("wamid.HBg...")).isPresent();
		assertThat(proposalRepository.count()).isEqualTo(1);
	}

	/**
	 * A message WITHOUT a Meta id cannot be deduplicated. Decision pinned
	 * here: it is still RECORDED (never silently lost), with a NULL
	 * message_id — Postgres treats NULLs as distinct in a unique index, so
	 * each arrival is its own row and no synthesized key can collide across
	 * distinct messages. Two POSTs of the id-less payload: two 200s, two rows.
	 */
	@Test
	void messageWithoutIdIsRecordedEveryTimeButNeverDeduplicated() throws Exception {

		seedBusinessWithMetaSenderPhone();

		String idLessPayload = META_TEXT_MESSAGE_PAYLOAD
				.replace("                \"id\": \"wamid.HBg...\",\n", "");

		postSigned(idLessPayload).andExpect(status().isOk());
		postSigned(idLessPayload).andExpect(status().isOk());

		assertThat(proposalRepository.count()).isEqualTo(2);
		assertThat(proposalRepository.findAll())
				.allSatisfy(p -> assertThat(p.getMessageId()).isNull());
	}

	// ------------------------------------------------------------- T5 record

	/**
	 * AC2 + AC8: a well-signed text message persists ONE PENDING proposal
	 * with the text intact, the resolved business, and the raw message data;
	 * and that PENDING proposal does NOT appear in findActiveOrders.
	 */
	@Test
	void signedTextMessageIsPersistedAsPendingWithResolvedBusinessAndIntactText() throws Exception {

		seedBusinessWithMetaSenderPhone();

		postSigned(META_TEXT_MESSAGE_PAYLOAD).andExpect(status().isOk());

		OrderProposal proposal = proposalRepository.findByMessageId("wamid.HBg...").orElseThrow();
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
		assertThat(proposal.getChannel()).isEqualTo("whatsapp");
		assertThat(proposal.getFromPhone()).isEqualTo(META_SENDER);
		assertThat(proposal.getRawText()).isEqualTo("hola quiero 2 milanesas con papas y una coca");
		assertThat(proposal.getBusiness()).isNotNull();
		assertThat(proposal.getBusiness().getId()).isEqualTo(BUSINESS_ID);
		assertThat(proposal.getReceivedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
		assertThat(proposal.getFailureReason()).isNull();
	}

	/**
	 * AC8, against the query: a PENDING proposal exists but
	 * {@code findActiveOrders} returns nothing for its business — proposals
	 * and orders are different entities and different tables, so the kitchen
	 * cannot see a proposal.
	 */
	@Test
	void pendingProposalDoesNotAppearInFindActiveOrders() throws Exception {

		seedBusinessWithMetaSenderPhone();

		postSigned(META_TEXT_MESSAGE_PAYLOAD).andExpect(status().isOk());

		assertThat(proposalRepository.findByMessageId("wamid.HBg...")).isPresent();
		assertThat(orderRepository.findActiveOrders(BUSINESS_ID)).isEmpty();
	}

	// ------------------------------------------------------- business outcomes (200)

	/**
	 * AC5: an unresolvable phone is a business outcome — the message is
	 * recorded as a FAILED proposal with a reason and a NULL business, and
	 * the provider gets 200 (retrying could never make the phone resolve).
	 */
	@Test
	void unknownPhonePersistsFailedProposalWithNullBusinessAndAnswers200() throws Exception {

		seedBusinessWithMetaSenderPhone();

		String otherSender = META_TEXT_MESSAGE_PAYLOAD.replace(META_SENDER, UNKNOWN_SENDER);
		postSigned(otherSender).andExpect(status().isOk());

		OrderProposal proposal = proposalRepository.findByMessageId("wamid.HBg...").orElseThrow();
		assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.FAILED);
		assertThat(proposal.getBusiness()).isNull();
		assertThat(proposal.getFailureReason()).isNotBlank();
	}

	/**
	 * AC6: an ambiguous phone is recorded and never routed. The database now
	 * FORBIDS the ambiguous state (T3c UNIQUE on the canonical phone), so —
	 * exactly like the T3c pure-function split — the resolver is substituted
	 * here to drive the handler's ambiguous branch, which must persist a
	 * FAILED proposal with the conflicting ids in the reason, a NULL
	 * business, and answer 200.
	 */
	@Test
	void ambiguousPhonePersistsFailedProposalWithNullBusinessAndAnswers200() throws Exception {

		seedBusinessWithMetaSenderPhone();
		IInboundMessageHandler handler = handlerProvider.getObject();
		IBusinessPhoneResolver realResolver = (IBusinessPhoneResolver)
				ReflectionTestUtils.getField(handler, "phoneResolver");
		IBusinessPhoneResolver ambiguousResolver = mock(IBusinessPhoneResolver.class);
		when(ambiguousResolver.resolve(any())).thenReturn(
				new PhoneResolution.Ambiguous(List.of(11L, 22L)));
		ReflectionTestUtils.setField(handler, "phoneResolver", ambiguousResolver);
		try {
			postSigned(META_TEXT_MESSAGE_PAYLOAD).andExpect(status().isOk());

			OrderProposal proposal = proposalRepository.findByMessageId("wamid.HBg...").orElseThrow();
			assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.FAILED);
			assertThat(proposal.getBusiness()).isNull();
			assertThat(proposal.getFailureReason())
					.contains("11")
					.contains("22")
					.contains("ambiguous");
		} finally {
			ReflectionTestUtils.setField(handler, "phoneResolver", realResolver);
		}
	}

	// --------------------------------------------- infrastructure failure (500)

	/**
	 * Open gap 16: a handler that throws must NOT end in 200. With the
	 * database unreachable, a 200 tells Meta the message was processed and
	 * the order is lost without a trace — the correct answer is 500 so Meta
	 * retries. The production handler is mutated (its repository substituted
	 * with one that throws, simulating the unreachable database) because a
	 * test that cannot fail here is worthless.
	 */
	@Test
	void throwingHandlerYields500Not200() throws Exception {

		seedBusinessWithMetaSenderPhone();
		IInboundMessageHandler handler = handlerProvider.getObject();
		CrudRepository<OrderProposal, Long> realRepo = (CrudRepository<OrderProposal, Long>)
				ReflectionTestUtils.getField(handler, "orderProposals");
		OrderProposalRepository throwingRepo = mock(OrderProposalRepository.class);
		when(throwingRepo.existsByMessageId(any())).thenReturn(false);
		when(throwingRepo.saveAndFlush(any(OrderProposal.class)))
				.thenThrow(new RuntimeException("database unreachable"));
		ReflectionTestUtils.setField(handler, "orderProposals", throwingRepo);
		try {
			mockMvc.perform(post("/api/whatsapp/webhook")
							.contentType(MediaType.APPLICATION_JSON)
							.header(SIGNATURE_HEADER, sha256Signature(APP_SECRET,
									META_TEXT_MESSAGE_PAYLOAD.getBytes(StandardCharsets.UTF_8)))
							.content(META_TEXT_MESSAGE_PAYLOAD.getBytes(StandardCharsets.UTF_8)))
					.andExpect(status().isInternalServerError());
		} finally {
			ReflectionTestUtils.setField(handler, "orderProposals", realRepo);
		}
	}

	/**
	 * Open gap 9: with NO handler registered, accepting the message with a
	 * 200 and discarding it would be the "success with silent loss" failure
	 * mode. Absence of a handler is a wiring defect and must be loud. Unit
	 * test: the controller is constructed exactly as Spring would, but with
	 * an ObjectProvider that streams no handlers.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void missingHandlerIsALoudFailureNotASilentDiscard() throws Exception {

		ObjectProvider<IInboundMessageHandler> emptyProvider = mock(ObjectProvider.class);
		doReturn(java.util.stream.Stream.empty()).when(emptyProvider).orderedStream();

		WhatsappSignatureVerifier alwaysValidVerifier = mock(WhatsappSignatureVerifier.class);
		when(alwaysValidVerifier.verifySignature(any(), any())).thenReturn(true);
		MetaInboundMessageTranslator oneMessageTranslator = mock(MetaInboundMessageTranslator.class);
		when(oneMessageTranslator.translate(any())).thenReturn(java.util.Optional.of(new InboundMessage(
				"whatsapp", "wamid.X", META_SENDER, "text", Instant.now())));

		WhatsappWebhookController controller = new WhatsappWebhookController(
				alwaysValidVerifier,
				oneMessageTranslator,
				emptyProvider,
				metaWebhookJsonMapper);

		byte[] body = META_TEXT_MESSAGE_PAYLOAD.getBytes(StandardCharsets.UTF_8);
		assertThatThrownBy(() -> controller.receive("sha256=aa", body))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("IInboundMessageHandler")
				.hasMessageContaining("registered");
	}

	// --------------------------------------- T4 race: the constraint is the authority

	/**
	 * The pre-check alone is racy: two concurrent deliveries of the same
	 * message can both miss it. The authoritative guarantee is the
	 * {@code uq_order_proposals_message_id} UNIQUE constraint, and the insert
	 * handler must answer that violation as "already processed" — returning
	 * normally, so the provider still gets its 200 — instead of failing.
	 * Built against a fresh handler with mocked collaborators because the
	 * violation must happen OUTSIDE any shared transaction to be catchable
	 * without a rollback-only propagation.
	 */
	@Test
	void duplicateInsertLosingTheRaceIsAnsweredAsAlreadyProcessed() {

		OrderProposalRepository racingRepo = mock(OrderProposalRepository.class);
		when(racingRepo.existsByMessageId("wamid.X")).thenReturn(false);
		when(racingRepo.saveAndFlush(any(OrderProposal.class)))
				.thenThrow(new DataIntegrityViolationException("unique constraint violated",
						new ConstraintViolationException(
								"duplicate key value violates unique constraint", null,
								"uq_order_proposals_message_id")));

		PersistingInboundMessageHandler handler = new PersistingInboundMessageHandler(
				racingRepo,
				fromPhone -> new PhoneResolution.NotFound(),
				businessRepository);

		assertThatCode(() -> handler.handle(new InboundMessage(
						"whatsapp", "wamid.X", META_SENDER, "text", Instant.now())))
				.doesNotThrowAnyException();
	}

	/**
	 * The reverse case, protecting the distinction: an integrity violation
	 * that is NOT the messageId constraint must be rethrown — it would become
	 * a 500, never be mistaken for an already-processed message. (Mutation
	 * check: with {@code isMessageIdUniqueViolation} unconditionally true,
	 * this test fails with the violation swallowed.)
	 */
	@Test
	void integrityViolationNotCausedByTheMessageIdConstraintIsNotSwallowed() {

		OrderProposalRepository racingRepo = mock(OrderProposalRepository.class);
		when(racingRepo.existsByMessageId("wamid.X")).thenReturn(false);
		when(racingRepo.saveAndFlush(any(OrderProposal.class)))
				.thenThrow(new DataIntegrityViolationException("some other constraint",
						new ConstraintViolationException(
								"null value in column", null,
								"some_other_constraint")));

		PersistingInboundMessageHandler handler = new PersistingInboundMessageHandler(
				racingRepo,
				fromPhone -> new PhoneResolution.NotFound(),
				businessRepository);

		assertThatThrownBy(() -> handler.handle(new InboundMessage(
						"whatsapp", "wamid.X", META_SENDER, "text", Instant.now())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ------------------------------------------------- open gap 32: no broadcast

	/**
	 * The structural guarantee "a proposal never reaches the kitchen" pinned
	 * where absence used to be the only evidence: after a full inbound POST —
	 * message accepted, proposal persisted — NOTHING was sent through the
	 * WebSocket broker. If a broadcast is ever added to the inbound path,
	 * this fails; guarantees held only by absence rot silently.
	 */
	@Test
	void inboundPostNeverBroadcastsAnythingToTheKitchen() throws Exception {

		seedBusinessWithMetaSenderPhone();

		postSigned(META_TEXT_MESSAGE_PAYLOAD).andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));

		assertThat(proposalRepository.findByMessageId("wamid.HBg...")).isPresent();
		verifyNoInteractions(messagingTemplate);
	}

	// ------------------------------------------------------------- stock isolation

	/**
	 * Nothing in the inbound path touches stock: {@code createOrder} is the
	 * only stock writer, and the proposal path never calls it. After the
	 * whole flow — message received, proposal persisted — the product stock
	 * is unchanged and no Order row exists.
	 */
	@Test
	void inboundFlowLeavesStockUntouched() throws Exception {

		Business business = seedBusinessWithMetaSenderPhone();
		Category category = new Category();
		category.setBusiness(business);
		category.setName("Inbound Stock Category");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName("Inbound Stock Product");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("30.00"));
		product.setStock(5);
		product.setActive(true);
		product = productRepository.saveAndFlush(product);

		postSigned(META_TEXT_MESSAGE_PAYLOAD).andExpect(status().isOk());

		assertThat(proposalRepository.findByMessageId("wamid.HBg...")).isPresent();
		assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
		assertThat(countOrdersForBusiness(BUSINESS_ID)).isZero();
	}

	// ------------------------------------------------------------------ helpers

	private ResultActions postSigned(String payload) throws Exception {

		byte[] body = payload.getBytes(StandardCharsets.UTF_8);
		return mockMvc.perform(post("/api/whatsapp/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header(SIGNATURE_HEADER, sha256Signature(APP_SECRET, body))
						.content(body));
	}

	private long countOrdersForBusiness(Long businessId) {

		return entityManager
				.createQuery("SELECT COUNT(o) FROM Order o WHERE o.business.id = :id", Long.class)
				.setParameter("id", businessId)
				.getSingleResult();
	}

	private Business seedBusinessWithMetaSenderPhone() {

		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Inbound Persistence Business");
		business.setSlug("inbound-persistence-business");
		business.setPhone(META_SENDER);
		return businessRepository.saveAndFlush(business);
	}

	/**
	 * Computes the expected signature with the JDK directly, as in
	 * {@code WhatsappWebhookContractTests}.
	 */
	private static String sha256Signature(String appSecret, byte[] body) throws Exception {

		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
	}
}
