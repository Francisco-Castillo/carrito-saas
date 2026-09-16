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
import com.carrito.saas.repository.entity.OrderItem;
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
 * Last-mile regression guard for the public QR menu order flow.
 *
 * <p>Exercises the full HTTP path an anonymous browser takes: POST
 * {@code application/json} to {@code /api/orders/menu/{slug}} with the EXACT
 * body shape the fixed menu page sends — canonical enum tokens
 * ({@code RETIRO}, {@code EFECTIVO}; Jackson matches them case-sensitively)
 * and {@code items} as an array of {@code {productId, quantity}}. No table
 * reference exists anywhere in that payload.</p>
 *
 * <p>Asserts the response is HTTP 200 with status {@code NEW}, and that the
 * order is persisted with status {@code NEW} and NO table
 * ({@code restaurantTable} null, i.e. {@code table_id IS NULL}). The read-back
 * happens through {@link OrderRepository} after clearing the persistence
 * context, so the assertion is a fresh SELECT against PostgreSQL and proves
 * the null table survived the round trip — the INSERT itself executes
 * immediately because {@code Order} uses IDENTITY generation.</p>
 *
 * <p>{@code @Transactional} rolls back every fixture at test end.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy}: Spring Boot 4 moved {@code @AutoConfigureMockMvc}
 * into the separate {@code spring-boot-webmvc-test} module, which
 * {@code spring-boot-starter-test} does not bring, and adding dependencies is
 * out of scope.</p>
 */
@SpringBootTest
@Transactional
class PublicMenuOrderHttpTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_654_322L;

	private static final String SLUG = "public-menu-http-test-business";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ComboRepository comboRepository;

	@Autowired
	private OrderRepository orderRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	@Test
	void anonymousBrowserOrderIsAcceptedAndPersistedWithoutTable() throws Exception {

		// --- Seed: business reachable by slug ---------------------------------
		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Public Menu HTTP Test Business");
		business.setSlug(SLUG);
		businessRepository.saveAndFlush(business);

		// --- Seed: product with stock >= ordered quantity ---------------------
		Product product = new Product();
		product.setName("Public Menu HTTP Test Product");
		product.setPrice(new BigDecimal("250.50"));
		product.setCost(new BigDecimal("80.00"));
		product.setStock(10);
		product.setActive(true);
		product = productRepository.saveAndFlush(product);

		// --- Act: EXACT body the fixed browser code sends ----------------------
		String body = """
				{
				  "customerName": "Cliente Publico",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "notes": "Sin cebolla",
				  "items": [ { "productId": %d, "quantity": 2 } ]
				}
				""".formatted(product.getId());

		String responseBody = mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").isNotEmpty())
				.andExpect(jsonPath("$.status").value("NEW"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		long createdOrderId = objectMapper.readTree(responseBody).path("orderId").asLong();
		assertThat(createdOrderId).isPositive();

		// --- Assert persisted round trip: NEW status, NO table ------------------
		// Detach everything so findById issues a fresh SELECT against the row the
		// IDENTITY INSERT already wrote.
		entityManager.clear();

		Order persisted = orderRepository.findById(createdOrderId).orElseThrow();
		assertThat(persisted.getStatus()).isEqualTo(OrderStatus.NEW);
		assertThat(persisted.getRestaurantTable())
				.as("a public menu order must be persisted with no table (table_id IS NULL)")
				.isNull();
		assertThat(persisted.getCustomerName()).isEqualTo("Cliente Publico");
		assertThat(persisted.getTotal()).isEqualByComparingTo(new BigDecimal("501.00"));
	}

	/**
	 * Combo branch of {@code OrderServiceImpl.createOrder}: the exact body the
	 * menu page sends when the customer picks a combo —
	 * {@code {comboId, quantity}} with no productId.
	 *
	 * <p>Regression guard for EXISTING production behavior: the combo is
	 * resolved through {@code findFullMenuCombosByIds(comboIds, businessId)}
	 * (combo category must belong to the ordered business), persisted as one
	 * {@code comboRoot} line priced at the combo price, and decomposed into one
	 * stock-decremented line per {@code ComboProduct}. The total counts only
	 * combo roots. Green on arrival when written — this pins the contract, it
	 * does not fix a bug.</p>
	 */
	@Test
	void comboOrderIsResolvedAndDecomposedThroughPublicMenuEndpoint() throws Exception {

		// --- Seed: business reachable by slug ---------------------------------
		Business business = new Business();
		business.setId(BUSINESS_ID + 1);
		business.setName("Public Menu Combo Test Business");
		business.setSlug(SLUG + "-combo");
		businessRepository.saveAndFlush(business);

		// --- Seed: category owned by the business (combo lookup joins on it) ---
		Category category = new Category();
		category.setBusiness(business);
		category.setName("Combos");
		category = categoryRepository.saveAndFlush(category);

		// --- Seed: component products; stock must be non-null and sufficient --
		Product burger = new Product();
		burger.setName("Combo Test Burger");
		burger.setPrice(new BigDecimal("400.00"));
		burger.setCost(new BigDecimal("150.00"));
		burger.setStock(10);
		burger.setActive(true);
		burger = productRepository.saveAndFlush(burger);

		Product fries = new Product();
		fries.setName("Combo Test Fries");
		fries.setPrice(new BigDecimal("150.00"));
		fries.setCost(new BigDecimal("50.00"));
		fries.setStock(20);
		fries.setActive(true);
		fries = productRepository.saveAndFlush(fries);

		// --- Seed: combo recipe: 1 burger + 2 fries ----------------------------
		Combo combo = new Combo();
		combo.setName("Combo Burger + Papas");
		combo.setPrice(new BigDecimal("500.00"));
		combo.setCategory(category);

		ComboProduct comboBurger = new ComboProduct();
		comboBurger.setCombo(combo);
		comboBurger.setProduct(burger);
		comboBurger.setQuantity(new BigDecimal("1"));

		ComboProduct comboFries = new ComboProduct();
		comboFries.setCombo(combo);
		comboFries.setProduct(fries);
		comboFries.setQuantity(new BigDecimal("2"));

		combo.setItems(new ArrayList<>(List.of(comboBurger, comboFries)));
		combo = comboRepository.saveAndFlush(combo);

		// Effectively final snapshots for use inside lambdas below
		final Long burgerId = burger.getId();
		final Long friesId = fries.getId();

		// --- Act: EXACT body the browser code sends for a combo ----------------
		String body = """
				{
				  "customerName": "Cliente Combo",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "comboId": %d, "quantity": 2 } ]
				}
				""".formatted(combo.getId());

		String responseBody = mockMvc.perform(post("/api/orders/menu/" + SLUG + "-combo")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").isNotEmpty())
				.andExpect(jsonPath("$.status").value("NEW"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		long createdOrderId = objectMapper.readTree(responseBody).path("orderId").asLong();
		assertThat(createdOrderId).isPositive();

		// --- Assert persisted round trip: root line + decomposition ------------
		entityManager.clear();

		Order persisted = orderRepository.findById(createdOrderId).orElseThrow();
		assertThat(persisted.getStatus()).isEqualTo(OrderStatus.NEW);
		assertThat(persisted.getCustomerName()).isEqualTo("Cliente Combo");
		assertThat(persisted.getTotal())
				.as("the total counts only combo roots: 2 x 500.00")
				.isEqualByComparingTo(new BigDecimal("1000.00"));

		List<OrderItem> items = persisted.getItems();
		assertThat(items).hasSize(3);

		OrderItem root = items.stream()
				.filter(OrderItem::getComboRoot)
				.findFirst()
				.orElseThrow();
		assertThat(root.getProductName()).isEqualTo("Combo Burger + Papas");
		assertThat(root.getCombo().getId()).isEqualTo(combo.getId());
		assertThat(root.getProductId()).isNull();
		assertThat(root.getQuantity()).isEqualTo(2);
		assertThat(root.getSubtotal()).isEqualByComparingTo(new BigDecimal("1000.00"));

		List<OrderItem> decomposed = items.stream()
				.filter(item -> !item.getComboRoot())
				.toList();
		assertThat(decomposed).hasSize(2);

		OrderItem burgerLine = decomposed.stream()
				.filter(item -> burgerId.equals(item.getProductId()))
				.findFirst()
				.orElseThrow();
		assertThat(burgerLine.getQuantity())
				.as("1 burger per combo x 2 combos")
				.isEqualTo(2);
		assertThat(burgerLine.getSubtotal()).isEqualByComparingTo(new BigDecimal("800.00"));

		OrderItem friesLine = decomposed.stream()
				.filter(item -> friesId.equals(item.getProductId()))
				.findFirst()
				.orElseThrow();
		assertThat(friesLine.getQuantity())
				.as("2 fries per combo x 2 combos")
				.isEqualTo(4);
		assertThat(friesLine.getSubtotal())
				.as("fries 150.00 x finalQty 4")
				.isEqualByComparingTo(new BigDecimal("600.00"));

		// --- Assert stock was decremented on every component product -----------
		assertThat(productRepository.findById(burgerId).orElseThrow().getStock())
				.as("burger stock 10 - 2")
				.isEqualTo(8);
		assertThat(productRepository.findById(friesId).orElseThrow().getStock())
				.as("fries stock 20 - 4")
				.isEqualTo(16);
	}

}
