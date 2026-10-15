package com.carrito.saas;

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

/**
 * Status contract of the PUBLIC path (whatever an anonymous customer can
 * reach): client-caused failures in the slug lookups and the public order
 * flow must answer 4xx through {@code BusinessException} +
 * {@code ErrorMapper}, not the catch-all's 500
 * {@code "Ocurrió un error inesperado"}.
 *
 * <p>Message texts stay byte-identical to what the customer already saw —
 * only the status changes. The admin/owner service surface is a separate,
 * documented unit and is deliberately out of scope here.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy} (same reason as
 * {@link PublicMenuOrderHttpTests}: Spring Boot 4 moved
 * {@code @AutoConfigureMockMvc} out of {@code spring-boot-starter-test}).
 * {@code @Transactional} rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class PublicPathErrorStatusHttpTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_809_202L;

	private static final String SLUG = "public-path-error-status-test-business";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	private Business seedBusiness(String slug) {

		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Public Path Error Status Test Business");
		business.setSlug(slug);
		return businessRepository.saveAndFlush(business);
	}

	private Product seedProduct(Business business, String name, int stock) {

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Menu");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName(name);
		product.setPrice(new BigDecimal("250.50"));
		product.setCost(new BigDecimal("80.00"));
		product.setStock(stock);
		product.setActive(true);
		return productRepository.saveAndFlush(product);
	}

	// ==================================================================
	// Unknown slug on the three public GETs → 404 with the four-key body
	// ==================================================================

	@Test
	void unknownSlugOnRestaurantBySlugReturns404WithFourKeyBody() throws Exception {

		mockMvc.perform(get("/api/restaurants/slug/no-such-public-path-slug"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").value("Local no encontrado"))
				.andExpect(jsonPath("$.path").value("/api/restaurants/slug/no-such-public-path-slug"));
	}

	@Test
	void unknownSlugOnProductsBySlugReturns404WithFourKeyBody() throws Exception {

		mockMvc.perform(get("/api/restaurants/slug/no-such-public-path-slug/products"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").value("Local no encontrado"))
				.andExpect(jsonPath("$.path").value("/api/restaurants/slug/no-such-public-path-slug/products"));
	}

	@Test
	void unknownSlugOnPublicMenuReturns404WithFourKeyBody() throws Exception {

		mockMvc.perform(get("/api/menu/no-such-public-path-slug"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").value("Local no encontrado"))
				.andExpect(jsonPath("$.path").value("/api/menu/no-such-public-path-slug"));
	}

	// ==================================================================
	// Public order: validation failures → 400, message text intact
	// ==================================================================

	@Test
	void orderWithoutCustomerNameReturns400WithMessage() throws Exception {

		seedBusiness(SLUG);

		String body = """
				{
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": 1, "quantity": 1 } ]
				}
				""";

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("El nombre del cliente es obligatorio"));
	}

	@Test
	void orderWithEmptyItemsReturns400WithMessage() throws Exception {

		seedBusiness(SLUG);

		String body = """
				{
				  "customerName": "Cliente Publico",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": []
				}
				""";

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("El pedido debe contener al menos un producto"));
	}

	@Test
	void deliveryOrderWithoutAddressReturns400WithMessage() throws Exception {

		seedBusiness(SLUG);

		String body = """
				{
				  "customerName": "Cliente Publico",
				  "orderType": "DELIVERY",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": 1, "quantity": 1 } ]
				}
				""";

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Debe indicar dirección para delivery"));
	}

	// ==================================================================
	// Public order: unknown slug / unknown product → 404
	// ==================================================================

	@Test
	void orderWithUnknownSlugReturns404WithMessage() throws Exception {

		String body = """
				{
				  "customerName": "Cliente Publico",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": 1, "quantity": 1 } ]
				}
				""";

		mockMvc.perform(post("/api/orders/menu/no-such-public-path-slug")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("No se encontró restaurante"));
	}

	@Test
	void orderWithUnknownProductReturns404WithMessage() throws Exception {

		Business business = seedBusiness(SLUG);
		seedProduct(business, "Producto Existente", 10);

		String body = """
				{
				  "customerName": "Cliente Publico",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": 999999999, "quantity": 1 } ]
				}
				""";

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Producto no existe"));
	}

	// ==================================================================
	// Public order: stock exhausted → 409
	// ==================================================================

	@Test
	void orderExceedingStockReturns409WithMessage() throws Exception {

		Business business = seedBusiness(SLUG);
		Product product = seedProduct(business, "Producto Con Poco Stock", 1);

		String body = """
				{
				  "customerName": "Cliente Publico",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 5 } ]
				}
				""".formatted(product.getId());

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Stock insuficiente: Producto Con Poco Stock"));
	}

	// ==================================================================
	// Regression: a valid order still succeeds as before
	// ==================================================================

	@Test
	void validOrderStillReturnsSuccessWithNewStatus() throws Exception {

		Business business = seedBusiness(SLUG);
		Product product = seedProduct(business, "Producto Valido", 10);

		String body = """
				{
				  "customerName": "Cliente Publico",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 2 } ]
				}
				""".formatted(product.getId());

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").isNotEmpty())
				.andExpect(jsonPath("$.status").value("NEW"));
	}

}
