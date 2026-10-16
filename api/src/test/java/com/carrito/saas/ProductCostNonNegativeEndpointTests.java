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
 * Cost is not price: a product can legitimately have NO cost recorded, so
 * cost 0 stays valid on both write paths — the only rule is that a NEGATIVE
 * cost is invalid, reusing create's exact rule and message ("Costo inválido")
 * for creation and update alike.
 *
 * <p>This is the same sibling-paths asymmetry the price defect had, one field
 * over: {@code crearProducto} already rejected a negative cost via
 * {@code validarProducto}, but {@code actualizarProducto} only validated the
 * cost for null. A rejected payload must not mutate anything: the stored row
 * (name, price and cost) and the catalogue count stay exactly as they were.</p>
 */
@SpringBootTest
@Transactional
class ProductCostNonNegativeEndpointTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_804_101L;

	private static final String SLUG_A = "product-cost-non-negative-a";

	private static final String OWNER_USERNAME = "product-cost-non-negative-owner";

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

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Cost Non Negative Cat A"));

		product = new Product();
		product.setCategory(categoryA);
		product.setName("Cost Non Negative Original");
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

	private String createBody(String costJson) {
		return """
				{
				  "categoryId": %d,
				  "name": "Cost Non Negative Nuevo",
				  "description": "descripcion nueva",
				  "price": "150.00",
				  "cost": %s,
				  "stock": 3,
				  "active": true
				}
				""".formatted(categoryA.getId(), costJson);
	}

	private String updateBody(String nameJson, String costJson) {
		return """
				{
				  "id": %d,
				  "name": %s,
				  "description": "descripcion original",
				  "price": "100.00",
				  "cost": %s,
				  "categoryId": %d,
				  "active": true,
				  "stock": 5
				}
				""".formatted(product.getId(), nameJson, costJson, categoryA.getId());
	}

	/**
	 * Plain defect: editing a product to a NEGATIVE cost went through because
	 * update only validated the cost for null. It must be a 400 reusing
	 * create's message and change nothing.
	 */
	@Test
	void updateToNegativeCostReturns400AndLeavesRowUnchanged() throws Exception {

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"Nombre Valido\"", "\"-5.00\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Costo inválido"));

		Product after = ownProductAfterRequest();
		assertThat(after.getName())
				.as("a rejected negative-cost update must not rename the product")
				.isEqualTo("Cost Non Negative Original");
		assertThat(after.getPrice())
				.as("a rejected negative-cost update must not touch the stored price")
				.isEqualByComparingTo("100.00");
		assertThat(after.getCost())
				.as("a rejected negative-cost update must not touch the stored cost")
				.isEqualByComparingTo("20.00");
	}

	/** A negative cost is already rejected on creation: 400, nothing stored. */
	@Test
	void createWithNegativeCostReturns400AndLeavesCatalogueUnchanged() throws Exception {

		long before = catalogueCount();

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("\"-5.00\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Costo inválido"));

		assertThat(catalogueCount())
				.as("a rejected negative-cost creation must not store any product")
				.isEqualTo(before);
	}

	/**
	 * Cost is not price: 0 is a legitimate "no cost recorded" value on
	 * creation and must stay accepted, stored exactly as zero.
	 */
	@Test
	void createWithZeroCostIsAcceptedAndStoresZero() throws Exception {

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("\"0\"")))
				.andExpect(status().isOk());

		Product stored = productRepository
				.findByCategory_Business_Id(BUSINESS_A_ID).stream()
				.filter(p -> "Cost Non Negative Nuevo".equals(p.getName()))
				.findFirst()
				.orElseThrow();
		assertThat(stored.getCost())
				.as("cost 0 must stay accepted on creation and be stored as 0")
				.isEqualByComparingTo("0");
	}

	/**
	 * Cost 0 must also stay accepted on update: it is the same "no cost
	 * recorded" value, stored exactly as zero.
	 */
	@Test
	void updateToZeroCostIsAcceptedAndStoresZero() throws Exception {

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"Nombre Con Costo Cero\"", "\"0\"")))
				.andExpect(status().isOk());

		Product after = ownProductAfterRequest();
		assertThat(after.getName()).isEqualTo("Nombre Con Costo Cero");
		assertThat(after.getCost())
				.as("cost 0 must stay accepted on update and be stored as 0")
				.isEqualByComparingTo("0");
	}

	/** A positive cost still works on creation, with the value stored. */
	@Test
	void createWithPositiveCostPersistsTheValue() throws Exception {

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("\"30.00\"")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cost").value(30.00));

		Product stored = productRepository
				.findByCategory_Business_Id(BUSINESS_A_ID).stream()
				.filter(p -> "Cost Non Negative Nuevo".equals(p.getName()))
				.findFirst()
				.orElseThrow();
		assertThat(stored.getCost()).isEqualByComparingTo("30.00");
	}

	/** A positive cost still works on update, with the value stored. */
	@Test
	void updateWithPositiveCostPersistsTheValue() throws Exception {

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"Nombre Con Costo Nuevo\"", "\"25.50\"")))
				.andExpect(status().isOk());

		assertThat(ownProductAfterRequest().getCost()).isEqualByComparingTo("25.50");
	}

}
