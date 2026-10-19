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
 * The analytics read routes used to answer 500 with
 * {@code relation "..." does not exist} because the JDBC tables had no owner.
 * With {@code schema.sql} provisioning them on startup, the routes must answer
 * 200 with an empty result on a database whose analytics tables exist but
 * hold no rows — the normal state of any freshly created database.
 */
@SpringBootTest
@Transactional
class AnalyticsRoutesOnEmptyAnalyticsSchemaTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_903_771L;

	private static final String SLUG = "analytics-empty-schema";

	private static final String OWNER_USERNAME = "analytics-empty-schema-owner";

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
	void topUpReturnsAnEmptyListInsteadOf500() throws Exception {
		seedOwner();

		mockMvc.perform(get("/api/trends/top-up").header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void topDownReturnsAnEmptyListInsteadOf500() throws Exception {
		seedOwner();

		mockMvc.perform(get("/api/trends/top-down").header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void averageOrdersReturnsANeutralZeroInsteadOf500() throws Exception {
		seedOwner();

		mockMvc.perform(get("/api/restaurants/dashboard/average-orders")
				.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.averageOrders").value(0.0));
	}

	private void seedOwner() {
		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);
	}

}
