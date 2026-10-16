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
 * Stock is not a free-form integer: it carries domain meaning on both write
 * paths, so the rule lives ONCE and is shared by creation and update.
 *
 * <p>Semantics pinned by the entity ({@code Product.stock}) and honoured by
 * the readers: {@code null} → stock infinito (bebidas), {@code 0} → sin stock
 * (hidden from the public menu by {@code ProductSpecification.hasStock()}),
 * {@code >0} → stock limitado (the atomic {@code decrementStock} floor at
 * order time). A NEGATIVE value means nothing in this domain: it is a 400
 * VALIDATION ("Stock inválido", the analogue of "Costo inválido") and a
 * rejected payload must not mutate anything — the catalogue count and the
 * stored row stay exactly as they were.</p>
 */
@SpringBootTest
@Transactional
class ProductStockNonNegativeEndpointTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_804_101L;

	private static final String SLUG_A = "product-stock-non-negative-a";

	private static final String OWNER_USERNAME = "product-stock-non-negative-owner";

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

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Stock Non Negative Cat A"));

		product = new Product();
		product.setCategory(categoryA);
		product.setName("Stock Non Negative Original");
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

	private long catalogueCount() {
		entityManager.flush();
		entityManager.clear();
		return productRepository.findByCategory_Business_Id(BUSINESS_A_ID).size();
	}

	private Product ownProductAfterRequest() {
		entityManager.flush();
		entityManager.clear();
		return productRepository.findById(product.getId()).orElseThrow();
	}

	private String createBody(String stockJson) {
		return """
				{
				  "categoryId": %d,
				  "name": "Stock Non Negative Nuevo",
				  "description": "descripcion nueva",
				  "price": "50.00",
				  "cost": "10.00",
				  "stock": %s,
				  "active": true
				}
				""".formatted(categoryA.getId(), stockJson);
	}

	private String updateBody(String stockJson) {
		return """
				{
				  "id": %d,
				  "name": "Nombre Valido",
				  "description": "descripcion original",
				  "price": "100.00",
				  "cost": "20.00",
				  "categoryId": %d,
				  "active": true,
				  "stock": %s
				}
				""".formatted(product.getId(), categoryA.getId(), stockJson);
	}

	/** A negative stock means nothing in this domain: 400 on create, nothing stored. */
	@Test
	void createWithNegativeStockReturns400AndLeavesCatalogueUnchanged() throws Exception {

		long before = catalogueCount();

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("-3")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Stock inválido"));

		assertThat(catalogueCount())
				.as("a rejected negative-stock creation must not store any product")
				.isEqualTo(before);
	}

	/** A negative stock means nothing in this domain: 400 on update, row intact. */
	@Test
	void updateToNegativeStockReturns400AndLeavesRowUnchanged() throws Exception {

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("-3")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Stock inválido"));

		Product after = ownProductAfterRequest();
		assertThat(after.getStock())
				.as("a rejected negative-stock update must not touch the stored stock")
				.isEqualTo(5);
		assertThat(after.getName())
				.as("a rejected negative-stock update must not rename the product")
				.isEqualTo("Stock Non Negative Original");
		assertThat(after.getPrice())
				.as("a rejected negative-stock update must not touch the stored price")
				.isEqualByComparingTo("100.00");
	}

	/**
	 * 0 is legitimate stock ("sin stock"): the public menu hides it via
	 * hasStock() — accepted and stored as 0 on creation.
	 */
	@Test
	void createWithZeroStockPersistsZero() throws Exception {

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("0")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stock").value(0));

		Product stored = productRepository
				.findByCategory_Business_Id(BUSINESS_A_ID).stream()
				.filter(p -> "Stock Non Negative Nuevo".equals(p.getName()))
				.findFirst()
				.orElseThrow();
		assertThat(stored.getStock())
				.as("stock 0 must be stored exactly as 0, not normalized")
				.isZero();
	}

	/** 0 is legitimate stock on update too: accepted and stored as 0. */
	@Test
	void updateToZeroStockPersistsZero() throws Exception {

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("0")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stock").value(0));

		assertThat(ownProductAfterRequest().getStock())
				.as("stock 0 must be stored exactly as 0, not normalized")
				.isZero();
	}

	/** A positive stock still works on creation, with the value stored. */
	@Test
	void createWithPositiveStockPersistsTheValue() throws Exception {

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("7")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stock").value(7));

		Product stored = productRepository
				.findByCategory_Business_Id(BUSINESS_A_ID).stream()
				.filter(p -> "Stock Non Negative Nuevo".equals(p.getName()))
				.findFirst()
				.orElseThrow();
		assertThat(stored.getStock()).isEqualTo(7);
	}

	/** A positive stock still works on update, with the value stored. */
	@Test
	void updateWithPositiveStockPersistsTheValue() throws Exception {

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("12")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stock").value(12));

		assertThat(ownProductAfterRequest().getStock()).isEqualTo(12);
	}

	/**
	 * Null is a MEANINGFUL value ("stock infinito", e.g. bebidas): the public
	 * menu shows it (hasStock accepts IS NULL) and orders skip the decrement.
	 * Pinned here so the acceptance cannot drift silently.
	 */
	@Test
	void createWithNullStockStoresNullInfiniteStock() throws Exception {

		String body = """
				{
				  "categoryId": %d,
				  "name": "Stock Null Nuevo",
				  "description": "descripcion nueva",
				  "price": "50.00",
				  "cost": "10.00",
				  "stock": null,
				  "active": true
				}
				""".formatted(categoryA.getId());

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());

		Product stored = productRepository
				.findByCategory_Business_Id(BUSINESS_A_ID).stream()
				.filter(p -> "Stock Null Nuevo".equals(p.getName()))
				.findFirst()
				.orElseThrow();
		assertThat(stored.getStock())
				.as("a null stock (untracked/unlimited) must be stored as null")
				.isNull();
	}

	/** Null stays accepted on update too: it means unlimited, so storing null is correct. */
	@Test
	void updateToNullStockStoresNullInfiniteStock() throws Exception {

		String body = """
				{
				  "id": %d,
				  "name": "Nombre Valido",
				  "description": "descripcion original",
				  "price": "100.00",
				  "cost": "20.00",
				  "categoryId": %d,
				  "active": true,
				  "stock": null
				}
				""".formatted(product.getId(), categoryA.getId());

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());

		assertThat(ownProductAfterRequest().getStock())
				.as("an update to a null stock (untracked/unlimited) must be stored as null")
				.isNull();
	}

}
