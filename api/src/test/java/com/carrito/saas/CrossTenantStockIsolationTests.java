package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Cross-tenant isolation contract for the public menu order channel.
 *
 * <p>Any anonymous caller can POST to {@code /api/orders/menu/{slug}} for
 * their own business. The product ids in that request belong to a global
 * namespace, so without a business predicate on the product load and on the
 * atomic stock writes, a caller could decrement another business's stock and
 * book it at the victim's price.</p>
 *
 * <p>The contract under test is the <strong>effect</strong>, not the HTTP
 * status: a foreign {@code productId} ordered through business A's public
 * menu must leave business B's stock exactly unchanged, must leave A's stock
 * unchanged, and must not persist any order for either business. The HTTP
 * status of the rejection is deliberately not asserted — it stays a
 * {@code RuntimeException} mapped to 500 by {@code GlobalExceptionHandler},
 * which is out of scope for this slice.</p>
 *
 * <p>Positive controls pin that the feature itself keeps working: an
 * own-product order still creates an order with status {@code NEW},
 * decrements A's stock, and computes the right total; a foreign
 * {@code comboId} stays rejected (the combo path was already scoped and must
 * not regress).</p>
 *
 * <p>Stock is read back through the repository after flushing and clearing
 * the persistence context, so every assertion is a fresh SELECT against
 * PostgreSQL rather than a cached in-memory value.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy}: Spring Boot 4 moved {@code @AutoConfigureMockMvc}
 * into the separate {@code spring-boot-webmvc-test} module, which
 * {@code spring-boot-starter-test} does not bring. {@code @Transactional}
 * rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class CrossTenantStockIsolationTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_700_101L;
	private static final Long BUSINESS_B_ID = 987_700_102L;

	private static final String SLUG_A = "cross-tenant-iso-a";
	private static final String SLUG_B = "cross-tenant-iso-b";

	private static final int STOCK_A = 10;
	private static final int STOCK_B = 7;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private ComboRepository comboRepository;

	@Autowired
	private OrderRepository orderRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private Product productA;
	private Product productB;
	private Combo foreignCombo;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		seedFixtures();
	}

	private void seedFixtures() {

		Business businessA = new Business();
		businessA.setId(BUSINESS_A_ID);
		businessA.setName("Cross Tenant Iso A");
		businessA.setSlug(SLUG_A);
		businessRepository.saveAndFlush(businessA);

		Business businessB = new Business();
		businessB.setId(BUSINESS_B_ID);
		businessB.setName("Cross Tenant Iso B");
		businessB.setSlug(SLUG_B);
		businessRepository.saveAndFlush(businessB);

		Category categoryA = new Category();
		categoryA.setBusiness(businessA);
		categoryA.setName("Cross Tenant Iso Cat A");
		categoryA = categoryRepository.saveAndFlush(categoryA);

		Category categoryB = new Category();
		categoryB.setBusiness(businessB);
		categoryB.setName("Cross Tenant Iso Cat B");
		categoryB = categoryRepository.saveAndFlush(categoryB);

		// A's own product: the positive-control path.
		Product ownProduct = new Product();
		ownProduct.setCategory(categoryA);
		ownProduct.setName("Cross Tenant Iso Product A");
		ownProduct.setPrice(new BigDecimal("100.00"));
		ownProduct.setCost(new BigDecimal("30.00"));
		ownProduct.setStock(STOCK_A);
		ownProduct.setActive(true);
		productA = productRepository.saveAndFlush(ownProduct);

		// B's product: the foreign product the attacker orders through A's menu.
		Product foreignProduct = new Product();
		foreignProduct.setCategory(categoryB);
		foreignProduct.setName("Cross Tenant Iso Product B");
		foreignProduct.setPrice(new BigDecimal("55.00"));
		foreignProduct.setCost(new BigDecimal("20.00"));
		foreignProduct.setStock(STOCK_B);
		foreignProduct.setActive(true);
		productB = productRepository.saveAndFlush(foreignProduct);

		// A combo owned by B: the no-regression probe for the already-scoped combo path.
		Combo combo = new Combo();
		combo.setName("Cross Tenant Iso Foreign Combo");
		combo.setPrice(new BigDecimal("300.00"));
		combo.setCategory(categoryB);

		ComboProduct comboProduct = new ComboProduct();
		comboProduct.setCombo(combo);
		comboProduct.setProduct(productB);
		comboProduct.setQuantity(new BigDecimal("1"));

		combo.setItems(new ArrayList<>(List.of(comboProduct)));
		foreignCombo = comboRepository.saveAndFlush(combo);
	}

	private long countOrdersForTestBusinesses() {

		return entityManager
				.createQuery(
						"SELECT COUNT(o) FROM Order o WHERE o.business.id IN (:idA, :idB)",
						Long.class)
				.setParameter("idA", BUSINESS_A_ID)
				.setParameter("idB", BUSINESS_B_ID)
				.getSingleResult();
	}

	/**
	 * AC1 + AC2: an anonymous POST to A's public menu carrying B's productId
	 * must leave B's stock exactly unchanged, leave A's stock unchanged, and
	 * persist no order for either business.
	 */
	@Test
	void foreignProductOrderLeavesForeignBusinessUntouched() throws Exception {

		String body = """
				{
				  "customerName": "Cliente Atacante",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 2 } ]
				}
				""".formatted(productB.getId());

		// The HTTP status is deliberately not asserted: the documented rejection
		// is a RuntimeException mapped to 500, and the contract under test is the
		// effect on stock and persisted orders.
		mockMvc.perform(post("/api/orders/menu/" + SLUG_A)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body));

		entityManager.flush();
		entityManager.clear();

		Product foreignAfter = productRepository.findById(productB.getId()).orElseThrow();
		assertThat(foreignAfter.getStock())
				.as("B's stock must be exactly unchanged when its productId is ordered through A's public menu")
				.isEqualTo(STOCK_B);

		Product ownAfter = productRepository.findById(productA.getId()).orElseThrow();
		assertThat(ownAfter.getStock())
				.as("A's own stock must be exactly unchanged when the order carries a foreign productId")
				.isEqualTo(STOCK_A);

		assertThat(countOrdersForTestBusinesses())
				.as("a cross-tenant order must not persist any order for either business")
				.isEqualTo(0L);
	}

	/**
	 * AC3: positive control. An own-product order through A's public menu must
	 * still succeed: order created with status NEW, A's stock decremented,
	 * correct total — and B untouched.
	 */
	@Test
	void ownProductOrderStillSucceedsAndDecrementsOwnStockOnly() throws Exception {

		String body = """
				{
				  "customerName": "Cliente Propio",
				  "customerPhone": "5411555001",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 2 } ]
				}
				""".formatted(productA.getId());

		String responseBody = mockMvc.perform(post("/api/orders/menu/" + SLUG_A)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").isNotEmpty())
				.andExpect(jsonPath("$.status").value("NEW"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		entityManager.flush();
		entityManager.clear();

		long createdOrderId = objectMapper.readTree(responseBody).path("orderId").asLong();
		Order persisted = orderRepository.findById(createdOrderId).orElseThrow();

		assertThat(persisted.getStatus()).isEqualTo(OrderStatus.NEW);
		assertThat(persisted.getBusiness().getId()).isEqualTo(BUSINESS_A_ID);
		assertThat(persisted.getTotal())
				.as("2 x 100.00 at A's own price")
				.isEqualByComparingTo(new BigDecimal("200.00"));

		assertThat(productRepository.findById(productA.getId()).orElseThrow().getStock())
				.as("A's own stock 10 - 2")
				.isEqualTo(STOCK_A - 2);

		assertThat(productRepository.findById(productB.getId()).orElseThrow().getStock())
				.as("an own-product order must leave B's stock untouched")
				.isEqualTo(STOCK_B);
	}

	/**
	 * AC5: no-regression probe for the combo path, which was already scoped by
	 * business. A foreign comboId ordered through A's menu must persist no
	 * order and touch no stock.
	 */
	@Test
	void foreignComboOrderIsStillRejectedWithoutSideEffects() throws Exception {

		String body = """
				{
				  "customerName": "Cliente Combo",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "comboId": %d, "quantity": 1 } ]
				}
				""".formatted(foreignCombo.getId());

		mockMvc.perform(post("/api/orders/menu/" + SLUG_A)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body));

		entityManager.flush();
		entityManager.clear();

		assertThat(productRepository.findById(productB.getId()).orElseThrow().getStock())
				.as("a foreign comboId must not touch B's component stock")
				.isEqualTo(STOCK_B);

		assertThat(productRepository.findById(productA.getId()).orElseThrow().getStock())
				.as("a foreign comboId must not touch A's stock")
				.isEqualTo(STOCK_A);

		assertThat(countOrdersForTestBusinesses())
				.as("a foreign comboId must not persist any order for either business")
				.isEqualTo(0L);
	}

}
