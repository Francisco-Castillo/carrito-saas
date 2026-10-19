package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Cross-tenant write isolation contract for the product endpoints.
 *
 * <p>{@code PUT /api/{id}} and {@code POST /api/productos} take row ids from
 * the request while the tenant anchor is the JWT. Without a business
 * predicate, an authenticated owner of business A could update business B's
 * product by raw id, re-point an own product at B's category, or create a
 * product inside B's category. The contract under test: a foreign row id
 * resolves as 404 (never a silent success), the foreign row is untouched on
 * rejection, and the positive paths keep working with their existing
 * response codes.</p>
 *
 * <p>Fixtures follow the A/B two-business pattern of
 * {@code CrossTenantStockIsolationTests}: two businesses, each with its own
 * category and product, seeded through the repositories and rolled back by
 * {@code @Transactional}. Every state assertion reads back through the
 * repository after flush + clear, i.e. a fresh SELECT against PostgreSQL.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy}. The business id comes from the JWT minted by
 * {@link BusinessAuthSeed#seedOwner}, never from the request.</p>
 */
@SpringBootTest
@Transactional
class CrossTenantProductWriteIsolationTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_702_101L;
	private static final Long BUSINESS_B_ID = 987_702_102L;

	private static final String SLUG_A = "cross-tenant-write-a";
	private static final String SLUG_B = "cross-tenant-write-b";

	private static final String OWNER_USERNAME = "cross-tenant-write-owner";

	private static final BigDecimal OWN_PRICE = new BigDecimal("100.00");
	private static final BigDecimal FOREIGN_PRICE = new BigDecimal("55.00");

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

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Category categoryA;
	private Category categoryB;

	private Product ownProduct;
	private Product foreignProduct;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Business businessB = BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_B_ID, SLUG_B);

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Cross Tenant Write Cat A"));
		categoryB = categoryRepository.saveAndFlush(category(businessB, "Cross Tenant Write Cat B"));

		ownProduct = productRepository.saveAndFlush(product(categoryA, "Cross Tenant Write Own", OWN_PRICE));
		foreignProduct = productRepository.saveAndFlush(product(categoryB, "Cross Tenant Write Foreign", FOREIGN_PRICE));
	}

	private Category category(Business business, String name) {
		Category c = new Category();
		c.setBusiness(business);
		c.setName(name);
		return c;
	}

	private Product product(Category category, String name, BigDecimal price) {
		Product p = new Product();
		p.setCategory(category);
		p.setName(name);
		p.setPrice(price);
		p.setCost(new BigDecimal("20.00"));
		p.setStock(5);
		p.setActive(true);
		return p;
	}

	private String updateBody(Long categoryId) {
		return """
				{
				  "name": "Nombre Atacante",
				  "description": "Descripcion Atacante",
				  "price": 1.00,
				  "cost": 0.50,
				  "stock": 1,
				  "active": false,
				  "categoryId": %d
				}
				""".formatted(categoryId);
	}

	private String createBody(Long categoryId) {
		return """
				{
				  "name": "Producto Atacante",
				  "description": "Descripcion Atacante",
				  "price": 9.99,
				  "cost": 2.00,
				  "stock": 3,
				  "active": true,
				  "categoryId": %d
				}
				""".formatted(categoryId);
	}

	/**
	 * Updating business B's product through A's credentials must resolve as
	 * 404 — never as a silent success — and the foreign row must keep its
	 * original category, name and price.
	 */
	@Test
	void updatingForeignProductReturns404AndLeavesForeignRowUntouched() throws Exception {

		mockMvc.perform(put("/api/products/" + foreignProduct.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody(categoryA.getId())))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		Product foreignAfter = productRepository.findById(foreignProduct.getId()).orElseThrow();
		assertThat(foreignAfter.getCategory().getId())
				.as("a cross-tenant update attempt must not re-point the foreign product's category")
				.isEqualTo(categoryB.getId());
		assertThat(foreignAfter.getName())
				.as("a cross-tenant update attempt must not rename the foreign product")
				.isEqualTo("Cross Tenant Write Foreign");
		assertThat(foreignAfter.getPrice())
				.as("a cross-tenant update attempt must not change the foreign product's price")
				.isEqualByComparingTo(FOREIGN_PRICE);
	}

	/**
	 * Updating an own product but pointing it at another business's category
	 * must resolve as 404 and leave the own row exactly as it was.
	 */
	@Test
	void updatingOwnProductToForeignCategoryReturns404AndLeavesOwnRowUntouched() throws Exception {

		mockMvc.perform(put("/api/products/" + ownProduct.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody(categoryB.getId())))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		Product ownAfter = productRepository.findById(ownProduct.getId()).orElseThrow();
		assertThat(ownAfter.getCategory().getId())
				.as("a rejected category re-point must not move the own product to the foreign category")
				.isEqualTo(categoryA.getId());
		assertThat(ownAfter.getName())
				.as("a rejected update must not rename the own product")
				.isEqualTo("Cross Tenant Write Own");
		assertThat(ownAfter.getPrice())
				.as("a rejected update must not change the own product's price")
				.isEqualByComparingTo(OWN_PRICE);
	}

	/**
	 * Positive control: updating an own product within its own category must
	 * keep the existing 200 and persist the change.
	 */
	@Test
	void updatingOwnProductWithinOwnCategoryReturns200AndPersists() throws Exception {

		mockMvc.perform(put("/api/products/" + ownProduct.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody(categoryA.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(ownProduct.getId()));

		entityManager.flush();
		entityManager.clear();

		Product ownAfter = productRepository.findById(ownProduct.getId()).orElseThrow();
		assertThat(ownAfter.getName())
				.as("an accepted update must persist the new name")
				.isEqualTo("Nombre Atacante");
		assertThat(ownAfter.getPrice())
				.as("an accepted update must persist the new price")
				.isEqualByComparingTo(new BigDecimal("1.00"));
		assertThat(ownAfter.getCategory().getId())
				.as("an accepted update must keep the product in its own category")
				.isEqualTo(categoryA.getId());
	}

	/**
	 * Creating a product inside another business's category must resolve as
	 * 404 and must not create any row inside the foreign category.
	 */
	@Test
	void creatingProductInForeignCategoryReturns404AndCreatesNoRow() throws Exception {

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(categoryB.getId())))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		assertThat(productRepository.findByCategoryId(categoryB.getId()))
				.as("a cross-tenant creation attempt must not create any new row in the foreign category")
				.hasSize(1)
				.first()
				.satisfies(p -> assertThat(p.getId()).isEqualTo(foreignProduct.getId()));
	}

	/**
	 * Positive control: creating a product inside an own category must keep
	 * working and the new row must live in the caller's business.
	 */
	@Test
	void creatingProductInOwnCategorySucceedsAndRowBelongsToCallerBusiness() throws Exception {

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(categoryA.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").isNotEmpty());

		entityManager.flush();
		entityManager.clear();

		assertThat(productRepository.findByCategoryId(categoryA.getId()))
				.as("the created product must exist inside the caller's own category")
				.anySatisfy(p -> {
					assertThat(p.getName()).isEqualTo("Producto Atacante");
					assertThat(p.getCategory().getBusiness().getId()).isEqualTo(BUSINESS_A_ID);
				});
	}

}
