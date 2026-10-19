package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.CancellationReason;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.CancellationReasonRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;

/**
 * Contract of the management day view: {@code GET /api/business/orders}
 * returns the CALLING business's orders for a date with ALL statuses —
 * including DELIVERED and CANCELLED, which {@code findActiveOrders} (the KDS
 * query) deliberately excludes — with the rich management payload (total,
 * paymentMethod, customerAddress, timeline, cancellation reason).
 *
 * <p>Tenant isolation is part of the contract: the business comes from the
 * JWT ({@code securityService.getCurrentBusinessId()}), never from the
 * request, so another business's orders must never leak into the response.
 * The date is optional and defaults to today (same server-local "today" the
 * dashboard uses); an explicit {@code ?date=YYYY-MM-DD} selects another day.</p>
 *
 * <p>MockMvc with the real security filter chain and a real JWT (pattern of
 * {@link OrderKitchenCustomerPhoneTests}); {@code @Transactional} rolls every
 * fixture back at test end. Both businesses are seeded fresh with high fixed
 * ids ({@code businesses.id} is manually assigned), so array-length
 * assertions are exact.</p>
 */
@SpringBootTest
@Transactional
class OrderManagementDayEndpointTests {

	private static final Long BUSINESS_A_ID = 987_712_101L;
	private static final Long BUSINESS_B_ID = 987_712_102L;
	private static final String SLUG_A = "orders-day-a";
	private static final String SLUG_B = "orders-day-b";

	private static final String USERNAME_A = "orders-day-owner-a";
	private static final String USERNAME_B = "orders-day-owner-b";

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
	private CancellationReasonRepository cancellationReasonRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private com.carrito.saas.repository.jpa.BusinessHoursRepository businessHoursRepository;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness ownerA;
	private BusinessAuthSeed.AuthenticatedBusiness ownerB;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		ownerA = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, USERNAME_A);
		ownerB = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_B_ID, SLUG_B, USERNAME_B);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, BUSINESS_A_ID);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, BUSINESS_B_ID);
	}

	// ------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------

	private long seedProduct(String slug, String name) {
		Business business = businessRepository.findBySlug(slug).orElseThrow();

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Cat " + slug);
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName(name);
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("30.00"));
		product.setStock(50);
		product.setActive(true);
		return productRepository.saveAndFlush(product).getId();
	}

	private long createOrder(String slug, String customerName, long productId) throws Exception {
		String body = """
				{
				  "customerName": "%s",
				  "customerPhone": "1155550000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(customerName, productId);

		MvcResult result = mockMvc.perform(post("/api/orders/menu/" + slug)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn();

		return objectMapper.readTree(result.getResponse().getContentAsString()).get("orderId").asLong();
	}

	private JsonNode getDayView(BusinessAuthSeed.AuthenticatedBusiness owner, String query) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/business/orders" + (query == null ? "" : query))
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andReturn();

		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	// ------------------------------------------------------------------
	// S4: all statuses of the day, including DELIVERED and CANCELLED
	// ------------------------------------------------------------------

	@Test
	void dayViewReturnsAllStatusesIncludingDeliveredAndCancelled() throws Exception {

		long productId = seedProduct(SLUG_A, "Producto Day View");

		long newOrderId = createOrder(SLUG_A, "Pedido Nuevo", productId);
		long deliveredOrderId = createOrder(SLUG_A, "Pedido Entregado", productId);
		long cancelledOrderId = createOrder(SLUG_A, "Pedido Cancelado", productId);

		// Deliver: NEW -> PREPARING -> READY -> DELIVERED (real transitions).
		mockMvc.perform(patch("/api/orders/" + deliveredOrderId + "/status?status=PREPARING")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader()))
				.andExpect(status().isOk());
		mockMvc.perform(patch("/api/orders/" + deliveredOrderId + "/status?status=READY")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader()))
				.andExpect(status().isOk());
		mockMvc.perform(patch("/api/orders/" + deliveredOrderId + "/status?status=DELIVERED")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader()))
				.andExpect(status().isOk());

		// Cancel with reason: NEW -> CANCELLED via the cancel endpoint.
		CancellationReason reason = new CancellationReason();
		reason.setCode("day-view-test-motivo");
		reason.setDescription("Cliente se fue sin avisar");
		reason = cancellationReasonRepository.saveAndFlush(reason);

		String cancelBody = """
				{ "reasonId": %d, "note": "No respondió el teléfono" }
				""".formatted(reason.getId());

		mockMvc.perform(patch("/api/orders/" + cancelledOrderId + "/cancel")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(cancelBody))
				.andExpect(status().isOk());

		// --- Act: management day view (no date param = today) ------------------
		JsonNode orders = getDayView(ownerA, null);

		assertThat(orders).hasSize(3);

		JsonNode nuevo = orders.get(0);
		assertThat(nuevo.get("customerName").asText()).isEqualTo("Pedido Nuevo");
		assertThat(nuevo.get("status").asText()).isEqualTo("NEW");
		assertThat(nuevo.get("total").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(nuevo.get("paymentMethod").asText()).isEqualTo("EFECTIVO");
		assertThat(nuevo.get("orderType").asText()).isEqualTo("RETIRO");
		assertThat(nuevo.get("items")).hasSize(1);
		assertThat(nuevo.get("items").get(0).get("name").asText()).isEqualTo("Producto Day View");
		assertThat(nuevo.get("items").get(0).get("quantity").asInt()).isEqualTo(1);
		assertThat(nuevo.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(nuevo.get("items").get(0).get("subtotal").decimalValue()).isEqualByComparingTo("100.00");

		JsonNode entregado = orders.get(1);
		assertThat(entregado.get("customerName").asText()).isEqualTo("Pedido Entregado");
		assertThat(entregado.get("status").asText()).isEqualTo("DELIVERED");
		assertThat(entregado.get("timeline").get("completedAt")).isNotNull();

		JsonNode cancelado = orders.get(2);
		assertThat(cancelado.get("customerName").asText()).isEqualTo("Pedido Cancelado");
		assertThat(cancelado.get("status").asText()).isEqualTo("CANCELLED");
		assertThat(cancelado.get("timeline").get("cancelledAt")).isNotNull();
		assertThat(cancelado.get("cancellationReason").get("id").asLong()).isEqualTo(reason.getId());
		assertThat(cancelado.get("cancellationReason").get("description").asText())
				.isEqualTo("Cliente se fue sin avisar");
		assertThat(cancelado.get("cancellationNote").asText()).isEqualTo("No respondió el teléfono");
	}

	// ------------------------------------------------------------------
	// Tenant isolation: another business's orders never leak
	// ------------------------------------------------------------------

	@Test
	void dayViewIsScopedToTheCallerBusiness() throws Exception {

		long productA = seedProduct(SLUG_A, "Producto A");
		long productB = seedProduct(SLUG_B, "Producto B");

		createOrder(SLUG_A, "Pedido Negocio A", productA);
		createOrder(SLUG_B, "Pedido Negocio B", productB);

		JsonNode viewA = getDayView(ownerA, null);
		assertThat(viewA).hasSize(1);
		assertThat(viewA.get(0).get("customerName").asText()).isEqualTo("Pedido Negocio A");

		JsonNode viewB = getDayView(ownerB, null);
		assertThat(viewB).hasSize(1);
		assertThat(viewB.get(0).get("customerName").asText()).isEqualTo("Pedido Negocio B");
	}

	// ------------------------------------------------------------------
	// Date filter: ?date=YYYY-MM-DD selects the day; default is today
	// ------------------------------------------------------------------

	@Test
	void dayViewDefaultsToTodayAndHonorsAnExplicitDate() throws Exception {

		long productId = seedProduct(SLUG_A, "Producto Fechas");

		long yesterdayOrderId = createOrder(SLUG_A, "Pedido De Ayer", productId);

		// Move the order back to yesterday with a bulk JPQL update: the column
		// is @CreationTimestamp, so a value set on the managed entity never
		// reaches the DB. The clear() afterwards detaches the stale snapshot
		// so it cannot auto-flush back over the reseeded value.
		entityManager.createQuery(
					"UPDATE Order o SET o.createdAt = :when WHERE o.id = :id")
			.setParameter("when", LocalDate.now().minusDays(1).atStartOfDay().withHour(12))
			.setParameter("id", yesterdayOrderId)
			.executeUpdate();
		entityManager.clear();

		JsonNode today = getDayView(ownerA, null);
		assertThat(today).hasSize(0);

		String yesterday = LocalDate.now().minusDays(1).toString();
		JsonNode ayer = getDayView(ownerA, "?date=" + yesterday);
		assertThat(ayer).hasSize(1);
		assertThat(ayer.get(0).get("customerName").asText()).isEqualTo("Pedido De Ayer");
	}

}
