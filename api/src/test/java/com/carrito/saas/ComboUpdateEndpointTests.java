package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
import com.carrito.saas.repository.entity.ComboProduct;
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
 * Contract for {@code PUT /api/combos/{id}} (admin panel combo editing).
 *
 * <p>The tenant anchor is the JWT, never the request: a foreign combo id must
 * resolve as 404 — never a silent success — and the foreign row must stay
 * exactly as it was. The category and every referenced product of the new
 * payload resolve with business-scoped lookups, the same guarantee
 * {@code crearCombo} already gives a creation payload.</p>
 *
 * <p>Update semantics: name, price and category are overwritten and the item
 * list is REPLACED (the old {@code combo_products} rows go away, the new ones
 * are stored). Validation runs BEFORE any lookup or mutation so a rejected
 * request cannot even partially apply: name required (trimmed before
 * persisting) and price greater than zero (null, zero and negative all
 * rejected), with the Spanish message style of the rest of the service.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy} (Spring Boot 4 moved {@code @AutoConfigureMockMvc}
 * into a module {@code spring-boot-starter-test} does not bring).
 * {@code @Transactional} rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class ComboUpdateEndpointTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_702_101L;
	private static final Long BUSINESS_B_ID = 987_702_102L;

	private static final String SLUG_A = "combo-update-a";
	private static final String SLUG_B = "combo-update-b";

	private static final String OWNER_USERNAME = "combo-update-owner";

	private static final BigDecimal ORIGINAL_PRICE = new BigDecimal("250.00");

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

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Combo Update Cat A"));
		categoryB = categoryRepository.saveAndFlush(category(businessB, "Combo Update Cat B"));

		ownProductOne = product(categoryA, "Combo Update Own Product One");
		ownProductTwo = product(categoryA, "Combo Update Own Product Two");
		foreignProduct = product(categoryB, "Combo Update Foreign Product");
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

	private Combo combo(Category category, String name, Product component, int quantity) {
		Combo c = new Combo();
		c.setName(name);
		c.setPrice(ORIGINAL_PRICE);
		c.setCategory(category);

		ComboProduct cp = new ComboProduct();
		cp.setCombo(c);
		cp.setProduct(component);
		cp.setQuantity(BigDecimal.valueOf(quantity));
		c.setItems(new ArrayList<>(List.of(cp)));

		return comboRepository.saveAndFlush(c);
	}

	/** Builds the PUT/POST body for the given category and component (productId, quantity) pairs. */
	private String comboBody(Long categoryId, String name, String price, long... productIds) {

		StringBuilder items = new StringBuilder();
		for (int i = 0; i < productIds.length; i++) {
			if (i > 0) {
				items.append(",");
			}
			items.append("{ \"productId\": ").append(productIds[i])
					.append(", \"quantity\": ").append(i + 2).append(" }");
		}

		return """
				{
				  "name": "%s",
				  "price": %s,
				  "categoryId": %d,
				  "products": [%s]
				}
				""".formatted(name, price, categoryId, items);
	}

	/**
	 * Happy path: an own combo updated with a new name (untrimmed on the wire,
	 * trimmed in storage), a new price and a REPLACED item list — the original
	 * component goes away and the two new components carry their quantities.
	 */
	@Test
	void updatingOwnComboReturns200AndReplacesNamePriceAndItems() throws Exception {

		Combo own = combo(categoryA, "Combo Original", ownProductOne, 1);

		mockMvc.perform(put("/api/combos/" + own.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryA.getId(), "  Combo Editado  ", "320.50",
								ownProductTwo.getId(), ownProductOne.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(own.getId()))
				.andExpect(jsonPath("$.name").value("Combo Editado"))
				.andExpect(jsonPath("$.price").value(320.50))
				.andExpect(jsonPath("$.categoryId").value(categoryA.getId().intValue()))
				.andExpect(jsonPath("$.items[*].productName", Matchers.containsInAnyOrder(
						"Combo Update Own Product One", "Combo Update Own Product Two")));

		entityManager.flush();
		entityManager.clear();

		Combo persisted = comboRepository.findById(own.getId()).orElseThrow();

		assertThat(persisted.getName())
				.as("an accepted update must persist the trimmed name")
				.isEqualTo("Combo Editado");
		assertThat(persisted.getPrice())
				.as("an accepted update must persist the new price")
				.isEqualByComparingTo(new BigDecimal("320.50"));
		assertThat(persisted.getCategory().getId())
				.as("an accepted update must keep the combo in the caller's own category")
				.isEqualTo(categoryA.getId());

		assertThat(persisted.getItems())
				.as("the update must REPLACE the item list, not append to it")
				.hasSize(2);
		assertThat(persisted.getItems()).allSatisfy(cp -> {
			assertThat(cp.getCombo().getId()).isEqualTo(own.getId());
			assertThat(cp.getQuantity().intValue()).isBetween(2, 3);
		});
		assertThat(persisted.getItems().stream()
				.map(cp -> cp.getProduct().getId())
				.toList())
				.containsExactlyInAnyOrder(ownProductOne.getId(), ownProductTwo.getId());
	}

	/**
	 * A foreign combo id is invisible: the update resolves as 404 and the
	 * foreign row keeps its original name, price and items.
	 */
	@Test
	void updatingForeignComboReturns404AndLeavesForeignRowUntouched() throws Exception {

		Combo foreign = combo(categoryB, "Combo Update Foreign", foreignProduct, 1);

		mockMvc.perform(put("/api/combos/" + foreign.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryA.getId(), "Nombre Atacante", "1.00",
								ownProductOne.getId())))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		Combo foreignAfter = comboRepository.findById(foreign.getId()).orElseThrow();

		assertThat(foreignAfter.getName())
				.as("a cross-tenant update attempt must not rename the foreign combo")
				.isEqualTo("Combo Update Foreign");
		assertThat(foreignAfter.getPrice())
				.as("a cross-tenant update attempt must not change the foreign combo's price")
				.isEqualByComparingTo(ORIGINAL_PRICE);
		assertThat(foreignAfter.getCategory().getId())
				.as("a cross-tenant update attempt must not re-point the foreign combo's category")
				.isEqualTo(categoryB.getId());
		assertThat(foreignAfter.getItems())
				.as("a cross-tenant update attempt must not touch the foreign combo's items")
				.hasSize(1);
		assertThat(foreignAfter.getItems().get(0).getProduct().getId())
				.isEqualTo(foreignProduct.getId());
	}

	/**
	 * A payload referencing another business's product must be rejected with
	 * the same 404 (naming the offending id) that creation already uses — and
	 * the own combo must keep its original name, price and items.
	 */
	@Test
	void updatingOwnComboWithForeignProductReturns404AndChangesNothing() throws Exception {

		Combo own = combo(categoryA, "Combo Original", ownProductOne, 1);

		mockMvc.perform(put("/api/combos/" + own.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryA.getId(), "Combo Atacante", "10.00",
								ownProductTwo.getId(), foreignProduct.getId())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message",
						Matchers.containsString(String.valueOf(foreignProduct.getId()))));

		entityManager.flush();
		entityManager.clear();

		Combo ownAfter = comboRepository.findById(own.getId()).orElseThrow();

		assertThat(ownAfter.getName())
				.as("a rejected foreign-product payload must not rename the own combo")
				.isEqualTo("Combo Original");
		assertThat(ownAfter.getPrice())
				.as("a rejected foreign-product payload must not change the own combo's price")
				.isEqualByComparingTo(ORIGINAL_PRICE);
		assertThat(ownAfter.getItems())
				.as("a rejected foreign-product payload must not touch the own combo's items")
				.hasSize(1);
		assertThat(ownAfter.getItems().get(0).getProduct().getId())
				.isEqualTo(ownProductOne.getId());
	}

	/** A blank name is a shape problem: 400 BEFORE any lookup, combo untouched. */
	@Test
	void updatingOwnComboWithBlankNameReturns400AndChangesNothing() throws Exception {

		Combo own = combo(categoryA, "Combo Original", ownProductOne, 1);

		mockMvc.perform(put("/api/combos/" + own.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryA.getId(), "   ", "10.00", ownProductTwo.getId())))
				.andExpect(status().isBadRequest());

		entityManager.flush();
		entityManager.clear();

		Combo ownAfter = comboRepository.findById(own.getId()).orElseThrow();
		assertThat(ownAfter.getName())
				.as("a rejected blank-name update must not touch the own combo")
				.isEqualTo("Combo Original");
		assertThat(ownAfter.getItems()).hasSize(1);
	}

	/** A negative price is a shape problem: 400 BEFORE any lookup, combo untouched. */
	@Test
	void updatingOwnComboWithNegativePriceReturns400AndChangesNothing() throws Exception {

		Combo own = combo(categoryA, "Combo Original", ownProductOne, 1);

		mockMvc.perform(put("/api/combos/" + own.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryA.getId(), "Combo Precio Negro", "-1.00",
								ownProductTwo.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("El precio del combo debe ser mayor que cero"));

		entityManager.flush();
		entityManager.clear();

		Combo ownAfter = comboRepository.findById(own.getId()).orElseThrow();
		assertThat(ownAfter.getName())
				.as("a rejected negative-price update must not touch the own combo")
				.isEqualTo("Combo Original");
		assertThat(ownAfter.getPrice())
				.isEqualByComparingTo(ORIGINAL_PRICE);
	}

	/**
	 * A combo is never free: updating the price to 0 is a 400 shape problem,
	 * same rule as create, and the stored price must stay untouched.
	 */
	@Test
	void updatingOwnComboWithZeroPriceReturns400AndChangesNothing() throws Exception {

		Combo own = combo(categoryA, "Combo Original", ownProductOne, 1);

		mockMvc.perform(put("/api/combos/" + own.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryA.getId(), "Combo Renombrado", "0",
								ownProductTwo.getId())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("El precio del combo debe ser mayor que cero"));

		entityManager.flush();
		entityManager.clear();

		Combo ownAfter = comboRepository.findById(own.getId()).orElseThrow();
		assertThat(ownAfter.getName())
				.as("a rejected zero-price update must not touch the own combo")
				.isEqualTo("Combo Original");
		assertThat(ownAfter.getPrice())
				.as("the stored price must stay exactly as it was")
				.isEqualByComparingTo(ORIGINAL_PRICE);
		assertThat(ownAfter.getItems())
				.as("a rejected update must not touch the own combo's items")
				.hasSize(1);
	}

	/**
	 * Regression: adding PUT /api/combos/{id} must not disturb the creation
	 * route the panel already uses.
	 */
	@Test
	void creatingComboStillWorksAfterAddingUpdate() throws Exception {

		mockMvc.perform(post("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(comboBody(categoryA.getId(), "Combo de prueba", "250.00",
								ownProductOne.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.items[*].productName",
						Matchers.hasItem("Combo Update Own Product One")));
	}

}
