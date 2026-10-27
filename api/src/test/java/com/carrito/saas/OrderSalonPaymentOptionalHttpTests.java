package com.carrito.saas;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.TableStatus;
import com.carrito.saas.repository.jpa.BusinessDateExceptionRepository;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.OrderItemRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.repository.jpa.TableRequestRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Payment contract of a table order (S4 de odd/tasks/pedido-qr-mesa.md): a
 * SALON order placed from a per-table QR is PAID AT THE TABLE, so the public
 * order endpoint must accept it WITHOUT a {@code paymentMethod} and persist
 * it as null. Every other order type keeps the legacy requirement — 400
 * "Debe indicar método de pago" — and a SALON order that DOES carry a payment
 * method (staff POS flow) keeps accepting it unchanged.
 *
 * <p>Every call uses the slug-scoped public endpoint
 * {@code POST /api/orders/menu/{slug}} (S4 de odd/tasks/retire-menu-route.md:
 * the slugless variant is retired; the endpoint is only the vehicle here).</p>
 */
@SpringBootTest
@Transactional
class OrderSalonPaymentOptionalHttpTests {

	private static final Long BUSINESS_ID = 987_654_380L;

	private static final String SLUG = "salon-payment-business";

	private static final String USERNAME = "salon-payment-owner";

	private Long productId;

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
	private BusinessHoursRepository businessHoursRepository;

	@Autowired
	private BusinessDateExceptionRepository businessDateExceptionRepository;

	@Autowired
	private TableRepository tableRepository;

	@Autowired
	private OrderItemRepository orderItemRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TableRequestRepository tableRequestRepository;

	@Autowired
	private ComboRepository comboRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private JwtUtil jwtUtil;

	// The application context exposes no Jackson ObjectMapper bean (same
	// convention as PublicMenuOrderHttpTests).
	private final ObjectMapper objectMapper = new ObjectMapper();

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private long tableId;

	@BeforeEach
	void setUp() throws Exception {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		// Slug-scoped public endpoint: make the shared dev database
		// deterministic inside this test's transaction (FK chain,
		// dependents first).
		orderItemRepository.deleteAll();
		orderRepository.deleteAll();
		tableRequestRepository.deleteAll();
		comboRepository.deleteAll();
		productRepository.deleteAll();
		categoryRepository.deleteAll();
		businessUserRepository.deleteAll();
		businessHoursRepository.deleteAll();
		businessDateExceptionRepository.deleteAll();
		tableRepository.deleteAll();
		businessRepository.deleteAll();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, USERNAME);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, BUSINESS_ID);

		RestaurantTable table = new RestaurantTable();
		table.setBusiness(businessRepository.findById(BUSINESS_ID).orElseThrow());
		table.setTableNumber(1);
		table.setTableName("Mesa pago");
		table.setQrToken(UUID.randomUUID().toString());
		table.setStatus(TableStatus.AVAILABLE);
		table.setCreatedAt(LocalDateTime.now());
		table.setUpdatedAt(LocalDateTime.now());
		tableId = tableRepository.saveAndFlush(table).getId();

		seedProduct();
	}

	private void seedProduct() {

		// A minimal active product the public menu would really serve.
		Category category = new Category();
		category.setBusiness(businessRepository.findById(BUSINESS_ID).orElseThrow());
		category.setName("Postres");
		category.setOrden(1);
		category.setActive(true);
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		// IDENTITY id: set it, then read back the generated value (a manually
		// assigned id turns saveAndFlush into a detached UPDATE and dies on a
		// freshly deleted table).
		product.setCategory(category);
		product.setName("Flan casero");
		product.setPrice(new java.math.BigDecimal("3200.00"));
		product.setCost(new java.math.BigDecimal("1200.00"));
		product.setActive(true);
		productId = productRepository.saveAndFlush(product).getId();
	}

	@Test
	void salonOrderWithoutPaymentMethodIsAcceptedAndPersistsNull() throws Exception {

		String body = mockMvc.perform(post("/api/orders/menu/{slug}", SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"customerName\":\"Cliente Mesa\",\"customerPhone\":\"1155551234\","
								+ "\"orderType\":\"SALON\",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}],"
								+ "\"tableId\":" + tableId + "}"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		JsonNode order = objectMapper.readTree(body);
		org.assertj.core.api.Assertions.assertThat(order.get("orderType").asText()).isEqualTo("SALON");
		org.assertj.core.api.Assertions.assertThat(order.get("tableId").asLong()).isEqualTo(tableId);
		org.assertj.core.api.Assertions.assertThat(order.hasNonNull("paymentMethod"))
				.as("a table order carries NO payment method — it is paid at the table")
				.isFalse();
	}

	@Test
	void retiroOrderStillRequiresPaymentMethod() throws Exception {

		mockMvc.perform(post("/api/orders/menu/{slug}", SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"customerName\":\"Cliente Retiro\",\"customerPhone\":\"1155551234\","
								+ "\"orderType\":\"RETIRO\",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Debe indicar método de pago"));
	}

	@Test
	void deliveryOrderStillRequiresPaymentMethod() throws Exception {

		// DELIVERY boundary (verifier probe 1): same legacy rule as RETIRO.
		mockMvc.perform(post("/api/orders/menu/{slug}", SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"customerName\":\"Cliente Delivery\",\"customerPhone\":\"1155551234\","
								+ "\"orderType\":\"DELIVERY\",\"customerAddress\":\"Calle Falsa 123\","
								+ "\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Debe indicar método de pago"));
	}

	@Test
	void salonOrderWithoutPaymentMethodAppearsAsNullInOrderManagement() throws Exception {

		// Verifier probe 2: the management listing (OrderManagementMapperImpl)
		// must not break on a null paymentMethod either.
		String body = mockMvc.perform(post("/api/orders/menu/{slug}", SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"customerName\":\"Cliente Mesa Lista\",\"customerPhone\":\"1155551234\","
								+ "\"orderType\":\"SALON\",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}],"
								+ "\"tableId\":" + tableId + "}"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		long orderId = objectMapper.readTree(body).get("orderId").asLong();

		String listing = mockMvc.perform(get("/api/business/orders")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		JsonNode created = null;
		for (JsonNode node : objectMapper.readTree(listing)) {
			if (node.get("orderId").asLong() == orderId) {
				created = node;
				break;
			}
		}

		org.assertj.core.api.Assertions.assertThat(created)
				.as("the SALON order without payment method must appear in the management listing")
				.isNotNull();
		org.assertj.core.api.Assertions.assertThat(created.get("paymentMethod") == null || created.get("paymentMethod").isNull())
				.as("the management listing must carry the null payment method, not a fabricated one")
				.isTrue();
	}

	@Test
	void salonOrderWithPaymentMethodKeepsAcceptingIt() throws Exception {

		// Staff POS flow: a SALON order created at the console carries a
		// payment method and must keep working unchanged.
		mockMvc.perform(post("/api/orders/menu/{slug}", SLUG)
						.header(HttpHeaders.AUTHORIZATION, owner.bearerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"customerName\":\"Cliente POS\",\"customerPhone\":\"1155551234\","
								+ "\"orderType\":\"SALON\",\"paymentMethod\":\"EFECTIVO\","
								+ "\"items\":[{\"productId\":" + productId + ",\"quantity\":1}],"
								+ "\"tableId\":" + tableId + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentMethod").value("EFECTIVO"));
	}
}
