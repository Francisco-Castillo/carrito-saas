package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * GET /analytics/sales-last-7-days: the dashboard's weekly chart source. On a
 * database with no orders at all the route must still answer 200 with the
 * complete 7-day series (today + the 6 previous days), zero-filled — the chart
 * needs a stable X axis, so days without sales are 0, never missing entries.
 */
@SpringBootTest
@Transactional
class SalesLast7DaysEndpointTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_903_772L;

	private static final String SLUG = "sales-7-days";

	private static final String OWNER_USERNAME = "sales-7-days-owner";

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
	}

	@Test
	void returnsSevenZeroFilledDaysOnAnEmptyDatabase() throws Exception {
		seedOwner();

		mockMvc.perform(get("/api/restaurants/" + SLUG + "/analytics/sales-last-7-days")
				.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(7))
				.andExpect(jsonPath("$[0].day").isNotEmpty())
				.andExpect(jsonPath("$[0].revenue").value(0.0))
				.andExpect(jsonPath("$[6].revenue").value(0.0));
	}

	@Test
	void rejectsAnAnonymousCaller() throws Exception {
		seedOwner();

		mockMvc.perform(get("/api/restaurants/" + SLUG + "/analytics/sales-last-7-days"))
				.andExpect(status().isUnauthorized());
	}

	private void seedOwner() {
		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);
	}

}
