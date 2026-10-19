package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Anonymous access contract for the business-only surface under
 * {@code /api/restaurants/**}.
 *
 * <p>The restaurant slug is public — it is encoded in the printed QR — so
 * every business-only route (dashboard KPIs, analytics, user management)
 * must demand the JWT: an anonymous caller reads another business's
 * revenue, order counts and prep times today because the broad
 * {@code /api/restaurants/**} permit lets the requests reach the controller.
 * Only {@code /api/restaurants/slug/**} stays anonymous, because the public
 * menu and the login screen need the slug lookup.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy} (same as {@code AdminProductAndComboEndpointTests};
 * Spring Boot 4 moved {@code @AutoConfigureMockMvc} out of
 * {@code spring-boot-starter-test}). {@code @Transactional} rolls back every
 * fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class DashboardAnonymousAccessTests {

	private static final Long BUSINESS_ID = 987_702_101L;
	private static final String SLUG = "dashboard-secret-pizzeria";
	private static final String OWNER_USERNAME = "dashboard-owner";

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

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);
	}

	/**
	 * Without a token the five business-only routes must be rejected by the
	 * filter chain with 401 — an anonymous caller gets the chain entry point's
	 * 401, never the controller. Before the narrowing they answered 200 (the
	 * four GETs leak the KPIs) or 500 (the POST reaches the controller and
	 * fails there): that is the leak this contract closes.
	 */
	@Test
	void anonymousCallerIsRejectedOnEveryBusinessRoute() throws Exception {

		mockMvc.perform(get("/api/restaurants/" + SLUG + "/dashboard/today"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/restaurants/" + SLUG + "/orders/status-summary"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/restaurants/" + SLUG + "/analytics/top-products"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/restaurants/" + SLUG + "/analytics/sales-by-hour"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/restaurants/" + SLUG + "/users"))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * With the owner's token the four dashboard/analytics GETs must keep
	 * answering 200: the fix narrows the public surface, it must not break
	 * the authenticated dashboard.
	 */
	@Test
	void ownerTokenStillGets200OnTheDashboardGets() throws Exception {

		String auth = owner.authorizationHeader();

		mockMvc.perform(get("/api/restaurants/" + SLUG + "/dashboard/today")
						.header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/restaurants/" + SLUG + "/orders/status-summary")
						.header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/restaurants/" + SLUG + "/analytics/top-products")
						.header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/restaurants/" + SLUG + "/analytics/sales-by-hour")
						.header(HttpHeaders.AUTHORIZATION, auth))
				.andExpect(status().isOk());
	}

	/**
	 * The public flows keep working anonymously: the slug lookup used by the
	 * public menu and the login screen, and the public menu itself, must stay
	 * reachable without a token.
	 */
	@Test
	void publicSlugLookupAndMenuStayAnonymous() throws Exception {

		mockMvc.perform(get("/api/restaurants/slug/" + SLUG))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/menu/" + SLUG))
				.andExpect(status().isOk());
	}

}
