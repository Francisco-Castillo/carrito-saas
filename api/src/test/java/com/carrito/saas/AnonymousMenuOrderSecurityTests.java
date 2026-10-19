package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;

import org.assertj.core.api.Assertions;
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
 * Security contract for the public QR menu order channel.
 *
 * <p>An anonymous customer (no Authorization header, no session) must be able
 * to POST an order to {@code /api/orders/menu/{slug}}. The previous rule only
 * permitted the exact path {@code POST /api/orders}, which does not match
 * {@code /api/orders/menu/{slug}}, so the browser request was rejected by the
 * security filter chain before reaching the controller.</p>
 *
 * <p>The boundary is asymmetric on purpose: only the public menu order
 * creation opens up. The kitchen-side order mutations
 * ({@code PATCH /api/orders/{id}/status} and
 * {@code PATCH /api/orders/{id}/cancel}) must stay authenticated.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy}: Spring Boot 4 moved {@code @AutoConfigureMockMvc}
 * into the separate {@code spring-boot-webmvc-test} module, which
 * {@code spring-boot-starter-test} does not bring, and adding dependencies is
 * out of scope. Registering the security chain as the single filter exercises
 * the exact production authorization rules. The probe seeds a real business
 * and product and asserts a 200: the endpoint itself answers an honest 404
 * for an unknown slug, so "reaches the real endpoint" can only be proven
 * against a valid one.</p>
 */
@SpringBootTest
@Transactional
class AnonymousMenuOrderSecurityTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_809_203L;

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

	@Autowired
	private com.carrito.saas.repository.jpa.BusinessHoursRepository businessHoursRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	@Test
	void anonymousCustomerCanPostOrderToPublicMenuEndpoint() throws Exception {

		// Seed a reachable business + product: the probe must prove the request
		// reaches the REAL endpoint (which now answers an honest 404 for an
		// unknown slug), so the anonymous POST must succeed against a real slug.
		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Security Probe Business");
		business.setSlug("security-probe-business");
		business = businessRepository.saveAndFlush(business);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, business.getId());

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Menu");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName("Security Probe Product");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("30.00"));
		product.setStock(10);
		product.setActive(true);
		product = productRepository.saveAndFlush(product);

		String body = """
				{
				  "customerName": "Cliente Anonimo",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(product.getId());

		mockMvc.perform(post("/api/orders/menu/security-probe-business")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(result -> {
					int status = result.getResponse().getStatus();
					Assertions.assertThat(status)
							.as("anonymous POST to /api/orders/menu/{slug} must not be rejected by security "
									+ "and must reach the real endpoint: a valid order succeeds (actual: %d)",
									status)
							.isEqualTo(200);
				});
	}

	@Test
	void orderStatusEndpointStaysAuthenticated() throws Exception {

		mockMvc.perform(patch("/api/orders/1/status").param("status", "PREPARING"))
				.andExpect(result -> Assertions.assertThat(result.getResponse().getStatus())
						.as("PATCH /api/orders/{id}/status must stay authenticated: an anonymous "
								+ "caller gets 401, the chain entry point's answer (actual: %d)",
								result.getResponse().getStatus())
						.isEqualTo(401));
	}

	@Test
	void orderCancelEndpointStaysAuthenticated() throws Exception {

		mockMvc.perform(patch("/api/orders/1/cancel")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(result -> Assertions.assertThat(result.getResponse().getStatus())
						.as("PATCH /api/orders/{id}/cancel must stay authenticated: an anonymous "
								+ "caller gets 401, the chain entry point's answer (actual: %d)",
								result.getResponse().getStatus())
						.isEqualTo(401));
	}

}
