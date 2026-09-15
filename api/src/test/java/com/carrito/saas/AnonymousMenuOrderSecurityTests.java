package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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
 * the exact production authorization rules.</p>
 */
@SpringBootTest
class AnonymousMenuOrderSecurityTests {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	@Test
	void anonymousCustomerCanPostOrderToPublicMenuEndpoint() throws Exception {

		String body = """
				{
				  "customerName": "Cliente Anonimo",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": 1, "quantity": 1 } ]
				}
				""";

		mockMvc.perform(post("/api/orders/menu/security-probe-business")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(result -> {
					int status = result.getResponse().getStatus();
					Assertions.assertThat(status)
							.as("anonymous POST to /api/orders/menu/{slug} must not be rejected by security "
									+ "and must be routed to the real endpoint, not 401/403/404 (actual: %d)",
									status)
							.isNotEqualTo(401)
							.isNotEqualTo(403)
							.isNotEqualTo(404);
				});
	}

	@Test
	void orderStatusEndpointStaysAuthenticated() throws Exception {

		mockMvc.perform(patch("/api/orders/1/status").param("status", "PREPARING"))
				.andExpect(result -> Assertions.assertThat(result.getResponse().getStatus())
						.as("PATCH /api/orders/{id}/status must stay authenticated")
						.isIn(401, 403));
	}

	@Test
	void orderCancelEndpointStaysAuthenticated() throws Exception {

		mockMvc.perform(patch("/api/orders/1/cancel")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(result -> Assertions.assertThat(result.getResponse().getStatus())
						.as("PATCH /api/orders/{id}/cancel must stay authenticated")
						.isIn(401, 403));
	}

}
