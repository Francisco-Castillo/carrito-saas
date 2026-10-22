package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import com.carrito.saas.service.interfaces.IGeneratePredictionsForAllBusinessesUseCase;

/**
 * Privilege contract of {@code POST /api/predictions/run}.
 *
 * <p>The endpoint triggers
 * {@link IGeneratePredictionsForAllBusinessesUseCase#execute()} — a GLOBAL job
 * that recomputes predictions for every business. Before the guard it sat under
 * {@code anyRequest().authenticated()}, so any logged-in user of any business
 * (e.g. a KITCHEN role) could force a full recalculation of the whole platform.</p>
 *
 * <p>Required behaviour: an authenticated caller without a privileged role
 * (KITCHEN) → 403, and OWNER reaches the use case (200). ADMIN does NOT:
 * since the role simplification (odd/tasks/simplificacion-roles.md, S1/S2)
 * ADMIN is the platform-exclusive role and no longer a business one, so the
 * rule is OWNER-only. An anonymous caller gets 401: the chain's entry point
 * answers 401 to every anonymous caller on a protected route — the
 * 401-versus-403 question recorded in
 * {@code odd/tasks/http-error-contract.md} was resolved in favour of the
 * global 401, so "no session" (401) and "no privilege" (403) stay distinct.</p>
 *
 * <p>The rule is a privilege barrier, NOT a tenant one: the job is global, so
 * an OWNER of business A still triggers a recalculation for every business.</p>
 *
 * <p>Same HTTP-level style as {@code PredictionEndpointHonestyHttpTests}: manual
 * MockMvc with the real security {@link FilterChainProxy}, real users with their
 * roles seeded and rolled back, and real JWTs. The use case itself is a
 * {@code @MockitoBean}: the security question is "who may reach it", not "what
 * does it compute", and verifying the mock proves the request crossed the
 * filter chain into the controller without running the global job.</p>
 */
@SpringBootTest
@Transactional
class PredictionRunPrivilegeHttpTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long OWNER_BUSINESS_ID = 987_901_001L;

	private static final Long KITCHEN_BUSINESS_ID = 987_901_002L;

	private static final Long ADMIN_BUSINESS_ID = 987_901_003L;

	private static final Long PUBLIC_BUSINESS_ID = 987_901_004L;

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

	@MockitoBean
	private IGeneratePredictionsForAllBusinessesUseCase generateForAllBusinessesUseCase;

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	// ==================================================================
	// The privilege barrier
	// ==================================================================

	@Test
	@DisplayName("anonymous caller of POST /api/predictions/run gets 401, like every protected route")
	void anonymousCallerOfRunIsUnauthorized() throws Exception {

		// 401 and not 403: an anonymous caller has no session, and the chain's
		// entry point answers 401 to anonymous callers on every protected route
		// (resolved contract, see odd/tasks/http-error-contract.md). 403 is the
		// answer for an AUTHENTICATED caller without the role (next test). What
		// matters here is the job provably not running.
		mockMvc.perform(post("/api/predictions/run"))
				.andExpect(status().isUnauthorized());

		Mockito.verifyNoInteractions(generateForAllBusinessesUseCase);
	}

	@Test
	@DisplayName("authenticated KITCHEN caller of POST /api/predictions/run gets 403")
	void kitchenCallerOfRunIsForbidden() throws Exception {

		String token = seedUserWithRole("kitchen-run-guard", "KITCHEN", KITCHEN_BUSINESS_ID,
				"kitchen-run-guard-business");

		mockMvc.perform(post("/api/predictions/run")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isForbidden());

		Mockito.verifyNoInteractions(generateForAllBusinessesUseCase);
	}

	@Test
	@DisplayName("authenticated OWNER caller reaches the global run use case (200)")
	void ownerCallerReachesRun() throws Exception {

		String token = seedUserWithRole("owner-run-guard", "OWNER", OWNER_BUSINESS_ID,
				"owner-run-guard-business");

		mockMvc.perform(post("/api/predictions/run")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		Mockito.verify(generateForAllBusinessesUseCase).execute();
	}

	@Test
	@DisplayName("authenticated ADMIN authority does NOT reach the global run use case (403): ADMIN is platform-only")
	void adminAuthorityIsForbiddenOnRun() throws Exception {

		// S1/S2 de simplificacion-roles.md: ADMIN dejó de ser un rol de
		// negocio (heredado por OWNER) y es el rol exclusivo de la plataforma.
		// Un usuario con autoridad ROLE_ADMIN ya no cruza esta barrera.
		String token = seedUserWithRole("admin-run-guard", "ADMIN", ADMIN_BUSINESS_ID,
				"admin-run-guard-business");

		mockMvc.perform(post("/api/predictions/run")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isForbidden());

		Mockito.verifyNoInteractions(generateForAllBusinessesUseCase);
	}

	// ==================================================================
	// Regression: the rest of the chain keeps its existing shape
	// ==================================================================

	@Test
	@DisplayName("regression: the public menu path stays anonymous-permitted")
	void publicMenuPathStaysPermittedForAnonymous() throws Exception {

		seedBusinessOnly(PUBLIC_BUSINESS_ID, "run-guard-public-menu-business");

		// The public menu endpoint answers its own data for an existing slug;
		// what must NOT happen is the security chain rejecting it (401/403).
		mockMvc.perform(get("/api/menu/run-guard-public-menu-business"))
				.andExpect(result -> org.assertj.core.api.Assertions
						.assertThat(result.getResponse().getStatus())
						.as("GET /api/menu/{slug} must stay public: not 401 and not 403 (actual: %d)",
								result.getResponse().getStatus())
						.isNotIn(401, 403));
	}

	@Test
	@DisplayName("regression: authenticated-only prediction reads keep answering their owner")
	void authenticatedPredictionReadStillAnswersOwner() throws Exception {

		String token = seedUserWithRole("owner-run-guard-read", "OWNER", OWNER_BUSINESS_ID,
				"owner-run-guard-read-business");

		// GET /api/predictions/today sits under anyRequest().authenticated();
		// the new role rule on POST /run must not change that.
		mockMvc.perform(get("/api/predictions/today")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	private void seedBusinessOnly(Long businessId, String slug) {

		Business business = new Business();
		business.setId(businessId);
		business.setName("Business " + slug);
		business.setSlug(slug);
		businessRepository.saveAndFlush(business);
	}

	/**
	 * Seeds a business, a user with exactly the given role name, the
	 * user-business link, and mints a real JWT for the Authorization header.
	 *
	 * @return raw token WITHOUT the "Bearer " prefix
	 */
	private String seedUserWithRole(String username, String roleName, Long businessId, String slug) {

		Business business = new Business();
		business.setId(businessId);
		business.setName("Business " + slug);
		business.setSlug(slug);
		businessRepository.saveAndFlush(business);

		Role role = roleRepository.findByName(roleName)
				.orElseGet(() -> {
					Role created = new Role();
					created.setName(roleName);
					return roleRepository.saveAndFlush(created);
				});
		User user = new User();
		user.setUsername(username);
		user.setPassword("test-password");
		user.setRoles(java.util.Set.of(role));
		userRepository.saveAndFlush(user);

		BusinessUser businessUser = new BusinessUser();
		businessUser.setUser(user);
		businessUser.setBusiness(business);
		businessUser.setRole(role);
		businessUserRepository.saveAndFlush(businessUser);

		return jwtUtil.generateToken(username, businessId);
	}

}
