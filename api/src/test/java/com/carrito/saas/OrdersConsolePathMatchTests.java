package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * Path-matching probes for the orders-console role gate (finding follow-ups
 * from the independent verification of T7): the exact matcher
 * {@code GET /api/business/orders} must not be defeated by a query string,
 * must NOT leak data through the trailing-slash variant (Spring 6+ removed
 * implicit slash matching, so {@code /orders/} reaches {@code anyRequest()}
 * as an unmatched route and must 404 — never 200 with the day view), and the
 * KDS feed must stay out of the gate's reach.
 *
 * <p>Same fixture and chain as {@link OrdersConsoleRoleGateTests}.</p>
 */
@SpringBootTest
@Transactional
class OrdersConsolePathMatchTests {

	private static final Long BUSINESS_ID = 987_713_201L;
	private static final String SLUG = "orders-pathmatch-pizzeria";

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

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	private BusinessAuthSeed.AuthenticatedBusiness seedUser(String username, String role) {
		return BusinessAuthSeed.seedUserWithRole(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, username, role);
	}

	@Test
	void aQueryStringCannotDefeatTheGate() throws Exception {
		BusinessAuthSeed.AuthenticatedBusiness kitchen = seedUser("pathmatch-kitchen", "KITCHEN");

		mockMvc.perform(get("/api/business/orders?date=2026-10-18")
						.header(HttpHeaders.AUTHORIZATION, kitchen.authorizationHeader()))
				.andExpect(status().isForbidden());
	}

	@Test
	void theTrailingSlashVariantLeaksNothing() throws Exception {
		BusinessAuthSeed.AuthenticatedBusiness kitchen = seedUser("pathmatch-kitchen-slash", "KITCHEN");

		// Not 200 under any circumstance: an unmatched route 404s (Spring 6+
		// removed implicit trailing-slash matching), it never falls through to
		// an ungated handler carrying the day view.
		mockMvc.perform(get("/api/business/orders/")
						.header(HttpHeaders.AUTHORIZATION, kitchen.authorizationHeader()))
				.andExpect(result -> org.assertj.core.api.Assertions
						.assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
	}

	@Test
	void theGateDoesNotSpillOntoTheKdsFeed() throws Exception {
		BusinessAuthSeed.AuthenticatedBusiness pedidos = seedUser("pathmatch-pedidos", "PEDIDOS");

		mockMvc.perform(get("/api/business/orders/active")
						.header(HttpHeaders.AUTHORIZATION, pedidos.authorizationHeader()))
				.andExpect(status().isOk());
	}
}
