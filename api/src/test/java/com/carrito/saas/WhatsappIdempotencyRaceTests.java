package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.api.WhatsappWebhookController;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.service.whatsapp.PersistingInboundMessageHandler;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Non-transactional integration tests for the T4 idempotency guarantee
 * ({@code odd/tasks/whatsapp-inbound.md}, open gaps 36 and 37).
 *
 * <p><strong>Why this class is deliberately NOT {@code @Transactional}</strong>
 * (unlike the rest of the repo's suites): the idempotency guarantee lives in
 * the {@code uq_order_proposals_message_id} UNIQUE constraint, and the
 * constraint only answers when an insert actually collides at flush time.
 * Inside a test-managed transaction that collision poisons the shared
 * session (the Hibernate {@code AssertionFailure ... flushed after an
 * exception} mode), so the constraint-as-authority path can never be
 * exercised by a rollback-isolated test — that is exactly why it had only
 * ever been proven by a throwaway probe. Here every request commits for
 * real, each repository call runs in its own transaction (the production
 * shape, since {@link PersistingInboundMessageHandler} is not
 * {@code @Transactional}), and cleanup removes only the rows these tests
 * created. Fixture isolation comes from precisely scoped {@code @AfterEach}
 * cleanup, not from rollback; row assertions are scoped to this suite's
 * sender phone for the same reason.</p>
 *
 * <p>Blank/whitespace message ids live here too (open gap 36): the defect
 * they expose is a real constraint collision between distinct messages —
 * the same session-poisoning shape — so the RED for the silent-loss bug is
 * only observable in this non-transactional setting.</p>
 *
 * <p><strong>Why the sender phone is a suite-UNIQUE marker (open gap 39).</strong>
 * This suite used to clean up by the sender phone {@code 5491122334455}, a
 * literal shared with {@code WhatsappInboundPersistenceTests} and
 * {@code WhatsappWebhookContractTests} — so "my rows" was defined by a value
 * another suite also uses, and the cleanup silently deleted a foreign
 * committed row carrying that phone (measured: seeded before = 1, after the
 * suite = 0). Unreachable today only because those suites are
 * {@code @Transactional} and roll back; any future non-transactional test
 * with that phone would lose rows invisibly. The marker below is used by
 * this suite's fixtures, assertions AND cleanup, and is grep-verified unique
 * in the repository. Its uniqueness, not any business id, is what scopes the
 * cleanup: the proposals this suite deliberately creates with NO usable
 * business linkage (blank/whitespace/absent message ids) are still selected
 * by the marker.</p>
 *
 * <p><strong>Why the cleanup deletes items before proposals (open gap 40).</strong>
 * {@code order_proposal_items} has a FOREIGN KEY to
 * {@code order_proposals(id)} and a JPQL bulk {@code DELETE} bypasses
 * entity-level cascade by specification, so the old proposal-only bulk
 * delete worked only while no proposal ever had lines — exactly the state
 * T6 ends. The cleanup therefore removes the item rows first, then the
 * proposals, both scoped by the same marker, inside ONE transaction so the
 * FK never observes an orphan. Chosen over entity-level cascade removal
 * (e.g. {@code deleteAll}-style loads with {@code CascadeType.ALL}) because
 * it requires no production-code change (the repository may not gain
 * suite-only methods) and does not depend on the entity graph being
 * maintained. Trade-off: bulk deletes bypass entity lifecycle semantics, so
 * if {@code OrderProposal} ever gains another child table, this cleanup must
 * be extended in lockstep — the FK error, not silent residue, announces a
 * miss.</p>
 */
@SpringBootTest(properties = {
		"whatsapp.app-secret=test-app-secret",
		"whatsapp.verify-token=test-verify-token"
})
class WhatsappIdempotencyRaceTests {

	private static final String APP_SECRET = "test-app-secret";
	private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

	/**
	 * Sender phone UNIQUE to this suite (open gap 39): used by its fixtures,
	 * its assertions and its cleanup. NOT shared with any other suite or the
	 * simulator ({@code 5491122334455} is their shared literal and this suite
	 * must never select on it) — grep the repository for this value to keep
	 * that property true.
	 */
	private static final String SUITE_SENDER = "5491176543210";

	/** The OLD shared phone literal, kept only to prove cleanup ignores it. */
	private static final String SHARED_PHONE_OF_OTHER_SUITES = "5491122334455";

	/** Different id from the transactional suite's, so the two never share rows. */
	private static final Long BUSINESS_ID = 987_900_002L;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BusinessRepository businessRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private TransactionTemplate txTemplate;

	private MockMvc mockMvc;

	@Autowired
	void setUpTransactionTemplate(PlatformTransactionManager transactionManager) {

		this.txTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void setUp() {

		cleanOwnRows();
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	@AfterEach
	void cleanOwnRows() {

		// Precisely scoped cleanup (no rollback in this class): only the rows
		// seeded/created by THIS suite, never another suite's data. Scoping is
		// the suite-unique sender marker (open gap 39), which also selects the
		// proposals this suite creates with no business linkage. Items go
		// first (open gap 40): a bulk JPQL DELETE bypasses cascade, and
		// order_proposal_items holds the FK to order_proposals. The bulk
		// deletes need their own transaction because there is no test-managed
		// one in this non-transactional suite.
		txTemplate.executeWithoutResult(tx -> {
			entityManager.createQuery(
					"DELETE FROM OrderProposalItem i WHERE i.proposal.id IN "
							+ "(SELECT p.id FROM OrderProposal p WHERE p.fromPhone = :sender)")
					.setParameter("sender", SUITE_SENDER)
					.executeUpdate();
			entityManager.createQuery("DELETE FROM OrderProposal p WHERE p.fromPhone = :sender")
					.setParameter("sender", SUITE_SENDER)
					.executeUpdate();
			entityManager.createQuery("DELETE FROM Business b WHERE b.id = :id")
					.setParameter("id", BUSINESS_ID)
					.executeUpdate();
		});
	}

	// ------------------------------- open gap 36: a blank id is NOT a usable id

	/**
	 * Two DISTINCT messages whose Meta id is the empty string must both be
	 * recorded. The unique index treats {@code ''} as a real value, so a
	 * blank id persisted verbatim would silently discard the second distinct
	 * message with a 200 — message loss. The single "usable id" decision
	 * (see {@link PersistingInboundMessageHandler}) must therefore persist
	 * NULL for a blank id, exactly as it does for an absent one.
	 */
	@Test
	void distinctMessagesWithBlankIdAreBothRecordedWithNullMessageId() throws Exception {

		seedBusinessWithSuiteSenderPhone();

		String first = blankIdPayload("first blank-id message");
		String second = blankIdPayload("a DIFFERENT blank-id message");

		postSigned(first).andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));
		postSigned(second).andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));

		assertThat(ownProposals()).hasSize(2);
		assertThat(ownProposals())
				.allSatisfy(p -> assertThat(p.getMessageId()).isNull());
	}

	/**
	 * Whitespace variant of the same rule: an id of blanks is equally
	 * unusable as a dedup key and must be persisted as NULL, never as the
	 * whitespace string itself.
	 */
	@Test
	void distinctMessagesWithWhitespaceOnlyIdAreBothRecordedWithNullMessageId() throws Exception {

		seedBusinessWithSuiteSenderPhone();

		String first = whitespaceIdPayload("first whitespace-id message");
		String second = whitespaceIdPayload("a DIFFERENT whitespace-id message");

		postSigned(first).andExpect(status().isOk());
		postSigned(second).andExpect(status().isOk());

		assertThat(ownProposals()).hasSize(2);
		assertThat(ownProposals())
				.allSatisfy(p -> assertThat(p.getMessageId()).isNull());
	}

	// ------------------------------- open gap 37: the constraint is the authority

	/**
	 * The full idempotency guarantee, exercised WITHOUT any surrounding test
	 * transaction: the same signed payload POSTed twice answers 200 twice
	 * and leaves exactly ONE row. Against the unmutated code the pre-check
	 * answers the duplicate; the /tmp mutation that makes the pre-check
	 * always miss proves this test still passes for the right reason — the
	 * UNIQUE constraint answered it — and the mutant that disables the
	 * constraint attribution turns this into a 500 and fails it.
	 */
	@Test
	void sameMessagePostedTwiceWithoutAnyTestTransactionProducesOneRowAndTwo200s() throws Exception {

		seedBusinessWithSuiteSenderPhone();

		postSigned(metaTextMessagePayload("the one and only real message"))
				.andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));
		postSigned(metaTextMessagePayload("the one and only real message"))
				.andExpect(status().isOk())
				.andExpect(content().string(WhatsappWebhookController.EVENT_RECEIVED));

		assertThat(ownProposals()).hasSize(1);
	}

	// ------------------------------- open gap 39: cleanup never deletes other rows

	/**
	 * The experiment that exposed the old defect, now pinned in the repo: a
	 * FOREIGN committed proposal carrying the phone literal the OTHER suites
	 * share must survive this suite's run. The old cleanup scoped by that
	 * shared literal and deleted the foreign row (seeded before = 1, after
	 * the suite = 0); the marker-scoped cleanup must leave it untouched.
	 */
	@Test
	void cleanupLeavesRowsThatDoNotBelongToThisSuite() throws Exception {

		Long foreignProposalId = seedForeignProposalCommittedByJdbcExperiment(
				SHARED_PHONE_OF_OTHER_SUITES);

		try {
			cleanOwnRows();

			assertThat(foreignProposalStillPresent(foreignProposalId)).isTrue();
		} finally {
			// The foreign row is deliberately NOT this suite's, so cleanOwnRows
			// will never remove it: the test removes exactly what it seeded.
			txTemplate.executeWithoutResult(tx -> entityManager
					.createQuery("DELETE FROM OrderProposal p WHERE p.id = :id")
					.setParameter("id", foreignProposalId)
					.executeUpdate());
		}
	}

	// ------------------------------- open gap 40: cleanup removes items with their proposal

	/**
	 * The T6 time bomb, pinned: the cleanup must remove a proposal AND its
	 * lines. Two committed lines are constructed directly (no normalizer
	 * exists yet — T6), then the suite's own cleanup runs: both the proposal
	 * and its item rows must be gone, without an FK violation.
	 */
	@Test
	void cleanupRemovesProposalsWithTheirItems() throws Exception {

		seedBusinessWithSuiteSenderPhone();

		txTemplate.executeWithoutResult(tx -> {
			OrderProposal proposal = new OrderProposal();
			proposal.setChannel("whatsapp");
			proposal.setMessageId(null);
			proposal.setFromPhone(SUITE_SENDER);
			proposal.setRawText("2 milanesas y una coca");
			proposal.setStatus(ProposalStatus.PENDING);
			for (String line : new String[]{"2 milanesas", "una coca"}) {
				OrderProposalItem item = new OrderProposalItem();
				item.setProposal(proposal);
				item.setRawLine(line);
				// T6b made the per-line resolution NOT NULL; this fixture is cleanup
				// plumbing, not a normalization product, so it uses the raw-text state.
				item.setResolution(ItemResolution.UNRESOLVED);
				proposal.getItems().add(item);
			}
			entityManager.persist(proposal);
		});
		entityManager.clear();

		assertThat(ownProposals()).hasSize(1);
		assertThat(ownProposalItemCount()).isEqualTo(2);

		cleanOwnRows();
		entityManager.clear();

		assertThat(ownProposals()).isEmpty();
		assertThat(ownProposalItemCount()).isZero();
	}

	// ------------------------------------------------------------------ helpers

	/** Every proposal row owned by THIS suite (scoped to its unique marker). */
	private List<OrderProposal> ownProposals() {

		return entityManager
				.createQuery("SELECT p FROM OrderProposal p WHERE p.fromPhone = :sender",
						OrderProposal.class)
				.setParameter("sender", SUITE_SENDER)
				.getResultList();
	}

	/** Item rows belonging to THIS suite's proposals (scoped to the marker). */
	private long ownProposalItemCount() {

		return entityManager
				.createQuery("SELECT count(i) FROM OrderProposalItem i "
						+ "WHERE i.proposal.fromPhone = :sender", Long.class)
				.setParameter("sender", SUITE_SENDER)
				.getSingleResult();
	}

	private void seedBusinessWithSuiteSenderPhone() {

		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Idempotency Race Business");
		business.setSlug("idempotency-race-business");
		business.setPhone(SUITE_SENDER);
		businessRepository.saveAndFlush(business);
	}

	/**
	 * Seeds a proposal that does NOT belong to this suite (the foreign row of
	 * the open-gap-39 experiment), committed for real — exactly the shape a
	 * future non-transactional suite with the shared phone would leave behind.
	 */
	private Long seedForeignProposalCommittedByJdbcExperiment(String foreignFromPhone) {

		return txTemplate.execute(tx -> {
			OrderProposal foreign = new OrderProposal();
			foreign.setChannel("whatsapp");
			foreign.setMessageId(null);
			foreign.setFromPhone(foreignFromPhone);
			foreign.setRawText("FOREIGN row owned by no suite — must survive this suite");
			foreign.setStatus(ProposalStatus.FAILED);
			entityManager.persist(foreign);
			entityManager.flush();
			return foreign.getId();
		});
	}

	private boolean foreignProposalStillPresent(Long id) {

		entityManager.clear();
		return entityManager.find(OrderProposal.class, id) != null;
	}

	/** Payload with the Meta id present and real (a genuine dedup key). */
	private static String metaTextMessagePayload(String text) {

		return textMessagePayload("\"id\": \"wamid.RACE.TEST\",", text);
	}

	/** Payload whose Meta id is the empty string. */
	private static String blankIdPayload(String text) {

		return textMessagePayload("\"id\": \"\",", text);
	}

	/** Payload whose Meta id is whitespace only. */
	private static String whitespaceIdPayload(String text) {

		return textMessagePayload("\"id\": \"   \",", text);
	}

	private static String textMessagePayload(String idLine, String text) {

		return """
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
				              "display_phone_number": "5491176543210",
				              "phone_number_id": "2222222222"
				            },
				            "contacts": [
				              {
				                "profile": {
				                  "name": "Juan"
				                },
				                "wa_id": "5491176543210"
				              }
				            ],
				            "messages": [
				              {
				                "from": "5491176543210",
				                %s
				                "timestamp": "1726000000",
				                "type": "text",
				                "text": {
				                  "body": "%s"
				                }
				              }
				            ]
				          }
				        }
				      ]
				    }
				  ]
				}""".formatted(idLine, text);
	}

	private ResultActions postSigned(String payload) throws Exception {

		byte[] body = payload.getBytes(StandardCharsets.UTF_8);
		return mockMvc.perform(post("/api/whatsapp/webhook")
						.contentType(MediaType.APPLICATION_JSON)
						.header(SIGNATURE_HEADER, sha256Signature(APP_SECRET, body))
						.content(body));
	}

	/** Computes the expected signature with the JDK directly, as in the other suites. */
	private static String sha256Signature(String appSecret, byte[] body) throws Exception {

		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
	}
}
