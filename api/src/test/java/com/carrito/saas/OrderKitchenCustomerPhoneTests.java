package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * The customer's phone must travel with the order all the way to the kitchen
 * screen: a public menu order created with {@code customerPhone} must expose
 * that phone on {@code GET /api/business/orders/active} for the owning
 * business, so the KDS card can render it as a tap-to-call link.
 *
 * <p>MockMvc is assembled manually with the real security
 * {@link FilterChainProxy} and a real {@link JwtUtil}-minted token seeded by
 * {@link BusinessAuthSeed#seedOwner}; {@code @Transactional} rolls every
 * fixture back at test end.</p>
 */
@SpringBootTest
@Transactional
class OrderKitchenCustomerPhoneTests {

	private static final Long BUSINESS_ID = 987_654_330L;

	private static final String SLUG = "kitchen-phone-test-business";

	private static final String USERNAME = "kitchen-phone-owner";

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
	private com.carrito.saas.repository.jpa.BusinessHoursRepository businessHoursRepository;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, USERNAME);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, BUSINESS_ID);
	}

	@Test
	void activeOrdersExposeTheCustomerPhoneCreatedThroughThePublicMenu() throws Exception {

		// --- Seed: category + product owned by the business ---------------------
		Business business = businessRepository.findById(BUSINESS_ID).orElseThrow();

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Menu");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName("Kitchen Phone Test Product");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("30.00"));
		product.setStock(10);
		product.setActive(true);
		product = productRepository.saveAndFlush(product);

		// --- Act: public menu order carrying the customer's phone ---------------
		String body = """
				{
				  "customerName": "Cliente Telefono",
				  "customerPhone": "1155551234",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(product.getId());

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("NEW"));

		// --- Assert: the kitchen payload carries the phone ----------------------
		mockMvc.perform(get("/api/business/orders/active")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.customerName == 'Cliente Telefono')].customerPhone")
						.value("1155551234"));
	}

}
