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
 * Role gate of the orders console: {@code GET /api/business/orders} is the
 * management day view — the one screen the KDS does not use — so it demands
 * {@code PEDIDOS}, {@code OWNER} or {@code ADMIN}. The KDS contracts are
 * untouched: {@code GET /api/business/orders/active} stays reachable by any
 * authenticated business user (KITCHEN included), because gating it belongs
 * to the still-open kitchen-authorization slice, not to this one.
 *
 * <p>MockMvc with the real security filter chain and real JWTs (pattern of
 * {@link OrderManagementDayEndpointTests}); {@code @Transactional} rolls
 * every fixture back at test end.</p>
 */
@SpringBootTest
@Transactional
class OrdersConsoleRoleGateTests {

	private static final Long BUSINESS_ID = 987_713_101L;
	private static final String SLUG = "orders-gate-pizzeria";

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

	// ------------------------------------------------------------------
	// S5: the day view demands PEDIDOS / OWNER / ADMIN
	// ------------------------------------------------------------------

	@Test
	void kitchenRoleIsRejectedOnTheManagementDayView() throws Exception {
		BusinessAuthSeed.AuthenticatedBusiness kitchen = seedUser("orders-gate-kitchen", "KITCHEN");

		mockMvc.perform(get("/api/business/orders")
						.header(HttpHeaders.AUTHORIZATION, kitchen.authorizationHeader()))
				.andExpect(status().isForbidden());
	}

	@Test
	void pedidosRoleOpensTheManagementDayView() throws Exception {
		BusinessAuthSeed.AuthenticatedBusiness pedidos = seedUser("orders-gate-pedidos", "PEDIDOS");

		mockMvc.perform(get("/api/business/orders")
						.header(HttpHeaders.AUTHORIZATION, pedidos.authorizationHeader()))
				.andExpect(status().isOk());
	}

	// ------------------------------------------------------------------
	// Preservation: the KDS contracts stay open (kitchen can still work)
	// ------------------------------------------------------------------

	@Test
	void kitchenRoleKeepsTheActiveOrdersFeed() throws Exception {
		BusinessAuthSeed.AuthenticatedBusiness kitchen = seedUser("orders-gate-kitchen-kds", "KITCHEN");

		mockMvc.perform(get("/api/business/orders/active")
						.header(HttpHeaders.AUTHORIZATION, kitchen.authorizationHeader()))
				.andExpect(status().isOk());
	}
}
