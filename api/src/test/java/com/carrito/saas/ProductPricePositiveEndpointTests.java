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
 * A product is never free: the same rule as a combo (see
 * {@code ComboServiceImpl.validarForma}) applied to both write paths.
 *
 * <p>Price is a monetary value, so 0 and negatives are shape problems — 400
 * VALIDATION naming the rule ("El precio del producto debe ser mayor que
 * cero") — and the rule lives ONCE, shared by creation and update. A rejected
 * payload must not mutate anything: the catalogue count and the stored row
 * stay exactly as they were.</p>
 *
 * <p>Partial semantics: a PUT with the price ABSENT is not a price of zero —
 * it is a missing required field and answers 400 "El precio es obligatorio"
 * (the existing contract in {@code ProductUpdateValidationTests}) leaving the
 * stored price untouched. Only a PRESENT price is judged by the &gt; 0 rule.</p>
 */
@SpringBootTest
@Transactional
class ProductPricePositiveEndpointTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_803_101L;

	private static final String SLUG_A = "product-price-positive-a";

	private static final String OWNER_USERNAME = "product-price-positive-owner";

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

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Price Positive Cat A"));

		product = new Product();
		product.setCategory(categoryA);
		product.setName("Price Positive Original");
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

	private String createBody(String priceJson) {
		return """
				{
				  "categoryId": %d,
				  "name": "Price Positive Nuevo",
				  "description": "descripcion nueva",
				  "price": %s,
				  "cost": "30.00",
				  "stock": 3,
				  "active": true
				}
				""".formatted(categoryA.getId(), priceJson);
	}

	private String updateBody(String nameJson, String priceJson) {
		return """
				{
				  "id": %d,
				  "name": %s,
				  "description": "descripcion original",
				  "price": %s,
				  "cost": "20.00",
				  "categoryId": %d,
				  "active": true,
				  "stock": 5
				}
				""".formatted(product.getId(), nameJson, priceJson, categoryA.getId());
	}

	/** A free product is not a product: price 0 is a 400 and nothing is stored. */
	@Test
	void createWithZeroPriceReturns400AndLeavesCatalogueUnchanged() throws Exception {

		long before = catalogueCount();

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("\"0\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El precio del producto debe ser mayor que cero"));

		assertThat(catalogueCount())
				.as("a rejected zero-price creation must not store any product")
				.isEqualTo(before);
	}

	/** A negative price is a shape problem on creation too: 400, nothing stored. */
	@Test
	void createWithNegativePriceReturns400AndLeavesCatalogueUnchanged() throws Exception {

		long before = catalogueCount();

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("\"-5.00\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El precio del producto debe ser mayor que cero"));

		assertThat(catalogueCount())
				.as("a rejected negative-price creation must not store any product")
				.isEqualTo(before);
	}

	/** A positive price still works on creation, with the value stored. */
	@Test
	void createWithPositivePricePersistsTheValue() throws Exception {

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("\"150.00\"")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.price").value(150.00));

		Product stored = productRepository
				.findByCategory_Business_Id(BUSINESS_A_ID).stream()
				.filter(p -> "Price Positive Nuevo".equals(p.getName()))
				.findFirst()
				.orElseThrow();
		assertThat(stored.getPrice()).isEqualByComparingTo("150.00");
	}

	/** Editing to a free product is not legitimate: price 0 is a 400, row intact. */
	@Test
	void updateToZeroPriceReturns400AndLeavesRowUnchanged() throws Exception {

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"Nombre Valido\"", "\"0\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El precio del producto debe ser mayor que cero"));

		Product after = ownProductAfterRequest();
		assertThat(after.getName())
				.as("a rejected zero-price update must not rename the product")
				.isEqualTo("Price Positive Original");
		assertThat(after.getPrice())
				.as("a rejected zero-price update must not touch the stored price")
				.isEqualByComparingTo("100.00");
		assertThat(after.getDescription())
				.as("a rejected zero-price update must not touch the stored description")
				.isEqualTo("descripcion original");
	}

	/**
	 * Plain defect independent of the zero decision: editing a product to a
	 * NEGATIVE price is never legitimate and went through. It must be a 400
	 * and change nothing.
	 */
	@Test
	void updateToNegativePriceReturns400AndLeavesRowUnchanged() throws Exception {

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"Nombre Valido\"", "\"-10.00\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El precio del producto debe ser mayor que cero"));

		Product after = ownProductAfterRequest();
		assertThat(after.getName())
				.as("a rejected negative-price update must not rename the product")
				.isEqualTo("Price Positive Original");
		assertThat(after.getPrice())
				.as("a rejected negative-price update must not touch the stored price")
				.isEqualByComparingTo("100.00");
		assertThat(after.getDescription())
				.as("a rejected negative-price update must not touch the stored description")
				.isEqualTo("descripcion original");
	}

	/** A positive price still works on update, with the value stored. */
	@Test
	void updateWithPositivePricePersistsTheValue() throws Exception {

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"Nombre Actualizado\"", "\"175.50\"")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.price").value(175.50));

		assertThat(ownProductAfterRequest().getPrice()).isEqualByComparingTo("175.50");
	}

	/**
	 * An ABSENT price is not a zero price: it is a missing required field, so
	 * it answers the existing 400 "El precio es obligatorio" contract and the
	 * stored price keeps exactly its current value.
	 */
	@Test
	void updateWithAbsentPriceKeepsTheStoredPrice() throws Exception {

		mockMvc.perform(put("/api/products/" + product.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("\"Nombre Valido\"", "null")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El precio es obligatorio"));

		assertThat(ownProductAfterRequest().getPrice())
				.as("an update without a price must keep the current stored price")
				.isEqualByComparingTo("100.00");
	}

}
