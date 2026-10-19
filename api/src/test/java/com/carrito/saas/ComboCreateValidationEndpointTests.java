package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
import com.carrito.saas.repository.entity.Combo;
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
 * Shape validation for {@code POST /api/combos} (crearCombo): name and price
 * must be validated the way {@code actualizarCombo} and the sibling create
 * paths (CategoryServiceImpl, ProductServiceImpl) already do.
 *
 * <p>Rules reused from the existing write paths, one copy shared by create
 * and update in {@code ComboServiceImpl}:</p>
 *
 * <ul>
 *   <li>name: required, trimmed — blank/whitespace-only is a 400 shape
 *       problem ("El nombre del combo es obligatorio", as in
 *       {@code actualizarCombo});</li>
 *   <li>duplicate name within the same business: 409 CONFLICT, mirroring the
 *       duplicate checks of {@code CategoryServiceImpl.crearCategoria} and
 *       {@code ProductServiceImpl.crearProducto}; the same name in ANOTHER
 *       business must still be created (tenant scoping of the duplicate
 *       finder);</li>
 *   <li>price: null, zero or negative is a 400 shape problem ("El precio
 *       del combo debe ser mayor que cero") — a combo is never free, the
 *       rule lives once in {@code validarForma} so create and update agree.</li>
 * </ul>
 *
 * <p>MockMvc is assembled manually with the real security
 * {@link FilterChainProxy} (same shape as
 * {@link ComboCreateEndpointTests}). {@code @Transactional} rolls back every
 * fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class ComboCreateValidationEndpointTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_703_101L;
	private static final Long BUSINESS_B_ID = 987_703_102L;

	private static final String SLUG_A = "combo-create-validation-a";
	private static final String SLUG_B = "combo-create-validation-b";

	private static final String OWNER_A_USERNAME = "combo-validation-owner-a";
	private static final String OWNER_B_USERNAME = "combo-validation-owner-b";

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

	private BusinessAuthSeed.AuthenticatedBusiness ownerA;
	private BusinessAuthSeed.AuthenticatedBusiness ownerB;

	private Category categoryA;
	private Category categoryB;

	private Product ownProductOne;
	private Product otherBusinessProduct;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		ownerA = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_A_USERNAME);
		ownerB = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_B_ID, SLUG_B, OWNER_B_USERNAME);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Business businessB = businessRepository.findById(BUSINESS_B_ID).orElseThrow();

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Combo Validation Cat A"));
		categoryB = categoryRepository.saveAndFlush(category(businessB, "Combo Validation Cat B"));

		ownProductOne = product(categoryA, "Combo Validation Product One");
		otherBusinessProduct = product(categoryB, "Combo Validation Product B");
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

	/** Builds the POST body for the given name, price, category and product ids. */
	private String comboBody(String name, String price, Long categoryId, Long... productIds) {

		StringBuilder items = new StringBuilder();
		for (int i = 0; i < productIds.length; i++) {
			if (i > 0) {
				items.append(",");
			}
			items.append("{ \"productId\": ").append(productIds[i]).append(", \"quantity\": 1 }");
		}

		return """
				{
				  "name": %s,
				  "price": %s,
				  "categoryId": %d,
				  "products": [%s]
				}
				""".formatted(name == null ? null : "\"" + name + "\"", price, categoryId, items);
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

	/** Blank name is a shape problem: 400 BEFORE any lookup, no combo stored. */
	@Test
	void creatingComboWithBlankNameReturns400AndCreatesNoCombo() throws Exception {

		mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody("", "250.00", categoryA.getId(), ownProductOne.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El nombre del combo es obligatorio"));

		assertThat(countCombosForTestBusinesses())
				.as("a rejected payload must not persist any combo")
				.isEqualTo(0L);
	}

	/** Whitespace-only name is blank: same 400 as the empty string. */
	@Test
	void creatingComboWithWhitespaceOnlyNameReturns400AndCreatesNoCombo() throws Exception {

		mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody("   ", "250.00", categoryA.getId(), ownProductOne.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El nombre del combo es obligatorio"));

		assertThat(countCombosForTestBusinesses())
				.as("a rejected payload must not persist any combo")
				.isEqualTo(0L);
	}

	/**
	 * Duplicate name within the SAME business: 409 CONFLICT with the message
	 * shape the sibling create paths use (categoría → "Ya existe una categoría
	 * con ese nombre para este negocio").
	 */
	@Test
	void creatingComboWithDuplicateNameInSameBusinessReturns409() throws Exception {

		mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody("Combo Duplicado", "250.00", categoryA.getId(), ownProductOne.getId())))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody("Combo Duplicado", "300.00", categoryA.getId(), ownProductOne.getId())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe un combo con ese nombre para este negocio"));
	}

	/**
	 * The scoping half of the duplicate rule: the same name in ANOTHER
	 * business is a different tenant and must still be created.
	 */
	@Test
	void creatingComboWithSameNameInAnotherBusinessIsCreated() throws Exception {

		mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody("Combo Compartido", "250.00", categoryA.getId(), ownProductOne.getId())))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, ownerB.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody("Combo Compartido", "250.00", categoryB.getId(), otherBusinessProduct.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Combo Compartido"));

		assertThat(countCombosForTestBusinesses())
				.as("both tenants must own their own combo with the same name")
				.isEqualTo(2L);
	}

	/** Negative price is a shape problem: 400, no combo stored. */
	@Test
	void creatingComboWithNegativePriceReturns400AndCreatesNoCombo() throws Exception {

		mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody("Combo Barato", "-1.00", categoryA.getId(), ownProductOne.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El precio del combo debe ser mayor que cero"));

		assertThat(countCombosForTestBusinesses())
				.as("a rejected payload must not persist any combo")
				.isEqualTo(0L);
	}

	/**
	 * A combo is never free: price must be greater than zero, so 0 is a 400
	 * shape problem too — no combo may be created.
	 */
	@Test
	void creatingComboWithPriceZeroReturns400AndCreatesNoCombo() throws Exception {

		mockMvc.perform(post("/api/combos")
					.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader())
					.contentType(MediaType.APPLICATION_JSON)
					.content(comboBody("Combo Gratis", "0", categoryA.getId(), ownProductOne.getId())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("El precio del combo debe ser mayor que cero"));

		assertThat(countCombosForTestBusinesses())
				.as("a rejected zero-price payload must not persist any combo")
				.isEqualTo(0L);
	}

	/** Happy path: the created row is what the caller sent. */
	@Test
	void validComboCreatePersistsNamePriceAndItemsAsSent() throws Exception {

		String responseBody = mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody("Combo Válido", "250.00", categoryA.getId(), ownProductOne.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.name").value("Combo Válido"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		long comboId = new com.fasterxml.jackson.databind.ObjectMapper()
				.readTree(responseBody).path("id").asLong();

		entityManager.flush();
		entityManager.clear();

		Combo persisted = comboRepository.findById(comboId).orElseThrow();

		assertThat(persisted.getName()).isEqualTo("Combo Válido");
		assertThat(persisted.getPrice()).isEqualByComparingTo("250.00");
		assertThat(persisted.getItems())
				.as("the component items must be stored")
				.hasSize(1);
		assertThat(persisted.getItems().get(0).getProduct().getId())
				.isEqualTo(ownProductOne.getId());
	}

}
