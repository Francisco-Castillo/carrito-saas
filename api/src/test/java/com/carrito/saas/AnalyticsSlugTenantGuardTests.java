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

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * The slug-scoped analytics routes
 * ({@code /api/restaurants/{slug}/analytics/*}) resolved the business purely
 * from the path slug: any authenticated user could read another tenant's
 * sales data by passing a foreign slug — the same cross-tenant read the
 * trend routes had before their guard
 * (finding R3-authz-slug-scope, review-b8efbb575f4aa7ab). The slug only
 * names the business whose data is requested; the caller's JWT business
 * must match it, with the same 404 as an unknown slug so the route does
 * not reveal which slugs exist.
 */
@SpringBootTest
@Transactional
class AnalyticsSlugTenantGuardTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_702_501L;

	private static final String SLUG = "analytics-guard-slug";

	private static final Long FOREIGN_BUSINESS_ID = 987_702_502L;
	private static final String FOREIGN_SLUG = "analytics-guard-foreign-slug";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private BusinessUserRepository businessUserRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JwtUtil jwtUtil;

	private MockMvc mockMvc;

	private String foreignOwnerHeader;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, "analytics-guard-owner");

		BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, FOREIGN_BUSINESS_ID, FOREIGN_SLUG,
				"analytics-guard-foreign-owner");

		Business foreign = businessRepository.findById(FOREIGN_BUSINESS_ID).orElseThrow();
		foreignOwnerHeader = "Bearer " + jwtUtil.generateToken("analytics-guard-foreign-owner", FOREIGN_BUSINESS_ID);
	}

	@Test
	void foreignOwnerGets404OnEveryAnalyticsRoute() throws Exception {

		String[] routes = {
				"/api/restaurants/" + SLUG + "/analytics/top-products",
				"/api/restaurants/" + SLUG + "/analytics/sales-by-hour",
				"/api/restaurants/" + SLUG + "/analytics/sales-last-7-days",
				"/api/restaurants/" + SLUG + "/analytics/sales-previous-7-days",
		};

		for (String route : routes) {
			mockMvc.perform(get(route).header(HttpHeaders.AUTHORIZATION, foreignOwnerHeader))
					.andExpect(status().isNotFound());
		}
	}

	@Test
	void unknownSlugGetsTheSame404() throws Exception {

		String[] routes = {
				"/api/restaurants/no-such-slug/analytics/top-products",
				"/api/restaurants/no-such-slug/analytics/sales-by-hour",
				"/api/restaurants/no-such-slug/analytics/sales-last-7-days",
				"/api/restaurants/no-such-slug/analytics/sales-previous-7-days",
		};

		for (String route : routes) {
			mockMvc.perform(get(route).header(HttpHeaders.AUTHORIZATION, foreignOwnerHeader))
					.andExpect(status().isNotFound());
		}
	}

}
