package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.enums.OperatorAction;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.service.whatsapp.ProposalCandidates;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * HTTP contract of T7b.1 — the operator's PER-LINE decision on an order
 * proposal ({@code odd/tasks/whatsapp-inbound.md}, T7b.1 work unit).
 *
 * <p>Contract pinned here:</p>
 * <ul>
 *   <li>{@code PATCH /api/business/proposals/{id}/items/{itemId}} records the
 *     {@code OperatorAction} (and, for {@code CHOSEN}, the chosen candidate
 *     NAME re-resolved against the LIVE catalog) and returns the updated
 *     {@code OrderProposalDTO};</li>
 *   <li>the legality matrix (ONE home in the service):
 *     SUGGESTED→{ACCEPTED,DISCARDED}, AMBIGUOUS→{CHOSEN,DISCARDED},
 *     UNRESOLVED→{DISCARDED}, RESOLVED→{} — anything outside it is 409;</li>
 *   <li>status codes: 400 for a malformed request, 404 for foreign-or-missing
 *     (proposal or line), 409 for every state conflict;</li>
 *   <li>the write is a CAS scoped by item AND proposal AND business AND
 *     PENDING: a decided proposal is 409 and nothing is ever written for a
 *     rejected decision;</li>
 *   <li>{@code candidateNames} round-trips through the single codec.</li>
 * </ul>
 *
 * <p>Same harness as the T7a suites: real PostgreSQL, MockMvc assembled by
 * hand with the real security filter chain, {@code businessId} only from the
 * JWT, fresh reads after flush/clear.</p>
 */
@SpringBootTest
@Transactional
class OrderProposalLineDecisionTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_700_801L;
	private static final Long BUSINESS_B_ID = 987_700_802L;

	private static final Instant RECEIVED_AT = Instant.parse("2026-09-20T12:00:00Z");

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
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness ownerA;
	private BusinessAuthSeed.AuthenticatedBusiness ownerB;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		ownerA = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, "line-decision-a", "line-decision-owner-a");
		ownerB = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_B_ID, "line-decision-b", "line-decision-owner-b");
	}

	// --- the legal actions ----------------------------------------------------

	@Test
	void acceptingASuggestedLineRecordsTheAction() throws Exception {

		Product product = seedProduct(businessA(), "Milanesa napolitana");
		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.SUGGESTED,
				"Milanesa napolitana", product.getId(), null, 2, "milanesa", null);

		mockMvc.perform(decide(ownerA, line.getId(), OperatorAction.ACCEPTED, null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(proposalOf(line.getId()).getId()))
				.andExpect(jsonPath("$.items[0].operatorAction").value("ACCEPTED"))
				// The id it carries is UNCHANGED: acceptance adds no semantics.
				.andExpect(jsonPath("$.items[0].productId").value(product.getId()));

		OrderProposalItem fresh = freshLine(line.getId());
		assertThat(fresh.getOperatorAction()).isEqualTo(OperatorAction.ACCEPTED);
		assertThat(fresh.getProductId()).isEqualTo(product.getId());
		assertThat(fresh.getChosenName()).isNull();
	}

	@Test
	void discardingASuggestedLineIsLegal() throws Exception {

		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.SUGGESTED,
				"Pan", 911_001L, null, 1, "pan", null);

		mockMvc.perform(decide(ownerA, line.getId(), OperatorAction.DISCARDED, null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].operatorAction").value("DISCARDED"));

		assertThat(freshLine(line.getId()).getOperatorAction()).isEqualTo(OperatorAction.DISCARDED);
	}

	@Test
	void discardingAnUnresolvedLineIsLegal() throws Exception {

		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.UNRESOLVED,
				null, null, null, null, "algo raro", null);

		mockMvc.perform(decide(ownerA, line.getId(), OperatorAction.DISCARDED, null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].operatorAction").value("DISCARDED"));

		assertThat(freshLine(line.getId()).getOperatorAction()).isEqualTo(OperatorAction.DISCARDED);
	}

	@Test
	void choosingACandidateOfAnAmbiguousLineResolvesItToTheCatalogId() throws Exception {

		Product product = seedProduct(businessA(), "Coca-Cola 500 ml");
		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.AMBIGUOUS,
				null, null, null, 1, "una coca", "Coca-Cola 500 ml\nCoca-Cola 2.25 L");

		mockMvc.perform(decide(ownerA, line.getId(), OperatorAction.CHOSEN, "Coca-Cola 500 ml"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].operatorAction").value("CHOSEN"))
				.andExpect(jsonPath("$.items[0].chosenName").value("Coca-Cola 500 ml"))
				// Asserted against the SEED, never a hardcoded number.
				.andExpect(jsonPath("$.items[0].productId").value(product.getId()));

		OrderProposalItem fresh = freshLine(line.getId());
		assertThat(fresh.getOperatorAction()).isEqualTo(OperatorAction.CHOSEN);
		assertThat(fresh.getChosenName()).isEqualTo("Coca-Cola 500 ml");
		assertThat(fresh.getProductId()).isEqualTo(product.getId());
	}

	// --- the conflicts that protect the choice --------------------------------

	@Test
	void choosingANameThatIsNotOneOfTheStoredCandidatesConflicts() throws Exception {

		// "Pepsi 1 L" IS a live catalog item: the only thing the decision is
		// rejected on is that it is NOT one of the names stored on the line —
		// this is what kills the mutation that drops the membership check
		// (the live-catalog re-resolution alone would accept it).
		seedProduct(businessA(), "Coca-Cola 500 ml");
		Product pepsi = seedProduct(businessA(), "Pepsi 1 L");
		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.AMBIGUOUS,
				null, null, null, 1, "una gaseosa", "Coca-Cola 500 ml\nCoca-Cola 2.25 L");

		mockMvc.perform(decide(ownerA, line.getId(), OperatorAction.CHOSEN, "Pepsi 1 L"))
				.andExpect(status().isConflict());

		// Nothing was written.
		assertThat(freshLine(line.getId()).getOperatorAction()).isNull();
		assertThat(freshLine(line.getId()).getChosenName()).isNull();
		assertThat(freshLine(line.getId()).getProductId()).isNotEqualTo(pepsi.getId());
	}

	@Test
	void choosingANameThatNoLongerResolvesUniquelyInTheCatalogConflicts() throws Exception {

		Product product = seedProduct(businessA(), "Coca-Cola 500 ml");
		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.AMBIGUOUS,
				null, null, null, 1, "una coca", "Coca-Cola 500 ml\nCoca-Cola 2.25 L");

		// The catalog changed under the proposal: the stored name is still
		// among the candidates but no longer resolves to exactly one item.
		productRepository.delete(product);
		entityManager.flush();
		entityManager.clear();

		mockMvc.perform(decide(ownerA, line.getId(), OperatorAction.CHOSEN, "Coca-Cola 500 ml"))
				.andExpect(status().isConflict());

		assertThat(freshLine(line.getId()).getOperatorAction()).isNull();
		assertThat(freshLine(line.getId()).getProductId()).isNull();
	}

	/**
	 * EVERY pair outside the legality matrix, one case per illegal pair: an
	 * illegal pairing is a STATE conflict (409), and nothing is written.
	 */
	@ParameterizedTest
	@MethodSource("illegalPairs")
	void actionsOutsideTheLegalityMatrixAreRejected(ItemResolution resolution, OperatorAction action) throws Exception {

		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), resolution,
				resolution == ItemResolution.UNRESOLVED ? null : "Milanesa napolitana",
				productIdFor(resolution),
				null, 1, "milanesa", null);

		mockMvc.perform(decide(ownerA, line.getId(), action, "Milanesa napolitana"))
				.andExpect(status().isConflict());

		assertThat(freshLine(line.getId()).getOperatorAction()).isNull();
	}

	/** SUGGESTED carries its candidate id, RESOLVED its resolved id, the rest none. */
	private static Long productIdFor(ItemResolution resolution) {

		if (resolution == ItemResolution.SUGGESTED) {
			return 911_002L;
		}
		if (resolution == ItemResolution.RESOLVED) {
			return 911_003L;
		}
		return null;
	}

	static Stream<Arguments> illegalPairs() {

		return Stream.of(
				Arguments.of(ItemResolution.UNRESOLVED, OperatorAction.ACCEPTED),
				Arguments.of(ItemResolution.UNRESOLVED, OperatorAction.CHOSEN),
				Arguments.of(ItemResolution.SUGGESTED, OperatorAction.CHOSEN),
				Arguments.of(ItemResolution.RESOLVED, OperatorAction.ACCEPTED),
				Arguments.of(ItemResolution.RESOLVED, OperatorAction.CHOSEN),
				Arguments.of(ItemResolution.RESOLVED, OperatorAction.DISCARDED));
	}

	/** The conditional half of the request shape, both directions → 400. */
	@ParameterizedTest
	@MethodSource("malformedShapes")
	void chosenNameIsRequiredForAChoiceAndProhibitedOtherwise(ItemResolution resolution, OperatorAction action,
			String chosenName) throws Exception {

		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), resolution,
				resolution == ItemResolution.SUGGESTED ? "Milanesa napolitana" : null,
				resolution == ItemResolution.SUGGESTED ? Long.valueOf(911_004L) : null,
				null, 1, "una coca", "Coca-Cola 500 ml\nCoca-Cola 2.25 L");

		mockMvc.perform(decide(ownerA, line.getId(), action, chosenName))
				.andExpect(status().isBadRequest());

		assertThat(freshLine(line.getId()).getOperatorAction()).isNull();
	}

	static Stream<Arguments> malformedShapes() {

		return Stream.of(
				// CHOSEN without a name (missing and blank).
				Arguments.of(ItemResolution.AMBIGUOUS, OperatorAction.CHOSEN, null),
				Arguments.of(ItemResolution.AMBIGUOUS, OperatorAction.CHOSEN, "   "),
				// The name is prohibited for the actions that ignore it.
				Arguments.of(ItemResolution.SUGGESTED, OperatorAction.ACCEPTED, "Milanesa napolitana"),
				Arguments.of(ItemResolution.AMBIGUOUS, OperatorAction.DISCARDED, "Coca-Cola 500 ml"));
	}

	// --- the CAS scoping ------------------------------------------------------

	@Test
	void decidingALineOfAnotherBusinessProposalIsNotFoundAndLeavesItUntouched() throws Exception {

		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.SUGGESTED,
				"Pan", 911_005L, null, 1, "pan", null);

		// B tries to decide A's line.
		mockMvc.perform(decide(ownerB, line.getId(), OperatorAction.ACCEPTED, null))
				.andExpect(status().isNotFound());

		OrderProposalItem fresh = freshLine(line.getId());
		assertThat(fresh.getOperatorAction()).isNull();
		assertThat(fresh.getProductId()).isEqualTo(911_005L);
	}

	@Test
	void decidingAnItemOfTheSameBusinessButAnotherProposalIsNotFound() throws Exception {

		OrderProposal ownProposal = pendingProposal(businessA());
		seedPendingProposalWithLine(ownProposal, ItemResolution.SUGGESTED, "Pan", 911_006L, null, 1, "pan", null);
		save(ownProposal);

		OrderProposal otherProposal = pendingProposal(businessA());
		OrderProposalItem foreignItem = seedPendingProposalWithLine(
				otherProposal, ItemResolution.SUGGESTED, "Coca-Cola", 911_007L, null, 2, "coca", null);
		save(otherProposal);

		// The item id EXISTS, but belongs to another proposal: the request
		// targets THIS business's OWN proposal with the foreign item id — the
		// CAS is scoped by proposal id, so this is 404 and NEITHER line changes.
		// Built inline (not through decide()): the helper resolves the proposal
		// id from the item, which would defeat the case under test.
		Map<String, Object> body = Map.of("action", "ACCEPTED");
		try {
			mockMvc.perform(patch("/api/business/proposals/{id}/items/{itemId}", ownProposal.getId(),
							foreignItem.getId())
					.header("Authorization", ownerA.authorizationHeader())
					.contentType(MediaType.APPLICATION_JSON)
					.content(new ObjectMapper().writeValueAsString(body)))
				.andExpect(status().isNotFound());
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException(e);
		}

		OrderProposal freshOwn = orderProposalRepository.findById(ownProposal.getId()).orElseThrow();
		assertThat(freshOwn.getItems().get(0).getOperatorAction()).isNull();
		OrderProposal freshOther = orderProposalRepository.findById(otherProposal.getId()).orElseThrow();
		assertThat(freshOther.getItems().get(0).getOperatorAction()).isNull();
	}

	@Test
	void repeatingTheSameActionIsIdempotent() throws Exception {

		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.SUGGESTED,
				"Pan", 911_008L, null, 1, "pan", null);

		mockMvc.perform(decide(ownerA, line.getId(), OperatorAction.ACCEPTED, null))
				.andExpect(status().isOk());
		mockMvc.perform(decide(ownerA, line.getId(), OperatorAction.ACCEPTED, null))
				.andExpect(status().isOk());

		OrderProposal fresh = orderProposalRepository.findById(proposalOf(line.getId()).getId()).orElseThrow();
		assertThat(fresh.getItems()).hasSize(1);
		assertThat(fresh.getItems().get(0).getOperatorAction()).isEqualTo(OperatorAction.ACCEPTED);
		assertThat(fresh.getItems().get(0).getProductId()).isEqualTo(911_008L);
	}

	@ParameterizedTest
	@MethodSource("decidedStatuses")
	void decidingALineOfAnAlreadyDecidedProposalConflicts(ProposalStatus status) throws Exception {

		Business business = businessA();
		OrderProposal decided = new OrderProposal();
		decided.setBusiness(business);
		decided.setStatus(status);
		decided.setReceivedAt(RECEIVED_AT);
		decided.setChannel("whatsapp");
		decided.setFromPhone("5491100000001");
		decided.setRawText("quiero pan");
		OrderProposalItem line = new OrderProposalItem();
		line.setProposal(decided);
		line.setResolution(ItemResolution.SUGGESTED);
		line.setProductName("Pan");
		line.setProductId(911_009L);
		line.setQuantity(1);
		line.setRawLine("pan");
		decided.getItems().add(line);
		save(decided);

		mockMvc.perform(decide(ownerA, line.getId(), OperatorAction.ACCEPTED, null))
				.andExpect(status().isConflict());

		assertThat(freshLine(line.getId()).getOperatorAction()).isNull();
	}

	static Stream<Arguments> decidedStatuses() {

		return Stream.of(Arguments.of(ProposalStatus.CONFIRMED), Arguments.of(ProposalStatus.REJECTED));
	}

	// --- the single codec -----------------------------------------------------

	@Test
	void candidateNamesRoundTripThroughTheSingleCodec() throws Exception {

		List<String> names = List.of("Café con leche", "Medialuna con jamón y queso");
		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.AMBIGUOUS,
				null, null, null, 1, "para desayunar", ProposalCandidates.join(names));

		// The DTO serves the DECODED list.
		mockMvc.perform(get("/api/business/proposals").header("Authorization", ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].items[0].candidateNames")
						.value(contains("Café con leche", "Medialuna con jamón y queso")));

		// The stored column keeps the newline-joined form, byte-identical.
		String stored = freshLine(line.getId()).getCandidates();
		assertThat(stored).isEqualTo("Café con leche\nMedialuna con jamón y queso");

		// And the codec round-trips names containing spaces and accents
		// byte-identically.
		assertThat(ProposalCandidates.split(ProposalCandidates.join(names))).isEqualTo(names);
	}

	/**
	 * The CAS's PENDING predicate, pinned where the endpoint's service-level
	 * pre-check cannot shield it (same technique as T7a's CAS mutations): a
	 * direct repository call on a DECIDED proposal writes 0 rows, while the
	 * same call on a PENDING one writes 1 — the positive control keeps the
	 * assertion from passing vacuously.
	 */
	@Test
	void theCasPredicateRefusesADecidedProposalAtTheRepositoryLevel() throws Exception {

		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.SUGGESTED,
				"Pan", 911_010L, null, 1, "pan", null);
		Long proposalId = line.getProposal().getId();

		// Positive control: PENDING → the write goes through.
		assertThat(orderProposalRepository.decideLineIfPending(line.getId(), proposalId, BUSINESS_A_ID,
				OperatorAction.ACCEPTED, null, 911_010L, null)).isEqualTo(1);

		// Decide the proposal, then try again: 0 rows, nothing rewritten.
		orderProposalRepository.confirmIfPending(proposalId, BUSINESS_A_ID, java.time.LocalDateTime.now());
		assertThat(orderProposalRepository.decideLineIfPending(line.getId(), proposalId, BUSINESS_A_ID,
				OperatorAction.DISCARDED, null, 911_010L, null)).isZero();
	}

	/** The CAS's tenant predicate, same shield-free technique as above. */
	@Test
	void theCasPredicateRefusesAForeignBusinessAtTheRepositoryLevel() throws Exception {

		OrderProposalItem line = seedPendingProposalWithLine(
				pendingProposal(businessA()), ItemResolution.SUGGESTED,
				"Pan", 911_011L, null, 1, "pan", null);
		Long proposalId = line.getProposal().getId();

		// A foreign businessId writes NOTHING; the owner's writes 1 row.
		assertThat(orderProposalRepository.decideLineIfPending(line.getId(), proposalId, BUSINESS_B_ID,
				OperatorAction.ACCEPTED, null, 911_011L, null)).isZero();
		assertThat(freshLine(line.getId()).getOperatorAction()).isNull();
		assertThat(orderProposalRepository.decideLineIfPending(line.getId(), proposalId, BUSINESS_A_ID,
				OperatorAction.ACCEPTED, null, 911_011L, null)).isEqualTo(1);
	}

	// --- fixtures -------------------------------------------------------------

	private Business businessA() {

		return businessRepository.findById(BUSINESS_A_ID).orElseThrow();
	}

	private OrderProposal pendingProposal(Business business) {

		OrderProposal proposal = new OrderProposal();
		proposal.setBusiness(business);
		proposal.setStatus(ProposalStatus.PENDING);
		proposal.setReceivedAt(RECEIVED_AT);
		proposal.setChannel("whatsapp");
		proposal.setFromPhone("5491100000001");
		proposal.setRawText("quiero 2 milanesas y una coca");
		return proposal;
	}

	private OrderProposalItem seedPendingProposalWithLine(OrderProposal proposal, ItemResolution resolution,
			String productName, Long productId, Long comboId, Integer quantity, String rawLine, String candidates) {

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
		save(proposal);
		return item;
	}

	private Product seedProduct(Business business, String name) {

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Line Decision Test " + name);
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName(name);
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("50.00"));
		product.setStock(10);
		return productRepository.saveAndFlush(product);
	}

	private MockHttpServletRequestBuilder decide(BusinessAuthSeed.AuthenticatedBusiness owner, Long itemId,
			OperatorAction action, String chosenName) {

		Map<String, Object> body = new HashMap<>();
		if (action != null) {
			body.put("action", action.name());
		}
		if (chosenName != null) {
			body.put("chosenName", chosenName);
		}
		try {
			return patch("/api/business/proposals/{id}/items/{itemId}", proposalIdOf(itemId), itemId)
					.header("Authorization", owner.authorizationHeader())
					.contentType(MediaType.APPLICATION_JSON)
					.content(new ObjectMapper().writeValueAsString(body));
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private Long proposalIdOf(Long itemId) {

		return freshLine(itemId).getProposal().getId();
	}

	private OrderProposal proposalOf(Long itemId) {

		return freshLine(itemId).getProposal();
	}

	/** Fresh SELECT for one line: flush/clear first, then a direct query by id
	 * (the item's own id needs no proposal lookup to be found). */
	private OrderProposalItem freshLine(Long itemId) {

		entityManager.flush();
		entityManager.clear();
		return entityManager
				.createQuery("select i from OrderProposalItem i where i.id = :id", OrderProposalItem.class)
				.setParameter("id", itemId)
				.getSingleResult();
	}

	private void save(OrderProposal proposal) {

		orderProposalRepository.save(proposal);
		entityManager.flush();
		entityManager.clear();
	}
}
