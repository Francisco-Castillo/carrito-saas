package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessUser;
import com.carrito.saas.repository.entity.Role;
import com.carrito.saas.repository.entity.User;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * Honest contract of {@code PredictionController}: every mapping it advertises
 * must actually answer its data. The stubs {@code GET /products} and
 * {@code GET /admin/hourly?businessId=} answered {@code 200} with a {@code null}
 * body — a false success worse than a missing endpoint, because the client
 * cannot tell an empty result from an unimplemented one. They are removed until
 * the backend has the repository support ({@code /products}) and the admin
 * authorization design ({@code /admin/hourly}) they would need.
 *
 * <p>Same HTTP-level style as {@code TableEndpointsHttpTests}: manual MockMvc
 * with the real filter chain, {@code ownerToken()} auth, {@code @Transactional}
 * rollback of every fixture.</p>
 */
@SpringBootTest
@Transactional
class PredictionEndpointHonestyHttpTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_820_101L;

	private static final String SLUG = "prediction-honesty-test-business";

	private static final String USERNAME = "prediction-honesty-owner";

	private static final String PASSWORD = "prediction-honesty-password";

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
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtUtil jwtUtil;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Business " + SLUG);
		business.setSlug(SLUG);
		businessRepository.saveAndFlush(business);

		Role role = new Role();
		role.setName("OWNER");
		role = roleRepository.saveAndFlush(role);

		User user = new User();
		user.setUsername(USERNAME);
		user.setPassword(passwordEncoder.encode(PASSWORD));
		user.setRoles(java.util.Set.of(role));
		userRepository.saveAndFlush(user);

		BusinessUser businessUser = new BusinessUser();
		businessUser.setUser(user);
		businessUser.setBusiness(business);
		businessUser.setRole(role);
		businessUserRepository.saveAndFlush(businessUser);
	}

	private String ownerToken() {
		return "Bearer " + jwtUtil.generateToken(USERNAME, BUSINESS_ID);
	}

	// ==================================================================
	// Removed mappings: an honest client error, never 200 + null
	// ==================================================================

	@Test
	void removedProductsEndpointReturns404Not200WithNullBody() throws Exception {

		// Authenticated on purpose: the 404 must say "path does not exist",
		// not hide behind the 401 an anonymous caller would get first.
		mockMvc.perform(get("/api/predictions/products")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound());
	}

	@Test
	void removedAdminHourlyEndpointReturns404Not200WithNullBody() throws Exception {

		mockMvc.perform(get("/api/predictions/admin/hourly?businessId=" + BUSINESS_ID)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound());
	}

	/**
	 * The API must not advertise the removed paths at all: another verb on the
	 * same URL is also 404 (no handler), not a 405 pointing at a living
	 * resource.
	 */
	@Test
	void removedPathsAreGoneForOtherVerbsToo() throws Exception {

		mockMvc.perform(post("/api/predictions/products")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNotFound());

		mockMvc.perform(post("/api/predictions/admin/hourly?businessId=" + BUSINESS_ID)
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNotFound());
	}

	// ==================================================================
	// Regression: the implemented endpoints keep answering their data
	// ==================================================================

	@Test
	void todayStillAnswersItsPredictionWith200() throws Exception {

		// No historical orders for the seeded business, so the documented
		// defaults apply: 0 predicted, peak hour 20, demand UNKNOWN.
		mockMvc.perform(get("/api/predictions/today")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.predictedOrders").value(0))
				.andExpect(jsonPath("$.peakHour").value(20))
				.andExpect(jsonPath("$.demandLevel").value("UNKNOWN"));
	}

	@Test
	void hourlyStillAnswersItsDistributionWith200() throws Exception {

		// A business without orders has an empty distribution, but the
		// endpoint must answer a real JSON array, not null.
		mockMvc.perform(get("/api/predictions/hourly")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray());
	}

}
