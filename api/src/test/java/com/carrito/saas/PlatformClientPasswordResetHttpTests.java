package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Role;
import com.carrito.saas.repository.entity.User;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * Contract of {@code POST /api/platform/clients/{id}/password}: the operator
 * resets the OWNER's password (S4 of odd/tasks/plataforma-editar-baja.md) —
 * for when the client forgot it.
 *
 * <p>Rules under test:</p>
 *
 * <ul>
 * <li>200: the owner's stored password becomes a NEW bcrypt hash and the
 * owner logs in with the reset password right after (the proof that the
 * reset creates a working credential). The old password stops working.</li>
 * <li>400 for a password shorter than 8 (same rule as the alta); 404 for an
 * unknown id; 404 when the business has no owner (nothing to reset).</li>
 * <li>403 for a business caller (OWNER) — the barrier is owned by
 * {@code PlatformPrivilegeHttpTests}; here it guards the WRITE route.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class PlatformClientPasswordResetHttpTests {

	private static final Long OWNER_BUSINESS_ID = 987_904_501L;

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

		run = "pw" + System.nanoTime();
	}

	@Test
	@DisplayName("reset changes the owner's password and the owner logs in with it right after")
	void resetChangesPasswordAndOwnerLogsIn() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness client = seedClient("local-" + run, "owner-" + run,
				"old-pass-123");

		String body = """
				{"password": "reset-pass-456"}
				""";

		mockMvc.perform(post("/api/platform/clients/" + client.businessId() + "/password")
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerUsername").value("owner-" + run));

		// The stored hash CHANGED (bcrypt, not plaintext).
		User owner = userRepository.findByUsername("owner-" + run).orElseThrow();
		assertThat(owner.getPassword()).startsWith("$2");
		assertThat(passwordEncoder.matches("reset-pass-456", owner.getPassword())).isTrue();
		assertThat(passwordEncoder.matches("old-pass-123", owner.getPassword())).isFalse();

		// The proof that matters: the owner logs in with the reset password.
		String loginBody = """
				{"username": "owner-%s", "password": "reset-pass-456"}
				""".formatted(run);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(loginBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("OWNER"));
	}

	@Test
	@DisplayName("password shorter than 8 answers 400 and does not touch the stored hash")
	void shortPasswordAnswersValidation() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness client = seedClient("local-" + run, "owner-" + run,
				"old-pass-123");

		String hashBefore = userRepository.findByUsername("owner-" + run).orElseThrow().getPassword();

		String body = """
				{"password": "corta"}
				""".formatted();

		mockMvc.perform(post("/api/platform/clients/" + client.businessId() + "/password")
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("La contraseña debe tener al menos 8 caracteres"));

		assertThat(userRepository.findByUsername("owner-" + run).orElseThrow().getPassword())
				.isEqualTo(hashBefore);
	}

	@Test
	@DisplayName("unknown id answers 404")
	void unknownIdAnswersNotFound() throws Exception {

		String body = """
				{"password": "reset-pass-456"}
				""";

		mockMvc.perform(post("/api/platform/clients/999999999/password")
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("a business without an owner answers 404 (nothing to reset)")
	void businessWithoutOwnerAnswersNotFound() throws Exception {

		Business business = BusinessAuthSeed.seedBusiness(businessRepository, 987_904_502L,
				"ownerless-" + run);
		assertThat(business.getId()).isNotNull();

		String body = """
				{"password": "reset-pass-456"}
				""";

		mockMvc.perform(post("/api/platform/clients/" + business.getId() + "/password")
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("El local no tiene dueño"));
	}

	@Test
	@DisplayName("business OWNER caller of the reset gets 403")
	void ownerCallerIsForbidden() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness owner = BusinessAuthSeed.seedOwner(
				businessRepository, userRepository, roleRepository, businessUserRepository,
				jwtUtil, OWNER_BUSINESS_ID, "reset-owner-" + run, "reset-owner-" + run);

		String body = """
				{"password": "reset-pass-456"}
				""";

		mockMvc.perform(post("/api/platform/clients/" + owner.businessId() + "/password")
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isForbidden());
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	/**
	 * Seeds a full client through the SHARED seed with a KNOWN raw password
	 * (bcrypt-hashed, like the alta stores it), plus the owner's email.
	 */
	private BusinessAuthSeed.AuthenticatedBusiness seedClient(String slug, String username,
			String rawPassword) {

		BusinessAuthSeed.AuthenticatedBusiness client = BusinessAuthSeed.seedOwner(
				businessRepository, userRepository, roleRepository, businessUserRepository,
				jwtUtil, 987_904_600L + (slug.hashCode() & 0xff), slug, username);

		User owner = userRepository.findByUsername(username).orElseThrow();
		owner.setEmail(username + "@test.dev");
		owner.setPassword(passwordEncoder.encode(rawPassword));
		userRepository.saveAndFlush(owner);

		return client;
	}

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

}
