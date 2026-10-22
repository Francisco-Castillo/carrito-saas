package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Contract of {@code POST /api/platform/clients}: the operator registers a
 * CLIENT in one call — the business (the locale) and its owner user with a
 * usable password — and the client can log into their own panel right after
 * (S1, S4 of odd/tasks/plataforma-altas.md).
 *
 * <p>Rules under test:</p>
 *
 * <ul>
 * <li>201 with the created client: businessId, slug, ownerUsername. The
 * owner logs in immediately afterwards (organic login, no slug: exactly one
 * membership) with the password the operator typed — the proof that the alta
 * creates a working account, not just rows.</li>
 * <li>409 CONFLICT with a legible Spanish message on duplicate business
 * slug, owner username, or owner email; and ATOMICITY: the failed alta must
 * leave NO trace — no user row when the slug collides, no business row when
 * the owner collides.</li>
 * <li>400 VALIDATION for blank required fields and a malformed slug.</li>
 * <li>403 for a business caller (OWNER) — the privilege barrier is owned by
 * {@code PlatformPrivilegeHttpTests}; here it guards the WRITE route.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class PlatformClientsHttpTests {

	private static final Long OWNER_BUSINESS_ID = 987_903_001L;

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

	/** Unique suffix per test: the shared dev DB keeps rows from past runs. */
	private String run;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		run = "run" + System.nanoTime();
	}

	@Test
	@DisplayName("valid alta returns 201, persists the client, and the owner logs in right away")
	void validAltaPersistsClientAndOwnerCanLogIn() throws Exception {

		String token = platformAdminToken();

		String body = """
				{
				  "business": {"name": "Local %s", "slug": "local-%s", "phone": "1150001111"},
				  "owner": {"username": "owner-%s", "email": "owner-%s@test.dev", "password": "client-pass-123"}
				}
				""".formatted(run, run, run, run);

		String[] businessId = new String[1];

		mockMvc.perform(post("/api/platform/clients")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.businessId").isNumber())
				.andExpect(jsonPath("$.slug").value("local-" + run))
				.andExpect(jsonPath("$.ownerUsername").value("owner-" + run))
				.andDo(result -> businessId[0] = String.valueOf((Object) com.jayway.jsonpath.JsonPath
						.read(result.getResponse().getContentAsString(), "$.businessId")));

		// Rows are real: the business exists and the owner is linked as OWNER.
		assertThat(businessRepository.findBySlug("local-" + run)).isPresent();
		var membership = businessUserRepository
				.findByUserUsernameAndBusinessSlug("owner-" + run, "local-" + run);
		assertThat(membership).isPresent();
		assertThat(membership.get().getRole().getName()).isEqualTo("OWNER");

		// S4 proof: the client logs into their own panel with the operator's
		// password. Organic login (no slug): exactly one membership.
		String loginBody = """
				{"username": "owner-%s", "password": "client-pass-123"}
				""".formatted(run);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(loginBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("OWNER"))
				.andExpect(jsonPath("$.businessId").value(Long.parseLong(businessId[0])));
	}

	@Test
	@DisplayName("duplicate business slug answers 409 and leaves NO user row (atomic)")
	void duplicateSlugAnswersConflictAndRollsBackOwner() throws Exception {

		String token = platformAdminToken();

		// Pre-existing business with this slug (no owner attached).
		BusinessAuthSeed.seedBusiness(businessRepository, 987_903_002L, "dup-" + run);

		String body = """
				{
				  "business": {"name": "Otro %s", "slug": "dup-%s"},
				  "owner": {"username": "owner-%s", "email": "owner-%s@test.dev", "password": "client-pass-123"}
				}
				""".formatted(run, run, run, run);

		mockMvc.perform(post("/api/platform/clients")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe un local con ese slug"));

		// Atomicity: the alta failed AFTER the owner was in flight; the user
		// row must not survive.
		assertThat(userRepository.findByUsername("owner-" + run)).isEmpty();
	}

	@Test
	@DisplayName("duplicate owner username answers 409 and leaves NO business row (atomic)")
	void duplicateOwnerUsernameAnswersConflictAndRollsBackBusiness() throws Exception {

		String token = platformAdminToken();

		BusinessAuthSeed.seedUserWithRole(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, 987_903_003L, "taken-" + run, "owner-" + run, "OWNER");

		String body = """
				{
				  "business": {"name": "Local nuevo %s", "slug": "local-%s"},
				  "owner": {"username": "owner-%s", "email": "nuevo-%s@test.dev", "password": "client-pass-123"}
				}
				""".formatted(run, run, run, run);

		mockMvc.perform(post("/api/platform/clients")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe un usuario con ese nombre"));

		// Atomicity: no business row survives a failed alta.
		assertThat(businessRepository.findBySlug("local-" + run)).isEmpty();
	}

	@Test
	@DisplayName("duplicate owner email answers 409")
	void duplicateOwnerEmailAnswersConflict() throws Exception {

		String token = platformAdminToken();

		String takenEmail = "taken-" + run + "@test.dev";

		seedOwnerWithEmail("mailowner-" + run, takenEmail, "takenmail-" + run, 987_903_004L);

		String body = """
				{
				  "business": {"name": "Local %s", "slug": "local-%s"},
				  "owner": {"username": "otro-%s", "email": "%s", "password": "client-pass-123"}
				}
				""".formatted(run, run, run, takenEmail);

		mockMvc.perform(post("/api/platform/clients")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe un usuario con ese email"));
	}

	@Test
	@DisplayName("blank owner password answers 400 with a legible message")
	void blankPasswordAnswersValidation() throws Exception {

		String token = platformAdminToken();

		String body = """
				{
				  "business": {"name": "Local %s", "slug": "local-%s"},
				  "owner": {"username": "owner-%s", "email": "owner-%s@test.dev", "password": ""}
				}
				""".formatted(run, run, run, run);

		mockMvc.perform(post("/api/platform/clients")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").isNotEmpty());

		assertThat(userRepository.findByUsername("owner-" + run)).isEmpty();
	}

	@Test
	@DisplayName("malformed slug answers 400")
	void malformedSlugAnswersValidation() throws Exception {

		String token = platformAdminToken();

		String body = """
				{
				  "business": {"name": "Local %s", "slug": "Mi Local!"},
				  "owner": {"username": "owner-%s", "email": "owner-%s@test.dev", "password": "client-pass-123"}
				}
				""".formatted(run, run, run);

		mockMvc.perform(post("/api/platform/clients")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El slug sólo admite minúsculas, números y guiones"));

		assertThat(businessRepository.findBySlug("Mi Local!")).isEmpty();
	}

	@Test
	@DisplayName("business OWNER caller of the alta gets 403")
	void ownerCallerIsForbidden() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness owner = BusinessAuthSeed.seedOwner(
				businessRepository, userRepository, roleRepository, businessUserRepository,
				jwtUtil, OWNER_BUSINESS_ID, "alta-owner-" + run, "alta-owner-" + run);

		mockMvc.perform(post("/api/platform/clients")
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("GET /api/platform/clients lists every business, owner included when it has one")
	void listingReturnsEveryBusinessWithItsOwner() throws Exception {

		String token = platformAdminToken();

		// Two clients registered through the real alta…
		for (int i = 1; i <= 2; i++) {
			String body = """
					{
					  "business": {"name": "Local %d %s", "slug": "lista-%d-%s", "phone": "11500011%02d"},
					  "owner": {"username": "owner-%d-%s", "email": "owner-%d-%s@test.dev", "password": "client-pass-123"}
					}
					""".formatted(i, run, i, run, i, i, run, i, run);

			mockMvc.perform(post("/api/platform/clients")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
					.andExpect(status().isCreated());
		}

		// …and one seeded business WITHOUT an owner (honest listing: the
		// operator sees the row, with null owner fields).
		BusinessAuthSeed.seedBusiness(businessRepository, 987_903_005L, "solo-local-" + run);

		String response = mockMvc.perform(get("/api/platform/clients")
					.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		var clients = com.jayway.jsonpath.JsonPath.read(response, "$[?(@.slug)]");
		
		var bySlug = new java.util.HashMap<String, java.util.Map<String, Object>>();
		@SuppressWarnings("unchecked")
		java.util.List<java.util.Map<String, Object>> list = (java.util.List<java.util.Map<String, Object>>) clients;
		for (var client : list) {
			bySlug.put((String) client.get("slug"), client);
		}

		for (int i = 1; i <= 2; i++) {
			var client = bySlug.get("lista-%d-%s".formatted(i, run));
			assertThat(client).isNotNull();
			assertThat(client.get("businessName")).isEqualTo("Local %d %s".formatted(i, run));
			assertThat(client.get("ownerUsername")).isEqualTo("owner-%d-%s".formatted(i, run));
			assertThat(client.get("ownerEmail")).isEqualTo("owner-%d-%s@test.dev".formatted(i, run));
			assertThat((Integer) client.get("businessId")).isPositive();
		}

		var solo = bySlug.get("solo-local-" + run);
		assertThat(solo).isNotNull();
		assertThat(solo.get("ownerUsername")).isNull();
	}

	@Test
	@DisplayName("GET /api/platform/clients keeps the listing ordered oldest first")
	void listingIsOrderedOldestFirst() throws Exception {

		String token = platformAdminToken();

		for (int i = 1; i <= 3; i++) {
			String body = """
					{
					  "business": {"name": "Orden %d %s", "slug": "orden-%d-%s"},
					  "owner": {"username": "owner-%d-%s", "email": "owner-%d-%s@test.dev", "password": "client-pass-123"}
					}
					""".formatted(i, run, i, run, i, run, i, run);

			mockMvc.perform(post("/api/platform/clients")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
					.andExpect(status().isCreated());
		}

		String response = mockMvc.perform(get("/api/platform/clients")
					.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		java.util.List<String> slugs = com.jayway.jsonpath.JsonPath.read(response, "$[*].slug");

		int first = slugs.indexOf("orden-1-" + run);
		int second = slugs.indexOf("orden-2-" + run);
		int third = slugs.indexOf("orden-3-" + run);
		assertThat(first).isLessThan(second);
		assertThat(second).isLessThan(third);
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	/**
	 * Logs a ADMIN in through the real login endpoint and returns
	 * the minted token: the same credential the operator panel will use.
	 */
	private String platformAdminToken() throws Exception {

		Role role = roleRepository.findByName("ADMIN")
				.orElseGet(() -> {
					Role created = new Role();
					created.setName("ADMIN");
					return roleRepository.saveAndFlush(created);
				});

		User user = new User();
		user.setUsername("platform-admin-" + run);
		user.setPassword(passwordEncoder.encode("operator-pass-123"));
		user.setRoles(java.util.Set.of(role));
		userRepository.saveAndFlush(user);

		String body = """
				{"username": "platform-admin-%s", "password": "operator-pass-123"}
				""".formatted(run);

		String response = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		return com.jayway.jsonpath.JsonPath.read(response, "$.token");
	}

	/**
	 * Seeds an owner user WITH a specific email (the shared fixture does not
	 * set emails) and a membership in its own business.
	 */
	private void seedOwnerWithEmail(String username, String email, String slug, Long businessId) {

		BusinessAuthSeed.seedBusiness(businessRepository, businessId, slug);

		Role role = roleRepository.findByName("OWNER")
				.orElseGet(() -> {
					Role created = new Role();
					created.setName("OWNER");
					return roleRepository.saveAndFlush(created);
				});

		User user = new User();
		user.setUsername(username);
		user.setEmail(email);
		user.setPassword(passwordEncoder.encode("test-password"));
		user.setRoles(java.util.Set.of(role));
		userRepository.saveAndFlush(user);

		var businessUser = new com.carrito.saas.repository.entity.BusinessUser();
		businessUser.setUser(user);
		businessUser.setBusiness(businessRepository.findBySlug(slug).orElseThrow());
		businessUser.setRole(role);
		businessUserRepository.saveAndFlush(businessUser);
	}

}
