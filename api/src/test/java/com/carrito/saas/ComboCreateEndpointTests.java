package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;

import org.hamcrest.Matchers;
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
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Contract for {@code POST /api/combos} (admin panel combo creation).
 *
 * <p>The business anchor is the JWT, never the request body: both the combo's
 * {@code categoryId} and every referenced {@code productId} must be resolved
 * with business-scoped lookups. A foreign id (a row that exists but belongs to
 * another business) resolves exactly like a missing one — 404, never a silent
 * success — because ids travel through a global namespace and the caller must
 * not be able to probe which ids exist outside their tenant.</p>
 *
 * <p>Status codes: 404 for a foreign/missing category or product (house
 * convention, as in {@code ProductServiceImpl}), NOT 400: the request shape is
 * valid, it references a resource that does not exist in the caller's tenant.
 * {@code categoryId} missing is a shape problem and maps to 400 VALIDATION.</p>
 *
 * <p>Before this slice, {@code crearCombo} never set {@code Combo.category}
 * (a {@code nullable=false} column) and resolved products with a bare
 * {@code findById}, so every creation crashed with a 500 (mapper NPE on the
 * null category) and a caller could build a combo out of another business's
 * products.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy} (Spring Boot 4 moved {@code @AutoConfigureMockMvc}
 * into a module {@code spring-boot-starter-test} does not bring).
 * {@code @Transactional} rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class ComboCreateEndpointTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_702_101L;
	private static final Long BUSINESS_B_ID = 987_702_102L;

	private static final String SLUG_A = "combo-create-a";
	private static final String SLUG_B = "combo-create-b";

	private static final String OWNER_USERNAME = "combo-create-owner";

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

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Category categoryA;
	private Category categoryB;

	private Product ownProductOne;
	private Product ownProductTwo;
	private Product foreignProduct;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		Business businessB = BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_B_ID, SLUG_B);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Combo Create Cat A"));
		categoryB = categoryRepository.saveAndFlush(category(businessB, "Combo Create Cat B"));

		ownProductOne = product(categoryA, "Combo Create Own Product One");
		ownProductTwo = product(categoryA, "Combo Create Own Product Two");
		foreignProduct = product(categoryB, "Combo Create Foreign Product");
	}

	private Category category(Business business, String name) {
		Category c = new Category();
		c.setBusiness(business);
		c.setName(name);
		return c;
	}

	private Product product(Category category, String name) {
		Product p = new Product();
		p.setCategory(category);
		p.setName(name);
		p.setPrice(new BigDecimal("100.00"));
		p.setCost(new BigDecimal("20.00"));
		p.setStock(5);
		p.setActive(true);
		return productRepository.saveAndFlush(p);
	}

	/** Builds the POST body for the given category and component product ids. */
	private String comboBody(Long categoryId, Long... productIds) {

		StringBuilder items = new StringBuilder();
		for (int i = 0; i < productIds.length; i++) {
			if (i > 0) {
				items.append(",");
			}
			items.append("{ \"productId\": ").append(productIds[i]).append(", \"quantity\": 1 }");
		}

		return """
				{
				  "name": "Combo de prueba",
				  "price": 250.00,
				  "categoryId": %d,
				  "products": [%s]
				}
				""".formatted(categoryId, items);
	}

	private long countCombosForTestBusinesses() {

		return entityManager
				.createQuery(
						"SELECT COUNT(c) FROM Combo c WHERE c.category.business.id IN (:idA, :idB)",
						Long.class)
				.setParameter("idA", BUSINESS_A_ID)
				.setParameter("idB", BUSINESS_B_ID)
				.getSingleResult();
	}

	/**
	 * AC1: a foreign categoryId exists in the global namespace but belongs to
	 * another business, so it must resolve as 404 and no combo row may be
	 * created — not even partially.
	 */
	@Test
	void foreignCategoryReturns404AndCreatesNoCombo() throws Exception {

		mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryB.getId(), ownProductOne.getId())))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		assertThat(countCombosForTestBusinesses())
				.as("a combo referencing a foreign category must not be persisted at all")
				.isEqualTo(0L);
	}

	/**
	 * AC2: a foreign productId must be rejected exactly like a missing one —
	 * 404, because the request shape is valid but the referenced resource does
	 * not exist in the caller's tenant (house convention, NOT 400) — and the
	 * error message must name the offending id so the caller can fix their
	 * request without probing.
	 */
	@Test
	void foreignProductReturns404WithOffendingIdAndCreatesNoCombo() throws Exception {

		mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryA.getId(), ownProductOne.getId(), foreignProduct.getId())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message",
						Matchers.containsString(String.valueOf(foreignProduct.getId()))));

		entityManager.flush();
		entityManager.clear();

		assertThat(countCombosForTestBusinesses())
				.as("a combo referencing a foreign product must not be persisted at all")
				.isEqualTo(0L);
	}

	/**
	 * AC3: the happy path must actually work — the persisted combo carries the
	 * own (non-null) category, its items are stored, and the returned DTO maps
	 * the category id without the previous NPE.
	 */
	@Test
	void validComboPersistsWithOwnCategoryAndItems() throws Exception {

		String responseBody = mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryA.getId(), ownProductOne.getId(), ownProductTwo.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.categoryId").value(categoryA.getId().intValue()))
				.andExpect(jsonPath("$.items[*].productName",
						Matchers.containsInAnyOrder(
								"Combo Create Own Product One", "Combo Create Own Product Two")))
				.andReturn()
				.getResponse()
				.getContentAsString();

		long comboId = new com.fasterxml.jackson.databind.ObjectMapper()
				.readTree(responseBody).path("id").asLong();

		entityManager.flush();
		entityManager.clear();

		var persisted = comboRepository.findById(comboId).orElseThrow();

		assertThat(persisted.getCategory())
				.as("the combo must be persisted with a non-null category")
				.isNotNull();
		assertThat(persisted.getCategory().getId())
				.as("the persisted combo must carry the caller's own category")
				.isEqualTo(categoryA.getId());

		assertThat(persisted.getItems())
				.as("both component items must be stored")
				.hasSize(2);

		assertThat(persisted.getItems().stream()
				.map(cp -> cp.getProduct().getId())
				.toList())
				.containsExactlyInAnyOrder(ownProductOne.getId(), ownProductTwo.getId());
	}

	/**
	 * AC4: a combo created through the POST route must immediately be visible
	 * through the owner-scoped {@code GET /api/combos}.
	 */
	@Test
	void createdComboIsVisibleThroughOwnerScopedList() throws Exception {

		String responseBody = mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryA.getId(), ownProductOne.getId())))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		long comboId = new com.fasterxml.jackson.databind.ObjectMapper()
				.readTree(responseBody).path("id").asLong();

		mockMvc.perform(get("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].id", Matchers.hasItem((int) comboId)))
				.andExpect(jsonPath("$[?(@.id == " + comboId + ")].categoryId",
						Matchers.hasItem(categoryA.getId().intValue())));
	}

}
