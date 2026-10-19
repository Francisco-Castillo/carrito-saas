package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.TableStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contract of SALON orders with table assignment ({@code OrderType.SALON} +
 * optional {@code tableId} in {@code OrderRequestDTO}):
 *
 * <ul>
 * <li>SALON without a table is a validation error (the counter POS must pick
 * a mesa).</li>
 * <li>SALON with a table of ANOTHER business must not resolve: 404, without
 * leaking that the table exists elsewhere.</li>
 * <li>RETIRO (and DELIVERY) ignore any tableId: no table is ever set on the
 * order.</li>
 * <li>SALON with the business's own table sets it, and the created-order
 * response exposes it.</li>
 * </ul>
 *
 * <p>Orders go through the public menu endpoint (same createOrder path the
 * counter POS will call); persistence is asserted against the repositories
 * and {@code @Transactional} rolls every fixture back at test end.</p>
 */
@SpringBootTest
@Transactional
class SalonOrderTableTests {

	private static final Long BUSINESS_A_ID = 987_713_201L;
	private static final Long BUSINESS_B_ID = 987_713_202L;
	private static final String SLUG_A = "salon-order-a";
	private static final String SLUG_B = "salon-order-b";

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
	private TableRepository tableRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private com.carrito.saas.repository.jpa.BusinessHoursRepository businessHoursRepository;

	private MockMvc mockMvc;

	private long productIdA;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, "salon-owner-a");
		BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_B_ID, SLUG_B, "salon-owner-b");
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, BUSINESS_A_ID);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, BUSINESS_B_ID);

		productIdA = seedProduct(SLUG_A, "Producto Salón");
	}

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

	private long seedTable(String slug, int number) {
		Business business = businessRepository.findBySlug(slug).orElseThrow();

		RestaurantTable table = new RestaurantTable();
		table.setBusiness(business);
		table.setTableNumber(number);
		table.setTableName("Mesa " + number);
		table.setQrToken(UUID.randomUUID().toString());
		table.setStatus(TableStatus.AVAILABLE);
		return tableRepository.saveAndFlush(table).getId();
	}

	private ResultActions createOrder(String slug, String orderType, Long tableId) throws Exception {
		String body = """
				{
				  "customerName": "Cliente Salón",
				  "orderType": "%s",
				  "paymentMethod": "EFECTIVO",
				  %s
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(orderType, tableId == null ? "" : "\"tableId\": " + tableId + ",", productIdA);

		return mockMvc.perform(post("/api/orders/menu/" + slug)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				;
	}

	// ------------------------------------------------------------------
	// SALON requires a table
	// ------------------------------------------------------------------

	@Test
	void salonOrderWithoutTableIsRejected() throws Exception {

		createOrder(SLUG_A, "SALON", null)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Debe indicar una mesa para pedidos en salón"));
	}

	// ------------------------------------------------------------------
	// SALON rejects a table of another business (404, no existence leak)
	// ------------------------------------------------------------------

	@Test
	void salonOrderWithForeignTableIsRejected() throws Exception {

		long foreignTableId = seedTable(SLUG_B, 1);

		createOrder(SLUG_A, "SALON", foreignTableId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Mesa no encontrada"));
	}

	// ------------------------------------------------------------------
	// RETIRO ignores the tableId: no table is ever set
	// ------------------------------------------------------------------

	@Test
	void retiroOrderIgnoresTheTableId() throws Exception {

		long tableId = seedTable(SLUG_A, 2);

		ResultActions result = createOrder(SLUG_A, "RETIRO", tableId);

		result.andExpect(status().isOk());

		long orderId = objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("orderId").asLong();

		Order order = orderRepository.findById(orderId).orElseThrow();
		assertThat(order.getRestaurantTable()).isNull();
	}

	// ------------------------------------------------------------------
	// SALON with the business's own table sets it and exposes it
	// ------------------------------------------------------------------

	@Test
	void salonOrderIsCreatedWithItsTable() throws Exception {

		long tableId = seedTable(SLUG_A, 3);

		ResultActions result = createOrder(SLUG_A, "SALON", tableId);

		result.andExpect(status().isOk())
				.andExpect(jsonPath("$.tableId").value((int) tableId))
				.andExpect(jsonPath("$.tableName").value("Mesa 3"));

		long orderId = objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("orderId").asLong();

		Order order = orderRepository.findById(orderId).orElseThrow();
		assertThat(order.getRestaurantTable()).isNotNull();
		assertThat(order.getRestaurantTable().getId()).isEqualTo(tableId);
		assertThat(order.getRestaurantTable().getTableName()).isEqualTo("Mesa 3");
	}

}
