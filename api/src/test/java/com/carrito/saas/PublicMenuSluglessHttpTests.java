package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Slugless public menu for a SINGLE-business deployment.
 *
 * <p>The project no longer carries the business slug in the customer URL: the
 * QR points at a plain {@code /menu} and the deployment hosts exactly one
 * business. The anonymous browser therefore calls {@code GET /api/menu} (no
 * slug) and {@code POST /api/orders/menu} (no slug), and the backend resolves
 * the single business itself.</p>
 *
 * <p>Fail-closed rule: when the deployment holds zero or multiple businesses,
 * the slugless endpoints must NOT guess — they answer the same "Local no
 * encontrado" error contract the slug endpoints use.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy} (same reason as {@link PublicMenuOrderHttpTests}:
 * Spring Boot 4 moved {@code @AutoConfigureMockMvc} out of
 * {@code spring-boot-starter-test}). {@code @Transactional} rolls back every
 * fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class PublicMenuSluglessHttpTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_654_330L;

	private static final String SLUG = "slugless-menu-test-business";

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

	@PersistenceContext
	private EntityManager entityManager;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	private Business seedSingleBusiness() {

		// The dev database may hold seed rows from other sessions; a single-business
		// deployment test needs exactly one. TRUNCATE ... CASCADE clears every
		// table referencing businesses in one transactional statement (Postgres
		// TRUNCATE is transactional, so the rollback restores everything).
		entityManager.createNativeQuery("TRUNCATE TABLE businesses CASCADE").executeUpdate();

		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Slugless Menu Test Business");
		business.setSlug(SLUG);
		business = businessRepository.saveAndFlush(business);

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Menu");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName("Slugless Menu Test Product");
		product.setPrice(new BigDecimal("300.00"));
		product.setCost(new BigDecimal("90.00"));
		product.setStock(10);
		product.setActive(true);
		productRepository.saveAndFlush(product);

		return business;
	}

	@Test
	void anonymousBrowserLoadsMenuWithoutSlug() throws Exception {

		seedSingleBusiness();

		mockMvc.perform(get("/api/menu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.slug").value(SLUG))
				.andExpect(jsonPath("$.business.name").value("Slugless Menu Test Business"))
				.andExpect(jsonPath("$.products[0].name").value("Slugless Menu Test Product"));
	}

	@Test
	void anonymousBrowserPostsOrderWithoutSlug() throws Exception {

		seedSingleBusiness();

		Product product = productRepository.findAll().stream()
				.filter(p -> p.getCategory().getBusiness().getId().equals(BUSINESS_ID))
				.findFirst()
				.orElseThrow();

		String body = """
				{
				  "customerName": "Cliente Sin Slug",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "notes": "",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(product.getId());

		mockMvc.perform(post("/api/orders/menu")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").isNotEmpty())
				.andExpect(jsonPath("$.status").value("NEW"))
				.andExpect(jsonPath("$.businessSlug").value(SLUG));
	}

	@Test
	void sluglessMenuFailsClosedWhenNoBusinessExists() throws Exception {

		// No business seeded: the deployment has nothing to serve. Other rows may
		// live in the shared dev database, so truncate first (transactional) to
		// model an empty deployment.
		entityManager.createNativeQuery("TRUNCATE TABLE businesses CASCADE").executeUpdate();

		mockMvc.perform(get("/api/menu"))
				.andExpect(status().isNotFound());
	}
}
