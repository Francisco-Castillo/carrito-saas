package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.ComboProduct;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.service.impl.OrderProposalServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * HTTP contract of T7a.2 — CONFIRM an order proposal
 * ({@code odd/tasks/whatsapp-inbound.md}, T7a.2 work unit, ejes 1/3/4/5).
 *
 * <p>Contract pinned here:</p>
 * <ul>
 *   <li>the confirmed order is IDENTICAL to the same order placed through the
 *     menu path (AC9) — compared against a REAL menu-created order, never a
 *     hardcoded golden;</li>
 *   <li>the created order id is recorded on the proposal;</li>
 *   <li>double confirmation produces ONE order and ONE stock decrement
 *     (AC10) — the second call 409s, and a direct re-claim through the CAS
 *     also reports 0 rows;</li>
 *   <li>EVERY non-RESOLVED line blocks the whole confirmation (one case per
 *     value, including a SUGGESTED line carrying only a comboId), as does a
 *     RESOLVED line without exactly one placeable id — 409, no order, stock
 *     untouched, proposal still PENDING;</li>
 *   <li>the operator data the proposal lacks is required: blank name with a
 *     blank draft → 400, null orderType/paymentMethod → 400, DELIVERY
 *     without address → 400, RETIRO without address → accepted; the operator
 *     name wins over the draft, and the draft fills in when the operator
 *     omits it;</li>
 *   <li>authorization: a foreign proposal is 404 and untouched (a direct
 *     cross-tenant CAS re-claim also reports 0 rows); FAILED without business
 *     is 404; REJECTED/CONFIRMED are 409;</li>
 *   <li>the {@code uq_order_proposals_order_id} UNIQUE constraint EXISTS and
 *     REJECTS a second attribution — asserted by provoking the violation on
 *     flush, not by reading the declaration.</li>
 * </ul>
 *
 * <p><strong>The writer-failure rollback test does NOT live here — measured,
 * not assumed.</strong> In a {@code @Transactional} test the service's
 * REQUIRED transaction JOINS the test transaction: the failed writer only
 * marks it rollback-only, the already-executed CAS UPDATE stays applied, and
 * the fresh read sees CONFIRMED, never the rolled-back PENDING. The property
 * "a failed writer rolls the claim back" is only observable when the service
 * runs in its OWN transaction, so that single case moved to the deliberately
 * non-transactional {@link OrderProposalConfirmationRollbackTests} — the
 * escape hatch the feature document prescribed, with the
 * {@code WhatsappIdempotencyRaceTests} pattern (suite-unique marker,
 * child-before-parent cleanup; gaps 37/39/40). The OTHER 404/409/500 cases
 * did NOT need it: subsequent flush/clear and reads still execute after a
 * rollback-only marking (same precedent as
 * {@code OrderProposalListAndRejectTests}).</p>
 *
 * <p><strong>Transactional poisoning note.</strong> Every test here that
 * provokes a constraint violation puts it LAST so nothing else depends on the
 * poisoned transaction.</p>
 */
@SpringBootTest
@Transactional
class OrderProposalConfirmationTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_700_901L;
	private static final Long BUSINESS_B_ID = 987_700_902L;

	private static final String PHONE = "5491190000001";

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
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private ComboRepository comboRepository;

	@Autowired
	private OrderProposalRepository orderProposalRepository;

	@Autowired
	private OrderRepository orderRepository;

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
				businessUserRepository, jwtUtil, BUSINESS_A_ID, "confirm-a", "confirm-owner-a");
		ownerB = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_B_ID, "confirm-b", "confirm-owner-b");
	}

	// --- fixtures -------------------------------------------------------------

	private Category seedCategory(Business business, String name) {

		Category category = new Category();
		category.setBusiness(business);
		category.setName(name);
		return categoryRepository.saveAndFlush(category);
	}

	private Product seedProduct(Business business, Category category, String name, String price, int stock) {

		Product product = new Product();
		product.setCategory(category);
		product.setName(name);
		product.setPrice(new BigDecimal(price));
		product.setCost(new BigDecimal("10.00"));
		product.setStock(stock);
		product.setActive(true);
		return productRepository.saveAndFlush(product);
	}

	private Combo seedCombo(Business business, Category category, String name, String price, Product component,
			int componentQuantity) {

		Combo combo = new Combo();
		combo.setCategory(category);
		combo.setName(name);
		combo.setPrice(new BigDecimal(price));

		ComboProduct comboProduct = new ComboProduct();
		comboProduct.setCombo(combo);
		comboProduct.setProduct(component);
		comboProduct.setQuantity(BigDecimal.valueOf(componentQuantity));
		combo.setItems(new ArrayList<>(List.of(comboProduct)));

		return comboRepository.saveAndFlush(combo);
	}

	private OrderProposal seedProposal(Business business, ProposalStatus status, String fromPhone,
			String customerName) {

		OrderProposal proposal = new OrderProposal();
		proposal.setBusiness(business);
		proposal.setStatus(status);
		proposal.setChannel("whatsapp");
		proposal.setFromPhone(fromPhone);
		proposal.setCustomerName(customerName);
		proposal.setRawText("quiero 2 milanesas");
		proposal.setFailureReason(status == ProposalStatus.FAILED ? "telefono sin negocio" : null);
		return proposal;
	}

	private OrderProposalItem attachLine(OrderProposal proposal, ItemResolution resolution, String productName,
			Long productId, Long comboId, Integer quantity) {

		OrderProposalItem item = new OrderProposalItem();
		item.setProposal(proposal);
		item.setResolution(resolution);
		item.setProductName(productName);
		item.setProductId(productId);
		item.setComboId(comboId);
		item.setQuantity(quantity);
		item.setRawLine(productName != null ? productName : "linea sin nombre");
		proposal.getItems().add(item);
		return item;
	}

	private OrderProposal save(OrderProposal proposal) {

		OrderProposal saved = orderProposalRepository.saveAndFlush(proposal);
		entityManager.flush();
		entityManager.clear();
		return saved;
	}

	/** Builds a confirm request body from only the fields that are present. */
	private String confirmBody(String customerName, String orderType, String paymentMethod, String customerAddress,
			String notes) throws Exception {

		Map<String, Object> body = new HashMap<>();
		if (customerName != null) {
			body.put("customerName", customerName);
		}
		if (orderType != null) {
			body.put("orderType", orderType);
		}
		if (paymentMethod != null) {
			body.put("paymentMethod", paymentMethod);
		}
		if (customerAddress != null) {
			body.put("customerAddress", customerAddress);
		}
		if (notes != null) {
			body.put("notes", notes);
		}
		return new ObjectMapper().writeValueAsString(body);
	}

	private long confirmAndGetOrderId(OrderProposal proposal) throws Exception {

		String response = mockMvc.perform(post("/api/business/proposals/{id}/confirm", proposal.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente Confirmado", "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return new ObjectMapper().readTree(response).get("orderId").asLong();
	}

	// --- AC9: the confirmed order is identical to the menu path ---------------

	@Test
	void confirmedOrderIsIdenticalToTheSameOrderPlacedThroughTheMenu() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "Confirm Menu");
		Product milanesa = seedProduct(businessA, category, "Milanesa napolitana", "100.00", 10);
		Product coca = seedProduct(businessA, category, "Coca-Cola", "50.00", 10);
		Combo combo = seedCombo(businessA, category, "Combo feliz", "250.00", coca, 2);

		// The proposal carries the SAME lines the menu request will carry.
		OrderProposal proposal = seedProposal(businessA, ProposalStatus.PENDING, PHONE, "Cliente Comparado");
		attachLine(proposal, ItemResolution.RESOLVED, "Milanesa napolitana", milanesa.getId(), null, 2);
		attachLine(proposal, ItemResolution.RESOLVED, "Combo feliz", null, combo.getId(), 1);
		proposal = save(proposal);

		// The REAL menu path, not a golden.
		String menuBody = """
				{
				  "customerName": "Cliente Comparado",
				  "customerPhone": "%s",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "notes": "igual",
				  "items": [ { "productId": %d, "quantity": 2 }, { "comboId": %d, "quantity": 1 } ]
				}
				""".formatted(PHONE, milanesa.getId(), combo.getId());

		String menuResponse = mockMvc.perform(post("/api/orders/menu/" + businessA.getSlug())
						.contentType(MediaType.APPLICATION_JSON)
						.content(menuBody))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		long menuOrderId = new ObjectMapper().readTree(menuResponse).get("orderId").asLong();

		// The confirm path with the SAME lines and operator data.
		String confirmResponse = mockMvc.perform(post("/api/business/proposals/{id}/confirm", proposal.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente Comparado", "RETIRO", "EFECTIVO", null, "igual")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		long confirmedOrderId = new ObjectMapper().readTree(confirmResponse).get("orderId").asLong();

		entityManager.flush();
		entityManager.clear();

		// POSITIVE CONTROL: both orders exist and are non-empty before comparing.
		Order menuOrder = orderRepository.findById(menuOrderId).orElseThrow();
		Order confirmedOrder = orderRepository.findById(confirmedOrderId).orElseThrow();
		assertThat(menuOrder.getItems()).isNotEmpty();
		assertThat(confirmedOrder.getItems()).isNotEmpty();

		assertThat(confirmedOrder.getStatus()).isEqualTo(OrderStatus.NEW);
		assertThat(confirmedOrder.getCustomerName()).isEqualTo(menuOrder.getCustomerName());
		assertThat(confirmedOrder.getCustomerPhone()).isEqualTo(menuOrder.getCustomerPhone());
		assertThat(confirmedOrder.getOrderType()).isEqualTo(menuOrder.getOrderType());
		assertThat(confirmedOrder.getPaymentMethod()).isEqualTo(menuOrder.getPaymentMethod());
		assertThat(confirmedOrder.getNotes()).isEqualTo(menuOrder.getNotes());
		assertThat(confirmedOrder.getTotal()).isEqualByComparingTo(menuOrder.getTotal());

		// Items compared by name so the collection ordering cannot flake:
		// same products, quantities, unit prices and subtotals.
		List<OrderItem> menuItems = menuOrder.getItems().stream()
				.sorted(java.util.Comparator.comparing(OrderItem::getProductName)).toList();
		List<OrderItem> confirmedItems = confirmedOrder.getItems().stream()
				.sorted(java.util.Comparator.comparing(OrderItem::getProductName)).toList();

		assertThat(confirmedItems).hasSameSizeAs(menuItems);
		for (int i = 0; i < menuItems.size(); i++) {
			OrderItem menu = menuItems.get(i);
			OrderItem confirmed = confirmedItems.get(i);
			assertThat(confirmed.getProductName()).isEqualTo(menu.getProductName());
			assertThat(confirmed.getQuantity()).isEqualTo(menu.getQuantity());
			assertThat(confirmed.getPrice()).isEqualByComparingTo(menu.getPrice());
			assertThat(confirmed.getSubtotal()).isEqualByComparingTo(menu.getSubtotal());
			assertThat(confirmed.getComboRoot()).isEqualTo(menu.getComboRoot());
		}
	}

	// --- order_id recorded ------------------------------------------------------

	@Test
	void confirmationRecordsTheCreatedOrderIdOnTheProposal() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "Record");
		Product product = seedProduct(businessA, category, "Producto grabado", "100.00", 5);

		OrderProposal proposal = seedProposal(businessA, ProposalStatus.PENDING, PHONE, null);
		attachLine(proposal, ItemResolution.RESOLVED, "Producto grabado", product.getId(), null, 1);
		proposal = save(proposal);

		long orderId = confirmAndGetOrderId(proposal);

		entityManager.flush();
		entityManager.clear();

		OrderProposal fresh = orderProposalRepository.findById(proposal.getId()).orElseThrow();
		assertThat(fresh.getStatus()).isEqualTo(ProposalStatus.CONFIRMED);
		assertThat(fresh.getOrderId()).isEqualTo(orderId);
	}

	// --- AC10: double confirm, one order, one decrement ------------------------

	@Test
	void confirmingTwiceCreatesOneOrderAndDecrementsStockOnce() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "Double");
		Product product = seedProduct(businessA, category, "Producto doble", "100.00", 7);

		OrderProposal proposal = seedProposal(businessA, ProposalStatus.PENDING, PHONE, null);
		attachLine(proposal, ItemResolution.RESOLVED, "Producto doble", product.getId(), null, 2);
		proposal = save(proposal);

		long ordersBefore = orderRepository.count();

		long firstOrderId = confirmAndGetOrderId(proposal);

		entityManager.flush();
		entityManager.clear();
		assertThat(orderRepository.count()).isEqualTo(ordersBefore + 1);
		assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);

		// Second confirmation → 409, and NOTHING else is created or decremented.
		mockMvc.perform(post("/api/business/proposals/{id}/confirm", proposal.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody(null, "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isConflict());

		entityManager.flush();
		entityManager.clear();

		assertThat(orderRepository.count()).isEqualTo(ordersBefore + 1);
		assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);

		OrderProposal fresh = orderProposalRepository.findById(proposal.getId()).orElseThrow();
		assertThat(fresh.getStatus()).isEqualTo(ProposalStatus.CONFIRMED);
		assertThat(fresh.getOrderId()).isEqualTo(firstOrderId);

		// A direct re-claim through the CAS also loses: the status predicate
		// is what denies it (mutation sensitivity: removing
		// "status = 'PENDING'" from the CAS makes THIS assertion fail).
		assertThat(orderProposalRepository.confirmIfPending(proposal.getId(), BUSINESS_A_ID, LocalDateTime.now()))
				.isZero();
	}

	// --- every non-RESOLVED line blocks -----------------------------------------

	static Stream<org.junit.jupiter.params.provider.Arguments> nonResolvedLines() {

		return Stream.of(
				// A SUGGESTED line legitimately carries ONE candidate id: product…
				org.junit.jupiter.params.provider.Arguments.of(ItemResolution.SUGGESTED, 1L, null),
				// …and a SUGGESTED COMBO carries only a comboId.
				org.junit.jupiter.params.provider.Arguments.of(ItemResolution.SUGGESTED, null, 1L),
				// AMBIGUOUS and UNRESOLVED carry no ids at all.
				org.junit.jupiter.params.provider.Arguments.of(ItemResolution.AMBIGUOUS, null, null),
				org.junit.jupiter.params.provider.Arguments.of(ItemResolution.UNRESOLVED, null, null));
	}

	@ParameterizedTest
	@MethodSource("nonResolvedLines")
	void everyNonResolvedLineBlocksTheConfirmation(ItemResolution resolution, Long productId, Long comboId)
			throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "Blocked " + resolution);
		Product product = seedProduct(businessA, category, "Producto bloqueo", "100.00", 5);

		OrderProposal proposal = seedProposal(businessA, ProposalStatus.PENDING, PHONE, null);
		attachLine(proposal, ItemResolution.RESOLVED, "Producto bloqueo", product.getId(), null, 1);
		attachLine(proposal, resolution,
				productId != null || comboId != null ? "Linea " + resolution : null,
				productId != null ? product.getId() : null,
				comboId,
				productId != null || comboId != null ? 1 : null);
		proposal = save(proposal);

		long ordersBefore = orderRepository.count();

		mockMvc.perform(post("/api/business/proposals/{id}/confirm", proposal.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isConflict());

		entityManager.flush();
		entityManager.clear();

		// No order, no stock, and the proposal is STILL pending on a fresh read.
		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
		OrderProposal fresh = orderProposalRepository.findById(proposal.getId()).orElseThrow();
		assertThat(fresh.getStatus()).isEqualTo(ProposalStatus.PENDING);
		assertThat(fresh.getOrderId()).isNull();
	}

	@Test
	void aResolvedLineWithoutAPlaceableIdBlocks() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "No id");
		Product product = seedProduct(businessA, category, "Producto sin id", "100.00", 5);

		OrderProposal proposal = seedProposal(businessA, ProposalStatus.PENDING, PHONE, null);
		attachLine(proposal, ItemResolution.RESOLVED, "Producto sin id", null, null, 1);
		proposal = save(proposal);

		long ordersBefore = orderRepository.count();

		mockMvc.perform(post("/api/business/proposals/{id}/confirm", proposal.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isConflict());

		entityManager.flush();
		entityManager.clear();

		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
	}

	@Test
	void aResolvedLineWithBothIdsBlocks() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "Both ids");
		Product product = seedProduct(businessA, category, "Producto ambos", "100.00", 5);

		OrderProposal proposal = seedProposal(businessA, ProposalStatus.PENDING, PHONE, null);
		attachLine(proposal, ItemResolution.RESOLVED, "Producto ambos", product.getId(), 123L, 1);
		proposal = save(proposal);

		long ordersBefore = orderRepository.count();

		mockMvc.perform(post("/api/business/proposals/{id}/confirm", proposal.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isConflict());

		entityManager.flush();
		entityManager.clear();

		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
	}

	// --- operator data (eje 1) ---------------------------------------------------

	@Test
	void confirmationRequiresTheOperatorDataThatTheProposalDoesNotHave() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "Operator data");
		Product product = seedProduct(businessA, category, "Producto datos", "100.00", 5);

		// Blank name with a BLANK draft → 400. (Every case below carries a
		// confirmable proposal: the colocability gate runs BEFORE operator-data
		// validation, so a line-less proposal would 409 at the gate and never
		// reach the 400s under test.)
		OrderProposal noDraft = save(seedProposal(businessA, ProposalStatus.PENDING, PHONE, null));
		attachLine(noDraft, ItemResolution.RESOLVED, "Producto datos", product.getId(), null, 1);
		noDraft = save(noDraft);
		mockMvc.perform(post("/api/business/proposals/{id}/confirm", noDraft.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("   ", "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isBadRequest());

		// Null orderType → 400.
		OrderProposal noType = save(seedProposal(businessA, ProposalStatus.PENDING, PHONE, null));
		attachLine(noType, ItemResolution.RESOLVED, "Producto datos", product.getId(), null, 1);
		noType = save(noType);
		mockMvc.perform(post("/api/business/proposals/{id}/confirm", noType.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", null, "EFECTIVO", null, null)))
				.andExpect(status().isBadRequest());

		// Null paymentMethod → 400.
		OrderProposal noPayment = save(seedProposal(businessA, ProposalStatus.PENDING, PHONE, null));
		attachLine(noPayment, ItemResolution.RESOLVED, "Producto datos", product.getId(), null, 1);
		noPayment = save(noPayment);
		mockMvc.perform(post("/api/business/proposals/{id}/confirm", noPayment.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", "RETIRO", null, null, null)))
				.andExpect(status().isBadRequest());

		// DELIVERY without address → 400.
		OrderProposal delivery = save(seedProposal(businessA, ProposalStatus.PENDING, PHONE, null));
		attachLine(delivery, ItemResolution.RESOLVED, "Producto datos", product.getId(), null, 1);
		delivery = save(delivery);
		mockMvc.perform(post("/api/business/proposals/{id}/confirm", delivery.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", "DELIVERY", "EFECTIVO", null, null)))
				.andExpect(status().isBadRequest());

		entityManager.flush();
		entityManager.clear();

		// None of the rejected calls consumed a proposal or created an order.
		assertThat(orderProposalRepository.findById(noDraft.getId()).orElseThrow().getStatus())
				.isEqualTo(ProposalStatus.PENDING);
		assertThat(orderProposalRepository.findById(delivery.getId()).orElseThrow().getStatus())
				.isEqualTo(ProposalStatus.PENDING);

		// POSITIVE: RETIRO without address IS accepted.
		OrderProposal retiro = seedProposal(businessA, ProposalStatus.PENDING, PHONE, null);
		attachLine(retiro, ItemResolution.RESOLVED, "Producto datos", product.getId(), null, 1);
		retiro = save(retiro);

		mockMvc.perform(post("/api/business/proposals/{id}/confirm", retiro.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").isNotEmpty());
	}

	@Test
	void customerNameFallsBackToTheDraftAndTheOperatorsValueWins() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "Name fallback");
		Product product = seedProduct(businessA, category, "Producto nombre", "100.00", 5);

		// Omitted name → the draft is used.
		OrderProposal withDraft = save(seedProposal(businessA, ProposalStatus.PENDING, PHONE, "Juanpi"));
		attachLine(withDraft, ItemResolution.RESOLVED, "Producto nombre", product.getId(), null, 1);
		withDraft = save(withDraft);

		long draftOrderId = confirmWithName(withDraft, null);

		// Operator's value WINS over the draft.
		OrderProposal corrected = save(seedProposal(businessA, ProposalStatus.PENDING, PHONE, "Juanpi"));
		attachLine(corrected, ItemResolution.RESOLVED, "Producto nombre", product.getId(), null, 1);
		corrected = save(corrected);

		long correctedOrderId = confirmWithName(corrected, "Juan Pérez");

		entityManager.flush();
		entityManager.clear();

		assertThat(orderRepository.findById(draftOrderId).orElseThrow().getCustomerName()).isEqualTo("Juanpi");
		assertThat(orderRepository.findById(correctedOrderId).orElseThrow().getCustomerName())
				.isEqualTo("Juan Pérez");
	}

	private long confirmWithName(OrderProposal proposal, String customerName) throws Exception {

		String response = mockMvc.perform(post("/api/business/proposals/{id}/confirm", proposal.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody(customerName, "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return new ObjectMapper().readTree(response).get("orderId").asLong();
	}

	// --- authorization and lifecycle (ejes 3/5) -----------------------------------

	@Test
	void confirmingAProposalOfAnotherBusinessIsNotFoundAndCreatesNothing() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "Cross tenant");
		Product product = seedProduct(businessA, category, "Producto ajeno", "100.00", 5);

		OrderProposal foreign = seedProposal(businessA, ProposalStatus.PENDING, PHONE, null);
		attachLine(foreign, ItemResolution.RESOLVED, "Producto ajeno", product.getId(), null, 1);
		foreign = save(foreign);

		long ordersBefore = orderRepository.count();

		// B tries to confirm A's proposal → 404 (never 403: no existence leak).
		mockMvc.perform(post("/api/business/proposals/{id}/confirm", foreign.getId())
						.header("Authorization", ownerB.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		// No order for either business, the foreign proposal is untouched.
		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(orderProposalRepository.findById(foreign.getId()).orElseThrow().getStatus())
				.isEqualTo(ProposalStatus.PENDING);

		// The CAS itself is tenant-scoped: B's direct re-claim reports 0 rows
		// (mutation sensitivity: removing "business.id = :businessId" from the
		// CAS makes THIS assertion fail).
		assertThat(orderProposalRepository.confirmIfPending(foreign.getId(), BUSINESS_B_ID, LocalDateTime.now()))
				.isZero();

		entityManager.flush();
		entityManager.clear();
		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
	}

	@Test
	void confirmingARejectedOrConfirmedProposalConflicts() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "Decided");
		Product product = seedProduct(businessA, category, "Producto decidido", "100.00", 5);

		OrderProposal rejected = seedProposal(businessA, ProposalStatus.REJECTED, PHONE, null);
		attachLine(rejected, ItemResolution.RESOLVED, "Producto decidido", product.getId(), null, 1);
		rejected = save(rejected);

		OrderProposal confirmed = seedProposal(businessA, ProposalStatus.CONFIRMED, PHONE, null);
		attachLine(confirmed, ItemResolution.RESOLVED, "Producto decidido", product.getId(), null, 1);
		confirmed = save(confirmed);

		long ordersBefore = orderRepository.count();

		mockMvc.perform(post("/api/business/proposals/{id}/confirm", rejected.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isConflict());

		mockMvc.perform(post("/api/business/proposals/{id}/confirm", confirmed.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isConflict());

		entityManager.flush();
		entityManager.clear();

		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
	}

	@Test
	void confirmingAFailedProposalWithoutBusinessIsNotFound() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		// A FAILED proposal has NO business at all — it is confirmable by NOBODY.
		OrderProposal failed = new OrderProposal();
		failed.setBusiness(null);
		failed.setStatus(ProposalStatus.FAILED);
		failed.setChannel("whatsapp");
		failed.setFromPhone(PHONE);
		failed.setRawText("texto sin destino");
		failed.setFailureReason("telefono sin negocio");
		failed = save(failed);

		long ordersBefore = orderRepository.count();

		mockMvc.perform(post("/api/business/proposals/{id}/confirm", failed.getId())
						.header("Authorization", ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody("Cliente", "RETIRO", "EFECTIVO", null, null)))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
	}

	// --- the UNIQUE constraint exists and rejects ---------------------------------

	@Test
	void theOrderIdUniqueConstraintRejectsASecondAttribution() throws Exception {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Category category = seedCategory(businessA, "Unique");
		Product product = seedProduct(businessA, category, "Producto unique", "100.00", 5);

		OrderProposal first = seedProposal(businessA, ProposalStatus.PENDING, PHONE, null);
		attachLine(first, ItemResolution.RESOLVED, "Producto unique", product.getId(), null, 1);
		first = save(first);

		long orderId = confirmAndGetOrderId(first);

		// Attribute the SAME order to a SECOND proposal: the constraint must
		// exist in the database and REJECT on flush — not merely be declared.
		Business reloadedBusinessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		OrderProposal second = seedProposal(reloadedBusinessA, ProposalStatus.PENDING, PHONE, null);
		second.setOrderId(orderId);

		// IDENTITY generation makes persist() issue the INSERT immediately, so
		// the violation surfaces on the save — Spring's @Repository translation
		// wraps it in DataIntegrityViolationException (measured: Hibernate 7 no
		// longer interposes ConstraintViolationException; the root cause is the
		// driver's PSQLException, which names the constraint).
		assertThatThrownBy(() -> {
			orderProposalRepository.save(second);
			entityManager.flush();
		})
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
				.getRootCause()
				.hasMessageContaining("uq_order_proposals_order_id");

		// This is deliberately the LAST statement of the test: the violated
		// flush poisons the transaction, and nothing else may depend on it.
	}

	// --- structural: the line mapping refuses to place non-RESOLVED lines ---------

	/**
	 * The gate ({@code isConfirmable}) makes the non-RESOLVED arms of the
	 * line→{@code OrderItemDTO} switch unreachable THROUGH the endpoint —
	 * which also means the endpoint tests above cannot kill the "someone adds
	 * a {@code default ->} that places the line" mutation by themselves. This
	 * test reaches the mapping directly and pins its contract: EVERY value of
	 * {@code ItemResolution} other than {@code RESOLVED} throws
	 * {@code IllegalStateException} instead of producing an order line.
	 *
	 * <p>The truth about what this net does and does not catch, stated
	 * honestly: iterating all values means that the day a NEW constant is
	 * added to {@code ItemResolution} and a placing {@code default ->} arm
	 * silently swallows it, THIS test goes red. It does NOT kill a dead
	 * {@code default} arm added ALONGSIDE the exhaustive arms today — such an
	 * arm is dead code today, so no behaviour test can reach it; that remains
	 * an EXPLICIT REVIEW OBLIGATION written on the switch. (A source-text/AST
	 * guard was considered and rejected as brittle.)</p>
	 */
	@Test
	void theLineMappingRefusesToPlaceNonResolvedLinesEvenWithoutTheGate() throws Exception {

		Method mapping = OrderProposalServiceImpl.class.getDeclaredMethod("toOrderItemDTO", OrderProposalItem.class);
		mapping.setAccessible(true);

		for (ItemResolution resolution : ItemResolution.values()) {
			if (resolution == ItemResolution.RESOLVED) {
				continue;
			}

			OrderProposalItem line = new OrderProposalItem();
			line.setResolution(resolution);
			// A SUGGESTED line legitimately carries a candidate id — the arm
			// must still refuse to place it.
			if (resolution == ItemResolution.SUGGESTED) {
				line.setProductId(1L);
			}
			line.setQuantity(1);

			assertThatThrownBy(() -> mapping.invoke(null, line))
					.isInstanceOf(InvocationTargetException.class)
					.hasRootCauseInstanceOf(IllegalStateException.class);
		}
	}

	// --- structural: recordOrderId is scoped to the proposal's business -------

	/**
	 * The T7a.2 attribution write is tenant-scoped: it may only attribute an
	 * order to a proposal OF the given business. This is pinned as a DIRECT
	 * REPOSITORY probe, and that level is the point, not a shortcut: the
	 * endpoint cannot reach this state — its only caller passes the proposal
	 * id it just claimed under the caller's own tenant and takes no order-id
	 * parameter from anywhere — so no HTTP test can exercise the missing
	 * predicate. The hole lives in the repository signature, so the probe
	 * lives there too, against the same query the service runs.
	 *
	 * <p>Positive control: attributing with A's OWN business id claims 1 row
	 * and writes {@code order_id}. Negative control: B's business id on A's
	 * proposal must claim 0 rows and write NOTHING — without the
	 * {@code business_id} predicate the negative control claims 1 row and
	 * attributes another business's proposal, and this test fails.</p>
	 */
	@Test
	void recordOrderIdIsScopedToTheProposalsBusiness() {

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		OrderProposal claimedA = save(seedProposal(businessA, ProposalStatus.CONFIRMED, PHONE, "Cliente Propio"));
		OrderProposal foreignTargetA = save(seedProposal(businessA, ProposalStatus.CONFIRMED, PHONE, "Cliente Ajeno"));

		// Positive control: the caller's own tenant claims exactly 1 row.
		int positive = orderProposalRepository.recordOrderId(claimedA.getId(), BUSINESS_A_ID, 777_001L,
				LocalDateTime.now());
		assertThat(positive).isEqualTo(1);
		assertThat(orderProposalRepository.findById(claimedA.getId()).orElseThrow().getOrderId())
				.isEqualTo(777_001L);

		// Negative control: a DIFFERENT business id on the SAME business's
		// proposal must claim 0 rows and leave order_id untouched.
		int negative = orderProposalRepository.recordOrderId(foreignTargetA.getId(), BUSINESS_B_ID, 777_002L,
				LocalDateTime.now());
		assertThat(negative).isZero();
		assertThat(orderProposalRepository.findById(foreignTargetA.getId()).orElseThrow().getOrderId()).isNull();
	}
}
