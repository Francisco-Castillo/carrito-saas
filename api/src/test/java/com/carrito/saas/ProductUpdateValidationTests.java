package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Validation contract for {@code PUT /api/{id}} (product update).
 *
 * <p>{@code name}, {@code price} and {@code cost} are required: {@code name}
 * used to blow up with an NPE (500) and a null {@code price}/{@code cost}
 * surfaced as a database constraint violation dressed as a 500. A body with a
 * missing required field must be rejected with 400 {@code VALIDATION}, the
 * message must name the missing field in Spanish, and the row must remain
 * exactly as it was. A complete body still updates and persists.</p>
 */
@SpringBootTest
@Transactional
class ProductUpdateValidationTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_802_101L;

	private static final String SLUG_A = "product-update-validation-a";

	private static final String OWNER_USERNAME = "product-update-validation-owner";

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

	private Product product;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Update Validation Cat A"));

		product = new Product();
		product.setCategory(categoryA);
		product.setName("Update Validation Original");
		product.setDescription("descripcion original");
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

	private String updateBody(String nameJson, String priceJson, String costJson) {
		return """
				{
				  "id": %d,
				  "name": %s,
				  "description": "descripcion original",
				  "price": %s,
				  "cost": %s,
				  "categoryId": %d,
				  "active": true,
				  "stock": 5
				}
				""".formatted(product.getId(), nameJson, priceJson, costJson, categoryA.getId());
	}

	/** A null name must answer 400 VALIDATION and leave the row untouched. */
	@Test
	void nullNameReturns400AndLeavesRowUnchanged() throws Exception {

		mockMvc.perform(put("/api/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("null", "\"100.00\"", "\"20.00\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El nombre es obligatorio"));

		Product after = ownProductAfterRequest();
		assertThat(after.getName())
				.as("a rejected update must not rename the product")
				.isEqualTo("Update Validation Original");
		assertThat(after.getPrice()).isEqualByComparingTo("100.00");
	}

	/** A blank name is as missing as a null one: 400 VALIDATION, row intact. */
	@Test
	void blankNameReturns400AndLeavesRowUnchanged() throws Exception {

		mockMvc.perform(put("/api/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"   \"", "\"100.00\"", "\"20.00\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El nombre es obligatorio"));

		assertThat(ownProductAfterRequest().getName())
				.as("a blank-name update must not blank out the stored name")
				.isEqualTo("Update Validation Original");
	}

	/** price is NOT NULL in the database: a null must be a 400, not a 500. */
	@Test
	void nullPriceReturns400() throws Exception {

		mockMvc.perform(put("/api/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"Nombre Valido\"", "null", "\"20.00\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El precio es obligatorio"));

		assertThat(ownProductAfterRequest().getPrice())
				.as("a rejected update must not touch the stored price")
				.isEqualByComparingTo("100.00");
	}

	/** cost is NOT NULL in the database: a null must be a 400, not a 500. */
	@Test
	void nullCostReturns400() throws Exception {

		mockMvc.perform(put("/api/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"Nombre Valido\"", "\"100.00\"", "null")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El costo es obligatorio"));

		assertThat(ownProductAfterRequest().getCost())
				.as("a rejected update must not touch the stored cost")
				.isEqualByComparingTo("20.00");
	}

	/** A complete body still updates: 200 and the new values persist. */
	@Test
	void validUpdateReturns200AndPersists() throws Exception {

		mockMvc.perform(put("/api/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"Nombre Actualizado\"", "\"150.00\"", "\"25.00\"")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(product.getId()))
				.andExpect(jsonPath("$.name").value("Nombre Actualizado"))
				.andExpect(jsonPath("$.price").value(150.00));

		Product after = ownProductAfterRequest();
		assertThat(after.getName()).isEqualTo("Nombre Actualizado");
		assertThat(after.getPrice()).isEqualByComparingTo("150.00");
		assertThat(after.getCost()).isEqualByComparingTo("25.00");
	}

}
