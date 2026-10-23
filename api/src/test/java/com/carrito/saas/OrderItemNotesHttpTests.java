package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Notas de preferencia por ítem ("sin mayonesa", "sin lechuga") — el cliente
 * las escribe en el modal del producto y tienen que llegar a quien cocina:
 * persistidas en order_items (trim, blank → null), visibles para el KDS
 * (GET /api/business/orders/active) y para la consola de pedidos
 * (GET /api/business/orders), con validación de longitud legible (400).
 */
@SpringBootTest
@Transactional
class OrderItemNotesHttpTests {

	private static final Long BUSINESS_ID = 987_805_101L;

	private static final String SLUG = "order-item-notes-test";

	private static final int MAX_NOTES = 300;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private BusinessHoursRepository businessHoursRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private BusinessUserRepository businessUserRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private Business business;

	private Product product;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Order Item Notes Test");
		business.setSlug(SLUG);
		businessRepository.saveAndFlush(business);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, business.getId());

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Menu");
		category = categoryRepository.saveAndFlush(category);

		product = new Product();
		product.setCategory(category);
		product.setName("Burger Notas Test");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("20.00"));
		product.setStock(10);
		product.setActive(true);
		product = productRepository.saveAndFlush(product);

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, "owner-notes-test");
	}

	private String orderBody(String itemsJson) {
		return """
				{
				  "customerName": "Cliente Notas",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": %s
				}
				""".formatted(itemsJson);
	}

	/** Notas por ítem: trim al persistir, null cuando no hay, y visibles para cocina y consola. */
	@Test
	@DisplayName("item notes persist trimmed and reach the kitchen and management payloads")
	void orderWithItemNotesPersistsAndReachesKitchenAndManagement() throws Exception {

		String body = """
				{
				  "customerName": "Cliente Notas",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [
				    { "productId": %d, "quantity": 1, "notes": "  sin mayonesa, sin lechuga  " },
				    { "productId": %d, "quantity": 1 }
				  ]
				}
				""".formatted(product.getId(), product.getId());

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].notes").value("sin mayonesa, sin lechuga"));

		entityManager.flush();
		entityManager.clear();

		var order = orderRepository.findAll().stream()
				.filter(o -> o.getBusiness() != null && BUSINESS_ID.equals(o.getBusiness().getId()))
				.findFirst().orElseThrow();
		var items = order.getItems();
		assertThat(items).hasSize(2);
		assertThat(items.get(0).getItemNotes()).isEqualTo("sin mayonesa, sin lechuga");
		assertThat(items.get(1).getItemNotes()).isNull();

		// KDS: el ding y la tarjeta tienen que mostrar la preferencia.
		mockMvc.perform(get("/api/business/orders/active")
						.header("Authorization", owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].items[0].notes").value("sin mayonesa, sin lechuga"))
				.andExpect(jsonPath("$[0].items[1].notes").doesNotExist());

		// Consola de pedidos: el histórico del día también.
		mockMvc.perform(get("/api/business/orders")
						.header("Authorization", owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].items[0].notes").value("sin mayonesa, sin lechuga"))
				.andExpect(jsonPath("$[0].items[1].notes").doesNotExist());
	}

	/** Notas sobre el límite: 400 legible, nada se guarda. */
	@Test
	@DisplayName("notes longer than the limit return a legible 400 and store nothing")
	void overlongNotesReturn400() throws Exception {

		String larga = "x".repeat(MAX_NOTES + 1);

		String body = """
				{
				  "customerName": "Cliente Notas",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1, "notes": "%s" } ]
				}
				""".formatted(product.getId(), larga);

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("Las preferencias del producto no pueden superar 300 caracteres"));

		entityManager.flush();
		entityManager.clear();
		assertThat(orderRepository.findAll().stream()
				.filter(o -> o.getBusiness() != null && BUSINESS_ID.equals(o.getBusiness().getId())))
				.as("a rejected order must not be stored")
				.isEmpty();
	}

	/** Notas en blanco no guardan basura: blank → null. */
	@Test
	@DisplayName("blank notes are stored as null")
	void blankNotesAreStoredAsNull() throws Exception {

		String body = """
				{
				  "customerName": "Cliente Notas",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1, "notes": "   " } ]
				}
				""".formatted(product.getId());

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());

		entityManager.flush();
		entityManager.clear();

		var order = orderRepository.findAll().stream()
				.filter(o -> o.getBusiness() != null && BUSINESS_ID.equals(o.getBusiness().getId()))
				.findFirst().orElseThrow();
		assertThat(order.getItems().get(0).getItemNotes()).isNull();
	}
}
