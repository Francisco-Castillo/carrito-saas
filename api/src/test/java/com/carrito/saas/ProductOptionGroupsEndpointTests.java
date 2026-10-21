package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.carrito.saas.repository.jpa.ProductOptionGroupRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Endpoint contract for product option groups (odd/tasks/product-options.md
 * T2, S1-S3 + S6).
 *
 * <p>POST/PUT accept {@code optionGroups} (per-product definition, no shared
 * library): required/multiple rules are validated with 400 VALIDATION, a
 * saved product round-trips its groups with price deltas, an update replaces
 * the whole set, and a PUT without the field leaves existing groups intact
 * (S6 compat for older clients). Deleting the product removes its groups.</p>
 */
@SpringBootTest
@Transactional
class ProductOptionGroupsEndpointTests {

	private static final Long BUSINESS_A_ID = 987_803_101L;

	private static final String SLUG_A = "product-option-groups-a";

	private static final String OWNER_USERNAME = "product-option-groups-owner";

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
	private ProductOptionGroupRepository optionGroupRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Category categoryA;

	private Product product;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Option Groups Cat A"));

		product = new Product();
		product.setCategory(categoryA);
		product.setName("Option Groups Original");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("20.00"));
		product.setStock(5);
		product.setActive(true);
		product = productRepository.saveAndFlush(product);
	}

	private Category category(Business business, String name) {
		Category c = new Category();
		c.setBusiness(business);
		c.setName(name);
		return c;
	}

	private Product ownProductAfterRequest() {
		entityManager.flush();
		entityManager.clear();
		return productRepository.findById(product.getId()).orElseThrow();
	}

	private String createBody(String optionGroupsJson) {
		return """
				{
				  "categoryId": %d,
				  "name": "Burger Con Grupos",
				  "description": "burger de prueba",
				  "price": 100.00,
				  "cost": 20.00,
				  "stock": 5,
				  "optionGroups": %s
				}
				""".formatted(categoryA.getId(), optionGroupsJson);
	}

	private String updateBody(String optionGroupsJson) {
		return """
				{
				  "id": %d,
				  "name": "Option Groups Original",
				  "price": 100.00,
				  "cost": 20.00,
				  "categoryId": %d,
				  "active": true,
				  "stock": 5,
				  "optionGroups": %s
				}
				""".formatted(product.getId(), categoryA.getId(), optionGroupsJson);
	}

	private static final String VALID_GROUPS = """
			[
			  {
			    "name": "Tipo de pan",
			    "required": true,
			    "multiple": false,
			    "options": [
			      { "name": "Blanco", "priceDelta": 0 },
			      { "name": "Integral", "priceDelta": 200 }
			    ]
			  },
			  {
			    "name": "Extras",
			    "required": false,
			    "multiple": true,
			    "minSelections": 0,
			    "maxSelections": 3,
			    "options": [
			      { "name": "Cheddar", "priceDelta": 300 },
			      { "name": "Panceta", "priceDelta": 500 }
			    ]
			  }
			]
			""";

	/** POST persists groups with their options and price deltas, and reads them back. */
	@Test
	void createProductPersistsGroupsAndRoundTrips() throws Exception {

		String response = mockMvc
				.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(VALID_GROUPS)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		Long savedId = entityManager
				.createQuery("select p.id from Product p where p.name = 'Burger Con Grupos'", Long.class)
				.getSingleResult();
		entityManager.clear();
		Product saved = productRepository.findById(savedId).orElseThrow();

		assertThat(saved.getOptionGroups()).hasSize(2);

		var bread = saved.getOptionGroups().stream()
				.filter(g -> g.getName().equals("Tipo de pan")).findFirst().orElseThrow();
		assertThat(bread.isRequired()).isTrue();
		assertThat(bread.isMultiple()).isFalse();
		assertThat(bread.getOptions()).extracting("name").containsExactly("Blanco", "Integral");
		assertThat(bread.getOptions().get(1).getPriceDelta()).isEqualByComparingTo("200");

		var extras = saved.getOptionGroups().stream()
				.filter(g -> g.getName().equals("Extras")).findFirst().orElseThrow();
		assertThat(extras.isRequired()).isFalse();
		assertThat(extras.isMultiple()).isTrue();
		assertThat(extras.getMinSelections()).isZero();
		assertThat(extras.getMaxSelections()).isEqualTo(3);

		// The admin response also carries the groups (the menu reads ProductDTO).
		assertThat(response).contains("Tipo de pan").contains("Integral").contains("300");
	}

	/** PUT replaces the whole group set. */
	@Test
	void updateProductReplacesGroups() throws Exception {

		product = productRepository.findById(product.getId()).orElseThrow();
		String oneGroup = """
				[ { "name": "Tamaño", "required": true, "multiple": false,
				    "options": [ { "name": "Grande", "priceDelta": 500 } ] } ]
				""";
		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody(oneGroup)))
				.andExpect(status().isOk());

		Product after = ownProductAfterRequest();
		assertThat(after.getOptionGroups()).hasSize(1);
		assertThat(after.getOptionGroups().get(0).getName()).isEqualTo("Tamaño");
	}

	/** A PUT without optionGroups keeps existing groups (S6: older clients keep working). */
	@Test
	void updateWithoutGroupsFieldKeepsExistingGroups() throws Exception {

		product = productRepository.findById(product.getId()).orElseThrow();
		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody(VALID_GROUPS)))
				.andExpect(status().isOk());
		assertThat(ownProductAfterRequest().getOptionGroups()).hasSize(2);

		String legacyBody = """
				{
				  "id": %d,
				  "name": "Option Groups Original",
				  "price": 100.00,
				  "cost": 20.00,
				  "categoryId": %d,
				  "active": true,
				  "stock": 5
				}
				""".formatted(product.getId(), categoryA.getId());

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(legacyBody))
				.andExpect(status().isOk());

		assertThat(ownProductAfterRequest().getOptionGroups())
				.as("an update without optionGroups must not delete stored groups")
				.hasSize(2);
	}

	/** Group with no options is a 400 naming the group. */
	@Test
	void groupWithoutOptionsReturns400() throws Exception {

		String emptyGroup = """
				[ { "name": "Tipo de pan", "required": true, "multiple": false, "options": [] } ]
				""";
		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(emptyGroup)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El grupo \"Tipo de pan\" necesita al menos una opción"));
	}

	/** Negative price delta is a 400. */
	@Test
	void negativePriceDeltaReturns400() throws Exception {

		String negative = """
				[ { "name": "Extras", "required": false, "multiple": true,
				    "options": [ { "name": "Cheddar", "priceDelta": -1 } ] } ]
				""";
		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(negative)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El recargo de una opción no puede ser negativo"));
	}

	/** Required multiple group must demand min >= 1. */
	@Test
	void requiredMultipleGroupWithMinZeroReturns400() throws Exception {

		String badMin = """
				[ { "name": "Salsas", "required": true, "multiple": true, "minSelections": 0,
				    "options": [ { "name": "Ketchup", "priceDelta": 0 } ] } ]
				""";
		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(badMin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Un grupo obligatorio y múltiple necesita mínimo 1 selección"));
	}

	/** min > max is a 400. */
	@Test
	void minGreaterThanMaxReturns400() throws Exception {

		String badRange = """
				[ { "name": "Salsas", "required": false, "multiple": true,
				    "minSelections": 3, "maxSelections": 2,
				    "options": [ { "name": "Ketchup", "priceDelta": 0 } ] } ]
				""";
		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(badRange)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El mínimo de selecciones no puede superar el máximo"));
	}

	/** Deleting the product removes its groups (no orphan rows). */
	@Test
	void deleteProductRemovesItsGroups() throws Exception {

		product = productRepository.findById(product.getId()).orElseThrow();
		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody(VALID_GROUPS)))
				.andExpect(status().isOk());

		Long productId = product.getId();
		mockMvc.perform(delete("/api/products/" + productId)
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNoContent());

		entityManager.flush();
		entityManager.clear();
		assertThat(productRepository.findById(productId)).isEmpty();
		assertThat(optionGroupRepository.findByProductIdOrderByIdAsc(productId))
				.as("deleting the product must delete its option groups")
				.isEmpty();
	}
}
