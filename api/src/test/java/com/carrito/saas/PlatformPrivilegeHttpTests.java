package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Role;
import com.carrito.saas.repository.entity.User;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * Privilege contract of the platform operator surface: the login branch for
 * {@code PLATFORM_ADMIN} and the security chain over {@code /api/platform/**}.
 *
 * <p>The operator panel registers clients and their locales. That surface is
 * platform-wide: it must be unreachable for every business-scoped role
 * (OWNER, KITCHEN, ADMIN, PEDIDOS) and answer only to the new platform role
 * {@code PLATFORM_ADMIN}. Before this rule the paths fell under
 * {@code anyRequest().authenticated()}, which would have let any business
 * owner enumerate every client of the SaaS.</p>
 *
 * <p>The operator is a platform-level user with NO business membership, so
 * the login must mint a token WITHOUT the {@code businessId} claim — and the
 * JWT filter must authenticate such a token instead of discarding it (it used
 * to throw "JWT sin businessId", silently falling back to anonymous). The
 * membership-less 403 of the business login must stay for anyone without
 * {@code PLATFORM_ADMIN}.</p>
 *
 * <p>Same HTTP-level style as {@code PredictionRunPrivilegeHttpTests}: manual
 * MockMvc with the real security {@link FilterChainProxy}, real users with
 * their roles seeded and rolled back, and real JWTs. The security question is
 * "who may reach the surface", not what the endpoints return — a PLATFORM_ADMIN
 * caller is only asserted to CROSS the chain (no 401/403), because the
 * controller may still answer 404 until the endpoint lands in its own task.</p>
 *
 * <p>See odd/tasks/plataforma-altas.md (S3).</p>
 */
@SpringBootTest
@Transactional
class PlatformPrivilegeHttpTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long OWNER_BUSINESS_ID = 987_902_001L;

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

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	@Test
	@DisplayName("anonymous caller of GET /api/platform/clients gets 401")
	void anonymousCallerIsUnauthorized() throws Exception {

		// 401 and not 403: an anonymous caller has no session, and the chain's
		// entry point answers 401 to anonymous callers on every protected route
		// (resolved contract, see odd/tasks/http-error-contract.md).
		mockMvc.perform(get("/api/platform/clients"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("authenticated OWNER caller of the platform API gets 403")
	void ownerCallerIsForbidden() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness owner = BusinessAuthSeed.seedUserWithRole(
				businessRepository, userRepository, roleRepository, businessUserRepository, jwtUtil,
				OWNER_BUSINESS_ID, "owner-platform-guard-business", "owner-platform-guard", "OWNER");

		mockMvc.perform(get("/api/platform/clients")
						.header("Authorization", owner.authorizationHeader()))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("authenticated KITCHEN caller of the platform API gets 403")
	void kitchenCallerIsForbidden() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness kitchen = BusinessAuthSeed.seedUserWithRole(
				businessRepository, userRepository, roleRepository, businessUserRepository, jwtUtil,
				OWNER_BUSINESS_ID, "kitchen-platform-guard-business", "kitchen-platform-guard", "KITCHEN");

		mockMvc.perform(get("/api/platform/clients")
						.header("Authorization", kitchen.authorizationHeader()))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("PLATFORM_ADMIN login answers a platform token (no businessId) and crosses the chain")
	void platformAdminLoginYieldsPlatformTokenThatCrossesTheChain() throws Exception {

		seedPlatformAdminUser("platform-admin-login");

		String body = """
				{"username": "platform-admin-login", "password": "test-password"}
				""";

		String[] token = new String[1];

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("PLATFORM_ADMIN"))
				.andExpect(jsonPath("$.businessId").doesNotExist())
				.andDo(result -> token[0] = com.jayway.jsonpath.JsonPath
						.read(result.getResponse().getContentAsString(), "$.token"));

		// The endpoint itself may still be missing (404) until its own task
		// lands; what this test owns is the SECURITY contract: the token the
		// login minted must cross the chain — not rejected with 401 (a null
		// businessId used to throw "JWT sin businessId" and fall back to
		// anonymous) and not 403 (no privilege).
		mockMvc.perform(get("/api/platform/clients")
						.header("Authorization", "Bearer " + token[0]))
				.andExpect(result -> org.assertj.core.api.Assertions
						.assertThat(result.getResponse().getStatus())
						.as("PLATFORM_ADMIN token must cross the chain: not 401 and not 403 (actual: %d)",
								result.getResponse().getStatus())
						.isNotIn(401, 403));
	}

	@Test
	@DisplayName("user without business membership and without PLATFORM_ADMIN keeps getting 403 at login")
	void loginWithoutMembershipAndWithoutPlatformRoleStaysForbidden() throws Exception {

		seedUserWithSingleBusinessRole("roleless-no-business");

		String body = """
				{"username": "roleless-no-business", "password": "test-password"}
				""";

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isForbidden());
	}

	// ==================================================================
	// Regression: the rest of the chain keeps its existing shape
	// ==================================================================

	@Test
	@DisplayName("regression: the public menu path stays anonymous-permitted")
	void publicMenuPathStaysPermittedForAnonymous() throws Exception {

		BusinessAuthSeed.seedBusiness(businessRepository, 987_902_003L,
				"platform-guard-public-menu-business");

		// The public menu endpoint answers its own data for an existing slug;
		// what must NOT happen is the security chain rejecting it (401/403).
		mockMvc.perform(get("/api/menu/platform-guard-public-menu-business"))
				.andExpect(result -> org.assertj.core.api.Assertions
						.assertThat(result.getResponse().getStatus())
						.as("GET /api/menu/{slug} must stay public: not 401 and not 403 (actual: %d)",
								result.getResponse().getStatus())
						.isNotIn(401, 403));
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	/**
	 * Seeds a platform-level user: the PLATFORM_ADMIN authority through
	 * user_roles and NO business membership — mirroring what the seed does
	 * for the operator (docs/dev-seed.sql).
	 */
	private void seedPlatformAdminUser(String username) {

		Role role = findOrCreateRole("PLATFORM_ADMIN");

		User user = new User();
		user.setUsername(username);
		user.setPassword(passwordEncoder.encode("test-password"));
		user.setRoles(java.util.Set.of(role));
		userRepository.saveAndFlush(user);
	}

	/**
	 * Seeds a membership-less user whose only role is a business one: the
	 * regression guard for the platform login branch — the 403 of the
	 * membership-less login must stay for anyone without PLATFORM_ADMIN.
	 */
	private void seedUserWithSingleBusinessRole(String username) {

		Role role = findOrCreateRole("KITCHEN");

		User user = new User();
		user.setUsername(username);
		user.setPassword(passwordEncoder.encode("test-password"));
		user.setRoles(java.util.Set.of(role));
		userRepository.saveAndFlush(user);
	}

	/**
	 * The role row is reused when it already exists (shared dev databases
	 * keep seeded roles; the unique uq_roles_name would reject a second
	 * insert) and created on a fresh one.
	 */
	private Role findOrCreateRole(String name) {

		return roleRepository.findByName(name)
				.orElseGet(() -> {
					Role created = new Role();
					created.setName(name);
					return roleRepository.saveAndFlush(created);
				});
	}

}
