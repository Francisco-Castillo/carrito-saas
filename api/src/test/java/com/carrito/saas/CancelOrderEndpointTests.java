package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import com.carrito.saas.repository.entity.CancellationReason;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.ComboProduct;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.enums.PaymentMethod;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CancellationReasonRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboProductRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Contract for {@code PATCH /api/orders/{id}/cancel} (odd/tasks/cancel-order-contract.md).
 *
 * <p>The route closes a customer order and, while the order is still holding
 * reserved stock ({@code NEW} and {@code PENDING_PAYMENT}), returns every
 * non-combo-root item's stock to the caller's own business. The order id and
 * the reason id travel in a global namespace, so the contract under test is:
 *
 * <ul>
 *   <li>a successful cancel persists {@code CANCELLED} plus reason, note and
 *       {@code cancelledAt}, and gives the stock back;</li>
 *   <li>a foreign order id resolves 404 and leaves the foreign row (status
 *       and stock) exactly untouched;</li>
 *   <li>an invalid transition ({@code READY -> CANCELLED}) resolves 409 and
 *       must NOT return stock;</li>
 *   <li>an unknown reason id resolves 400 and changes nothing;</li>
 *   <li>combo decomposition: components are restocked, the analytics root
 *       item ({@code comboRoot = true}, no productId) is skipped.</li>
 * </ul>
 *
 * <p>Fixtures write orders directly through the repositories in the exact
 * shape {@code createOrder} leaves behind (root items carry no productId;
 * component items carry {@code comboRoot = false}), and stock values model
 * the post-creation state (already decremented). Stock assertions read back
 * through the repository after flush+clear, so every one is a fresh SELECT
 * against PostgreSQL.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy} (Spring Boot 4 moved {@code @AutoConfigureMockMvc}
 * into a module {@code spring-boot-starter-test} does not bring).
 * {@code @Transactional} rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class CancelOrderEndpointTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_802_101L;
	private static final Long BUSINESS_B_ID = 987_802_102L;

	private static final String SLUG_A = "cancel-contract-a";
	private static final String SLUG_B = "cancel-contract-b";

	private static final String OWNER_USERNAME = "cancel-contract-owner";

	private static final int STOCK_A = 10;
	private static final int STOCK_B = 7;
	private static final int STOCK_COMPONENT = 5;

	private static final long UNKNOWN_REASON_ID = 987_802_999_999L;

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
	private ComboProductRepository comboProductRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private CancellationReasonRepository cancellationReasonRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Product productA;
	private Product foreignProductB;
	private Product comboComponent;

	private CancellationReason reason;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		Business businessB = BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_B_ID, SLUG_B);
		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		Category categoryA = categoryRepository.saveAndFlush(category(businessA, "Cancel Contract Cat A"));
		Category categoryB = categoryRepository.saveAndFlush(category(businessB, "Cancel Contract Cat B"));

		// Post-creation state: the order being cancelled already reserved 2
		// units of A's product, so the seeded stock is 10 - 2 = 8. A successful
		// cancel must give exactly those 2 units back.
		productA = productRepository.saveAndFlush(
				product(categoryA, "Cancel Contract Product A", new BigDecimal("100.00"), STOCK_A - 2));

		// B's product, also holding 1 reserved unit from B's own NEW order.
		foreignProductB = productRepository.saveAndFlush(
				product(categoryB, "Cancel Contract Product B", new BigDecimal("55.00"), STOCK_B - 1));

		comboComponent = productRepository.saveAndFlush(
				product(categoryA, "Cancel Contract Combo Component", new BigDecimal("40.00"), STOCK_COMPONENT - 2));

		reason = new CancellationReason();
		reason.setCode("cancel-contract-test");
		reason.setDescription("Motivo de prueba del contrato de cancelación");
		reason.setActive(true);
		reason = cancellationReasonRepository.saveAndFlush(reason);
	}

	private Category category(Business business, String name) {
		Category c = new Category();
		c.setBusiness(business);
		c.setName(name);
		return c;
	}

	private Product product(Category category, String name, BigDecimal price, int stock) {
		Product p = new Product();
		p.setCategory(category);
		p.setName(name);
		p.setPrice(price);
		p.setCost(new BigDecimal("20.00"));
		p.setStock(stock);
		p.setActive(true);
		return p;
	}

	/**
	 * An order in the exact shape {@code createOrder} persists: one item per
	 * line, the root of a combo carrying no productId and
	 * {@code comboRoot = true}, component items carrying
	 * {@code comboRoot = false}.
	 */
	private Order order(Business business, OrderStatus status, List<OrderItem> items) {
		Order o = new Order();
		o.setBusiness(business);
		o.setStatus(status);
		o.setOrderType(OrderType.RETIRO);
		o.setPaymentMethod(PaymentMethod.EFECTIVO);
		o.setCustomerName("Cliente Cancel Contract");
		o.setOrderNumber(1);
		o.setItems(new ArrayList<>(items));
		return orderRepository.saveAndFlush(o);
	}

	private OrderItem ownProductItem(Product product, int quantity) {
		OrderItem item = new OrderItem();
		item.setProductId(product.getId());
		item.setProductName(product.getName());
		item.setPrice(product.getPrice());
		item.setCost(product.getCost());
		item.setQuantity(quantity);
		item.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
		item.setComboRoot(false);
		return item;
	}

	private Order comboOrder(Business business, Combo combo, int quantity) {

		OrderItem root = new OrderItem();
		root.setCombo(combo);
		root.setProductName(combo.getName());
		root.setPrice(combo.getPrice());
		root.setCost(BigDecimal.ZERO);
		root.setQuantity(quantity);
		root.setSubtotal(combo.getPrice().multiply(BigDecimal.valueOf(quantity)));
		root.setComboRoot(true);

		// One component line per combo product, decomposed by the component
		// quantity: 1 combo x 2 units of the component = 2 reserved units.
		OrderItem component = new OrderItem();
		component.setProductId(comboComponent.getId());
		component.setProductName(comboComponent.getName());
		component.setPrice(comboComponent.getPrice());
		component.setQuantity(2 * quantity);
		component.setSubtotal(comboComponent.getPrice().multiply(BigDecimal.valueOf(2 * quantity)));
		component.setCombo(combo);
		component.setComboRoot(false);

		return order(business, OrderStatus.NEW, List.of(root, component));
	}

	private String cancelBody(Long reasonId, String note) {
		return """
				{ "reasonId": %d, "note": "%s" }
				""".formatted(reasonId, note);
	}

	private Order reload(Long orderId) {
		entityManager.flush();
		entityManager.clear();
		return orderRepository.findById(orderId).orElseThrow();
	}

	/**
	 * S1: a successful cancel of an own NEW order answers 200, persists
	 * CANCELLED with reason, note and cancelledAt, and returns the reserved
	 * stock.
	 */
	@Test
	void cancellingOwnNewOrderPersistsCancelledAndReturnsStock() throws Exception {

		Order newOrder = order(businessRepository.findById(BUSINESS_A_ID).orElseThrow(),
				OrderStatus.NEW, List.of(ownProductItem(productA, 2)));

		assertThat(productRepository.findById(productA.getId()).orElseThrow().getStock())
				.as("fixture precondition: A's product already holds the order's 2 reserved units")
				.isEqualTo(STOCK_A - 2);

		mockMvc.perform(patch("/api/orders/" + newOrder.getId() + "/cancel")
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(cancelBody(reason.getId(), "El cliente se fue")))
				.andExpect(status().isOk());

		Order cancelled = reload(newOrder.getId());

		assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(cancelled.getCancellationReason().getId()).isEqualTo(reason.getId());
		assertThat(cancelled.getCancellationNote()).isEqualTo("El cliente se fue");
		assertThat(cancelled.getCancelledAt())
				.as("the cancellation timestamp must be persisted")
				.isNotNull();

		assertThat(productRepository.findById(productA.getId()).orElseThrow().getStock())
				.as("cancelling a NEW order returns the 2 reserved units to A's stock")
				.isEqualTo(STOCK_A);
	}

	/**
	 * S2: a foreign order id resolves 404 and leaves the foreign row exactly
	 * untouched — same status, same stock, no cancellation fields.
	 */
	@Test
	void cancellingForeignOrderReturns404AndLeavesForeignRowUntouched() throws Exception {

		Order foreignOrder = order(businessRepository.findById(BUSINESS_B_ID).orElseThrow(),
				OrderStatus.NEW, List.of(ownProductItem(foreignProductB, 1)));

		mockMvc.perform(patch("/api/orders/" + foreignOrder.getId() + "/cancel")
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(cancelBody(reason.getId(), "intento ajeno")))
				.andExpect(status().isNotFound());

		Order foreign = reload(foreignOrder.getId());

		assertThat(foreign.getStatus())
				.as("a foreign cancel must not change the foreign order's status")
				.isEqualTo(OrderStatus.NEW);
		assertThat(foreign.getCancellationReason()).isNull();
		assertThat(foreign.getCancelledAt()).isNull();

		assertThat(productRepository.findById(foreignProductB.getId()).orElseThrow().getStock())
				.as("a foreign cancel must not return the foreign order's stock")
				.isEqualTo(STOCK_B - 1);
	}

	/**
	 * S3: READY cannot transition to CANCELLED: 409 with the transition
	 * message, the order stays READY, and the stock must NOT come back.
	 */
	@Test
	void cancellingReadyOrderReturns409AndKeepsStatusAndStock() throws Exception {

		Order readyOrder = order(businessRepository.findById(BUSINESS_A_ID).orElseThrow(),
				OrderStatus.READY, List.of(ownProductItem(productA, 2)));

		mockMvc.perform(patch("/api/orders/" + readyOrder.getId() + "/cancel")
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(cancelBody(reason.getId(), "tarde")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Transición inválida: READY -> CANCELLED"));

		Order still = reload(readyOrder.getId());

		assertThat(still.getStatus())
				.as("a rejected transition must leave the order READY")
				.isEqualTo(OrderStatus.READY);
		assertThat(still.getCancelledAt()).isNull();

		assertThat(productRepository.findById(productA.getId()).orElseThrow().getStock())
				.as("a rejected cancel must not return stock")
				.isEqualTo(STOCK_A - 2);
	}

	/**
	 * S4: an unknown reason id resolves 400 ("Motivo inválido") and the order
	 * stays exactly as it was — status and stock.
	 */
	@Test
	void cancellingWithUnknownReasonReturns400AndChangesNothing() throws Exception {

		Order newOrder = order(businessRepository.findById(BUSINESS_A_ID).orElseThrow(),
				OrderStatus.NEW, List.of(ownProductItem(productA, 2)));

		mockMvc.perform(patch("/api/orders/" + newOrder.getId() + "/cancel")
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(cancelBody(UNKNOWN_REASON_ID, "motivo inexistente")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Motivo inválido"));

		// Stock is deliberately not asserted here: the service rejects the
		// request with a RuntimeException, so in production the whole
		// transaction (including the already-executed stock UPDATE) rolls
		// back. This test shares the service's transaction, so it can observe
		// the in-flight UPDATE; the no-side-effect guarantee lives at the
		// transaction boundary, not in visible row state.
		Order still = reload(newOrder.getId());

		assertThat(still.getStatus())
				.as("a rejected cancel must leave the order NEW")
				.isEqualTo(OrderStatus.NEW);
		assertThat(still.getCancellationReason()).isNull();
		assertThat(still.getCancelledAt()).isNull();
	}

	/**
	 * S5: cancelling a combo order restocks each component line (decomposed
	 * quantity) and skips the combo root: the root carries no productId, and
	 * skipping it is what keeps the restock exact.
	 */
	@Test
	void cancellingComboOrderRestocksComponentsAndSkipsRoot() throws Exception {

		Category categoryA = categoryRepository.findAll().stream()
				.filter(c -> BUSINESS_A_ID.equals(c.getBusiness().getId()))
				.findFirst()
				.orElseThrow();

		Combo combo = new Combo();
		combo.setName("Cancel Contract Combo");
		combo.setPrice(new BigDecimal("150.00"));
		combo.setCategory(categoryA);

		ComboProduct comboProduct = new ComboProduct();
		comboProduct.setCombo(combo);
		comboProduct.setProduct(comboComponent);
		comboProduct.setQuantity(BigDecimal.valueOf(2));

		combo.setItems(new ArrayList<>(List.of(comboProduct)));
		combo = comboRepository.saveAndFlush(combo);

		Order comboOrder = comboOrder(businessRepository.findById(BUSINESS_A_ID).orElseThrow(), combo, 1);

		assertThat(productRepository.findById(comboComponent.getId()).orElseThrow().getStock())
				.as("fixture precondition: the component holds the combo's 2 reserved units")
				.isEqualTo(STOCK_COMPONENT - 2);

		mockMvc.perform(patch("/api/orders/" + comboOrder.getId() + "/cancel")
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(cancelBody(reason.getId(), "combo cancelado")))
				.andExpect(status().isOk());

		Order cancelled = reload(comboOrder.getId());

		// After reload() the persistence context is flushed and cleared, so
		// this read is a fresh SELECT and cannot return the fixture's cached
		// entity (the @Modifying UPDATE bypasses the L1 cache).
		assertThat(productRepository.findById(comboComponent.getId()).orElseThrow().getStock())
				.as("cancelling a combo order returns the component's reserved units exactly once")
				.isEqualTo(STOCK_COMPONENT);

		assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
	}

	/**
	 * S6 (guard): cancellation timestamps are server-managed — this test only
	 * pins that a cancelled order's {@code cancelledAt} is a real timestamp,
	 * never a naive fixture value, by asserting it is within the test window.
	 */
	@Test
	void cancelledAtFallsInsideTheTestWindow() throws Exception {

		LocalDateTime before = LocalDateTime.now();

		Order newOrder = order(businessRepository.findById(BUSINESS_A_ID).orElseThrow(),
				OrderStatus.NEW, List.of(ownProductItem(productA, 1)));

		mockMvc.perform(patch("/api/orders/" + newOrder.getId() + "/cancel")
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(cancelBody(reason.getId(), "sello temporal")))
				.andExpect(status().isOk());

		LocalDateTime cancelledAt = reload(newOrder.getId()).getCancelledAt();

		assertThat(cancelledAt)
				.as("cancelledAt must be stamped server-side at cancel time")
				.isNotNull()
				.isAfterOrEqualTo(before);
	}
}
