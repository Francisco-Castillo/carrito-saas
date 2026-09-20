package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * HTTP contract of T7a.1 — LIST and REJECT order proposals
 * ({@code odd/tasks/whatsapp-inbound.md}, T7a.1 work unit).
 *
 * <p>The businessId comes ONLY from the JWT ({@code ISecurityService#getCurrentBusinessId}),
 * never from a parameter, so every test authenticates through the REAL filter
 * chain with {@link BusinessAuthSeed} and never passes a business id in the
 * request.</p>
 *
 * <p>Contract pinned here:</p>
 * <ul>
 *   <li>the list contains exactly the authenticated business's PENDING
 *     proposals (positive control included), ordered by received_at DESC with
 *     a NULL received_at LAST, with their lines and resolutions;</li>
 *   <li>reject marks REJECTED without creating an order, without touching
 *     stock and WITHOUT deleting the lines;</li>
 *   <li>rejecting an already-decided proposal is 409; a foreign proposal is
 *     404 and is left untouched;</li>
 *   <li>{@code confirmable} is false unless EVERY line is RESOLVED carrying
 *     exactly one of productId/comboId — each non-RESOLVED value is
 *     independently load-bearing;</li>
 *   <li>{@code candidates} is exposed VERBATIM (newline-joined candidate
 *     names, never parsed or reformatted).</li>
 * </ul>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy}: Spring Boot 4 moved {@code @AutoConfigureMockMvc}
 * into the separate {@code spring-boot-webmvc-test} module, which
 * {@code spring-boot-starter-test} does not bring. {@code @Transactional}
 * rolls back every fixture at test end; assertions that read persisted state
 * {@code flush()}/{@code clear()} first so they are fresh SELECTs against
 * PostgreSQL.</p>
 */
@SpringBootTest
@Transactional
class OrderProposalListAndRejectTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_700_601L;
	private static final Long BUSINESS_B_ID = 987_700_602L;
	private static final Long BUSINESS_C_ID = 987_700_603L;

	private static final Instant RECEIVED_EARLIER = Instant.parse("2026-09-20T10:00:00Z");
	private static final Instant RECEIVED_LATER = Instant.parse("2026-09-20T11:00:00Z");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private BusinessUserRepository businessUserRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@Autowired
	private OrderProposalRepository orderProposalRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness ownerA;
	private BusinessAuthSeed.AuthenticatedBusiness ownerB;
	private BusinessAuthSeed.AuthenticatedBusiness ownerC;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		ownerA = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, "proposals-a", "owner-a");
		ownerB = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_B_ID, "proposals-b", "owner-b");
		ownerC = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_C_ID, "proposals-c", "owner-c");
	}

	@Test
	void listReturnsOnlyPendingProposalsOfTheAuthenticatedBusiness() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Business businessB = businessRepository.findById(BUSINESS_B_ID).orElseThrow();

		// A: two PENDING (one with a NULL received_at, to pin NULLS LAST) plus
		// one REJECTED and one CONFIRMED, which must NOT come back.
		OrderProposal pendingEarly = seedProposal(businessA, ProposalStatus.PENDING, RECEIVED_EARLIER);
		attachLine(pendingEarly, ItemResolution.RESOLVED, "Milanesa napolitana", 910_001L, null, 2, "2 milanesas", null);
		attachLine(pendingEarly, ItemResolution.AMBIGUOUS, null, null, null, null, "una coca",
				"Coca-Cola 500 ml\nCoca-Cola 2.25 L");
		save(pendingEarly);

		OrderProposal pendingLate = seedProposal(businessA, ProposalStatus.PENDING, RECEIVED_LATER);
		attachLine(pendingLate, ItemResolution.RESOLVED, "Pan", 910_002L, null, 1, "pan", null);
		save(pendingLate);

		OrderProposal pendingNull = seedProposal(businessA, ProposalStatus.PENDING, null);
		save(pendingNull);

		OrderProposal rejected = seedProposal(businessA, ProposalStatus.REJECTED, RECEIVED_EARLIER);
		save(rejected);
		OrderProposal confirmed = seedProposal(businessA, ProposalStatus.CONFIRMED, RECEIVED_LATER);
		save(confirmed);

		// B: one PENDING — must NOT appear in A's list.
		OrderProposal foreign = seedProposal(businessB, ProposalStatus.PENDING, RECEIVED_LATER);
		save(foreign);

		mockMvc.perform(get("/api/business/proposals").header("Authorization", ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				// POSITIVE CONTROL: A's own PENDING proposals ARE present, in
				// received_at DESC order with the NULL one LAST.
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].id").value(pendingLate.getId()))
				.andExpect(jsonPath("$[1].id").value(pendingEarly.getId()))
				.andExpect(jsonPath("$[2].id").value(pendingNull.getId()))
				// The lines come with their resolutions.
				.andExpect(jsonPath("$[1].items", hasSize(2)))
				.andExpect(jsonPath("$[1].items[0].resolution").value("RESOLVED"))
				.andExpect(jsonPath("$[1].items[0].productId").value(910_001))
				.andExpect(jsonPath("$[1].items[0].productName").value("Milanesa napolitana"))
				.andExpect(jsonPath("$[1].items[0].quantity").value(2))
				.andExpect(jsonPath("$[1].items[1].resolution").value("AMBIGUOUS"))
				.andExpect(jsonPath("$[1].items[1].productId").doesNotExist())
				// The decided ones and the foreign one are absent.
				.andExpect(jsonPath("$[?(@.id == " + rejected.getId() + ")]").isEmpty())
				.andExpect(jsonPath("$[?(@.id == " + confirmed.getId() + ")]").isEmpty())
				.andExpect(jsonPath("$[?(@.id == " + foreign.getId() + ")]").isEmpty());
	}

	@Test
	void proposalListForTheAuthenticatedBusinessWithNoProposalsIsEmpty() throws Exception {

		mockMvc.perform(get("/api/business/proposals").header("Authorization", ownerC.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void anonymousListIsRejected() throws Exception {

		int status = mockMvc.perform(get("/api/business/proposals"))
				.andReturn()
				.getResponse()
				.getStatus();

		assertThat(status).isIn(401, 403);
	}

	@Test
	void rejectMarksTheProposalRejectedWithoutCreatingAnything() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		Category category = new Category();
		category.setBusiness(businessA);
		category.setName("Reject Test");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName("Milanesa napolitana");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("50.00"));
		product.setStock(10);
		product = productRepository.saveAndFlush(product);

		OrderProposal pending = seedProposal(businessA, ProposalStatus.PENDING, RECEIVED_EARLIER);
		attachLine(pending, ItemResolution.RESOLVED, product.getName(), product.getId(), null, 2, "2 milanesas", null);
		save(pending);

		long ordersBefore = orderRepository.count();

		mockMvc.perform(post("/api/business/proposals/{id}/reject", pending.getId())
						.header("Authorization", ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(pending.getId()))
				.andExpect(jsonPath("$.status").value("REJECTED"));

		entityManager.flush();
		entityManager.clear();

		OrderProposal fresh = orderProposalRepository.findById(pending.getId()).orElseThrow();
		assertThat(fresh.getStatus()).isEqualTo(ProposalStatus.REJECTED);
		// The lines are NOT deleted.
		assertThat(fresh.getItems()).hasSize(1);
		// No order was created and no stock moved.
		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(10);
	}

	@Test
	void rejectingAnAlreadyDecidedProposalConflicts() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		OrderProposal decided = seedProposal(businessA, ProposalStatus.CONFIRMED, RECEIVED_EARLIER);
		save(decided);

		mockMvc.perform(post("/api/business/proposals/{id}/reject", decided.getId())
						.header("Authorization", ownerA.authorizationHeader()))
				.andExpect(status().isConflict());
	}

	@Test
	void rejectingAnotherBusinessProposalIsNotFoundAndLeavesItUntouched() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		OrderProposal foreign = seedProposal(businessA, ProposalStatus.PENDING, RECEIVED_EARLIER);
		attachLine(foreign, ItemResolution.RESOLVED, "Pan", 910_003L, null, 1, "pan", null);
		save(foreign);

		// B tries to reject A's proposal.
		mockMvc.perform(post("/api/business/proposals/{id}/reject", foreign.getId())
						.header("Authorization", ownerB.authorizationHeader()))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		OrderProposal fresh = orderProposalRepository.findById(foreign.getId()).orElseThrow();
		assertThat(fresh.getStatus()).isEqualTo(ProposalStatus.PENDING);
	}

	/**
	 * Every non-RESOLVED line blocks confirmability, one proposal per value so
	 * each {@link ItemResolution} branch is independently load-bearing.
	 */
	@ParameterizedTest
	@EnumSource(ItemResolution.class)
	void confirmableIsFalseWhenAnyLineIsNotResolved(ItemResolution resolution) throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		OrderProposal pending = seedProposal(businessA, ProposalStatus.PENDING, RECEIVED_EARLIER);
		boolean expected = resolution == ItemResolution.RESOLVED;
		// A RESOLVED line carries exactly one of productId/comboId; the other
		// states are seeded with the ids they legitimately carry (SUGGESTED)
		// or without them (AMBIGUOUS, UNRESOLVED) — the rule is about the
		// RESOLUTION, not the presence of ids.
		if (expected) {
			attachLine(pending, resolution, "Milanesa napolitana", 910_004L, null, 1, "milanesa", null);
		} else {
			Long productId = resolution == ItemResolution.SUGGESTED ? 910_004L : null;
			attachLine(pending, resolution, productId != null ? "Milanesa napolitana" : null, productId, null, 1,
					"milanesa", null);
		}
		save(pending);

		mockMvc.perform(get("/api/business/proposals").header("Authorization", ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].confirmable").value(expected));
	}

	@Test
	void confirmableIsFalseWhenAResolvedLineCarriesNoId() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		OrderProposal pending = seedProposal(businessA, ProposalStatus.PENDING, RECEIVED_EARLIER);
		attachLine(pending, ItemResolution.RESOLVED, "Milanesa napolitana", null, null, 1, "milanesa", null);
		save(pending);

		mockMvc.perform(get("/api/business/proposals").header("Authorization", ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].confirmable").value(false));
	}

	/**
	 * The shape the {@code @EnumSource} table does not reach: a NON-RESOLVED line
	 * that carries a comboId and no productId — exactly what a SUGGESTED COMBO
	 * looks like once persisted (the handler fills {@code comboId} from the
	 * candidate). It must block, and the blocked-ness must come from the
	 * RESOLUTION, never from which id happens to be present.
	 *
	 * <p>Pinned because of a precedence subtlety that is easy to get wrong in
	 * BOTH directions: the production rule reads {@code A && B ^ C}, and in Java
	 * {@code ^} binds TIGHTER than {@code &&}, so it already groups as
	 * {@code A && (B ^ C)}. A reader (or an author) who assumes the other
	 * precedence will "fix" a bug that is not there, or write one. This test
	 * fails if the grouping ever becomes {@code (A && B) ^ C}: with
	 * {@code A} false and {@code C} true that expression yields true, which
	 * would make an unaccepted suggestion placeable in T7a.2.</p>
	 */
	@Test
	void confirmableIsFalseWhenANonResolvedLineCarriesAComboId() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		OrderProposal pending = seedProposal(businessA, ProposalStatus.PENDING, RECEIVED_EARLIER);
		attachLine(pending, ItemResolution.SUGGESTED, "Combo familiar", null, 910_007L, 1, "combo", null);
		save(pending);

		mockMvc.perform(get("/api/business/proposals").header("Authorization", ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].confirmable").value(false));
	}

	/** The XOR half: a RESOLVED line carrying BOTH ids is not placeable either. */
	@Test
	void confirmableIsFalseWhenAResolvedLineCarriesBothIds() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		OrderProposal pending = seedProposal(businessA, ProposalStatus.PENDING, RECEIVED_EARLIER);
		attachLine(pending, ItemResolution.RESOLVED, "Ambiguo", 910_008L, 910_009L, 1, "ambos", null);
		save(pending);

		mockMvc.perform(get("/api/business/proposals").header("Authorization", ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].confirmable").value(false));
	}

	@Test
	void confirmableIsTrueWhenEveryLineIsResolvedWithAnId() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		OrderProposal pending = seedProposal(businessA, ProposalStatus.PENDING, RECEIVED_EARLIER);
		attachLine(pending, ItemResolution.RESOLVED, "Milanesa napolitana", 910_005L, null, 2, "2 milanesas", null);
		attachLine(pending, ItemResolution.RESOLVED, null, null, 910_006L, 1, "combo", null);
		save(pending);

		mockMvc.perform(get("/api/business/proposals").header("Authorization", ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].confirmable").value(true));
	}

	@Test
	void listedProposalExposesCandidateNamesVerbatim() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		OrderProposal pending = seedProposal(businessA, ProposalStatus.PENDING, RECEIVED_EARLIER);
		String candidates = "Coca-Cola 500 ml\nCoca-Cola 2.25 L";
		attachLine(pending, ItemResolution.AMBIGUOUS, null, null, null, null, "una coca", candidates);
		save(pending);

		mockMvc.perform(get("/api/business/proposals").header("Authorization", ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				// VERBATIM: byte-identical, newline-joined, never parsed or
				// reformatted.
				.andExpect(jsonPath("$[0].items[0].candidates").value(candidates));
	}

	// --- fixtures -------------------------------------------------------------

	private OrderProposal seedProposal(Business business, ProposalStatus status, Instant receivedAt) {

		OrderProposal proposal = new OrderProposal();
		proposal.setBusiness(business);
		proposal.setStatus(status);
		proposal.setReceivedAt(receivedAt);
		proposal.setChannel("whatsapp");
		proposal.setFromPhone("5491100000001");
		proposal.setRawText("quiero 2 milanesas y una coca");
		return proposal;
	}

	private void attachLine(OrderProposal proposal, ItemResolution resolution, String productName, Long productId,
			Long comboId, Integer quantity, String rawLine, String candidates) {

		OrderProposalItem item = new OrderProposalItem();
		item.setProposal(proposal);
		item.setResolution(resolution);
		item.setProductName(productName);
		item.setProductId(productId);
		item.setComboId(comboId);
		item.setQuantity(quantity);
		item.setRawLine(rawLine);
		item.setCandidates(candidates);
		proposal.getItems().add(item);
	}

	private void save(OrderProposal proposal) {

		orderProposalRepository.save(proposal);
		entityManager.flush();
		entityManager.clear();
	}
}
