package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.CancellationReason;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CancellationReasonRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Positive wiring tests for the {@link OrderAnnouncer} extraction (T7a.2 of
 * {@code odd/tasks/whatsapp-inbound.md}, open gap 54).
 *
 * <p><strong>Why this class exists.</strong> Until T7a.2 NO test in the repo
 * proved that the menu order path broadcasts at all — the only broadcast
 * assertion was the NEGATIVE one of open gap 32 (the inbound path does not).
 * The extraction of the broadcast + metric into one collaborator could have
 * silently killed the menu broadcast with the whole suite green. These tests
 * pin the wiring positively:</p>
 *
 * <ul>
 *   <li>the menu path broadcasts EXACTLY once and counts the
 *     {@code pedidos.creados} metric EXACTLY once;</li>
 *   <li>proposal confirmation does the same (the new caller of
 *     {@code createOrder});</li>
 *   <li>status change and cancellation broadcast once each and increment
 *     NOTHING — the {@code orderCreated}/{@code orderChanged} split is
 *     load-bearing;</li>
 *   <li>a FAILED confirmation (the writer rolls back) broadcasts nothing and
 *     counts nothing — a rolled-back order must never leak into the
 *     kitchen.</li>
 * </ul>
 *
 * <p>{@code SimpMessagingTemplate} is a {@code @MockitoBean} so the send can
 * be observed without a broker; the REAL {@code MeterRegistry} is autowired,
 * and every metric assertion is a DELTA of the counter — the Spring context
 * is cached and shared across test classes, so absolute counts are
 * meaningless. Mockito's stubs/invocations are reset between tests by the
 * framework, so the per-test {@code times(1)} assertions are independent.</p>
 */
@SpringBootTest
@Transactional
class OrderBroadcastWiringTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_700_801L;
	private static final String SLUG = "broadcast-wiring";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@MockitoBean
	private SimpMessagingTemplate messagingTemplate;

	@Autowired
	private MeterRegistry registry;

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
	private OrderProposalRepository orderProposalRepository;

	@Autowired
	private CancellationReasonRepository cancellationReasonRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, "broadcast-owner");
	}

	private Product seedProduct(String name, String price, int stock) {

		Business business = businessRepository.findById(BUSINESS_ID).orElseThrow();
		Category category = new Category();
		category.setBusiness(business);
		category.setName(name + " category");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName(name);
		product.setPrice(new BigDecimal(price));
		product.setCost(new BigDecimal("10.00"));
		product.setStock(stock);
		product.setActive(true);
		return productRepository.saveAndFlush(product);
	}

	private OrderProposal seedConfirmableProposal(Product product, int quantity) {

		Business business = businessRepository.findById(BUSINESS_ID).orElseThrow();
		OrderProposal proposal = new OrderProposal();
		proposal.setBusiness(business);
		proposal.setStatus(ProposalStatus.PENDING);
		proposal.setChannel("whatsapp");
		proposal.setFromPhone("5491180000001");
		proposal.setCustomerName("Broadcast Draft");
		proposal.setRawText("quiero " + quantity + " " + product.getName());

		OrderProposalItem line = new OrderProposalItem();
		line.setProposal(proposal);
		line.setResolution(ItemResolution.RESOLVED);
		line.setProductId(product.getId());
		line.setProductName(product.getName());
		line.setQuantity(quantity);
		line.setRawLine(quantity + " " + product.getName());
		proposal.getItems().add(line);

		return orderProposalRepository.saveAndFlush(proposal);
	}

	private String confirmBody() {

		return """
				{
				  "customerName": "Broadcast Operator Name",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO"
				}
				""";
	}

	private double pedidosCreadosCount() {

		return registry.counter("pedidos.creados").count();
	}

	/**
	 * Until T7a.2 nothing proved the menu path broadcasts: this is the
	 * positive control that keeps the {@code OrderAnnouncer} extraction from
	 * silently killing the only existing order path.
	 */
	@Test
	void menuOrderIsBroadcastOnceAndCountsOnce() throws Exception {

		Product product = seedProduct("Broadcast Milanesa", "100.00", 5);

		double before = pedidosCreadosCount();

		String body = """
				{
				  "customerName": "Cliente Menu",
				  "customerPhone": "5491180000002",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(product.getId());

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").isNotEmpty());

		// EXACTLY one broadcast to the business topic, and the metric counted
		// EXACTLY one creation (delta: the context is shared across classes).
		verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/orders/" + SLUG), any(OrderDTO.class));
		assertThat(pedidosCreadosCount() - before).isEqualTo(1.0);
	}

	@Test
	void proposalConfirmationIsBroadcastOnceAndCountsOnce() throws Exception {

		Product product = seedProduct("Broadcast Pizza", "200.00", 5);
		OrderProposal proposal = seedConfirmableProposal(product, 1);

		double before = pedidosCreadosCount();

		mockMvc.perform(post("/api/business/proposals/{id}/confirm", proposal.getId())
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").isNotEmpty());

		entityManager.flush();
		entityManager.clear();

		verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/orders/" + SLUG), any(OrderDTO.class));
		assertThat(pedidosCreadosCount() - before).isEqualTo(1.0);
	}

	@Test
	void statusChangeAndCancelBroadcastWithoutCountingTheMetric() throws Exception {

		Product product = seedProduct("Broadcast Empanada", "80.00", 5);

		String body = """
				{
				  "customerName": "Cliente Kitchen",
				  "customerPhone": "5491180000003",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(product.getId());

		String response = mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		long orderId = new ObjectMapper().readTree(response).get("orderId").asLong();

		CancellationReason reason = new CancellationReason();
		reason.setCode("BROADCAST_WIRING_CANCEL");
		reason.setDescription("cancel used by the broadcast wiring suite");
		reason = cancellationReasonRepository.saveAndFlush(reason);

		clearInvocations(messagingTemplate);
		double before = pedidosCreadosCount();

		// Status change: broadcast once, metric UNTOUCHED.
		mockMvc.perform(patch("/api/orders/{id}/status", orderId)
						.header("Authorization", owner.authorizationHeader())
						.param("status", OrderStatus.PREPARING.name()))
				.andExpect(status().isOk());

		verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/orders/" + SLUG), any(OrderDTO.class));
		assertThat(pedidosCreadosCount() - before).isEqualTo(0.0);

		clearInvocations(messagingTemplate);

		// Cancellation: broadcast once, metric STILL untouched.
		mockMvc.perform(patch("/api/orders/{id}/cancel", orderId)
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reasonId\": %d, \"note\": \"wiring test\"}".formatted(reason.getId())))
				.andExpect(status().isOk());

		verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/orders/" + SLUG), any(OrderDTO.class));
		assertThat(pedidosCreadosCount() - before).isEqualTo(0.0);
	}

	@Test
	void failedConfirmationBroadcastsNothing() throws Exception {

		// Stock 5, ordered 10: the gate passes (the line is RESOLVED with an
		// id) but createOrder fails on stock — the response is 500 (documented
		// decision, gap 56) and the ROLLBACK must not leak an order into the
		// kitchen or the metric.
		Product product = seedProduct("Broadcast Scarce", "90.00", 5);
		OrderProposal proposal = seedConfirmableProposal(product, 10);

		double before = pedidosCreadosCount();

		mockMvc.perform(post("/api/business/proposals/{id}/confirm", proposal.getId())
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody()))
				.andExpect(status().isInternalServerError());

		verify(messagingTemplate, times(0)).convertAndSend(anyString(), any(OrderDTO.class));
		assertThat(pedidosCreadosCount() - before).isEqualTo(0.0);
	}
}
