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
 * Contract of {@code POST /api/platform/clients/{id}/users/{username}/password}:
 * the operator resets ANY locale user's password (S3 of
 * odd/tasks/plataforma-usuarios.md). Behind the existing ROLE_ADMIN
 * barrier; same rules as the client-level reset.
 *
 * <p>Rules under test:</p>
 *
 * <ul>
 * <li>200: the stored hash becomes a NEW bcrypt hash and the user logs in
 * with the reset password right after; the old password stops working.</li>
 * <li>400 for a password shorter than 8, leaving the stored hash untouched;
 * 404 for a user not in that locale; 404 unknown client.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class PlatformUsersPasswordResetHttpTests {

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

	private String run;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		run = "up" + System.nanoTime();
	}

	@Test
	@DisplayName("reset changes the hash and the user logs in with the new password")
	void resetChangesHashAndUserLogsIn() throws Exception {

		String[] businessId = new String[1];
		seedClientWithUsers(businessId, "old-pass-123");

		String token = platformAdminToken();

		String body = """
				{"password": "reset-pass-456"}
				""";

		mockMvc.perform(post("/api/platform/clients/" + businessId[0] + "/users/kitchen-" + run + "/password")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("kitchen-" + run));

		User user = userRepository.findByUsername("kitchen-" + run).orElseThrow();
		assertThat(passwordEncoder.matches("reset-pass-456", user.getPassword())).isTrue();
		assertThat(passwordEncoder.matches("old-pass-123", user.getPassword())).isFalse();

		// The proof that matters: the user logs in with the reset password.
		String loginBody = """
				{"username": "kitchen-%s", "password": "reset-pass-456"}
				""".formatted(run);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(loginBody))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("short password answers 400 and leaves the stored hash untouched")
	void shortPasswordAnswersValidation() throws Exception {

		String[] businessId = new String[1];
		seedClientWithUsers(businessId, "old-pass-123");

		String token = platformAdminToken();
		String hashBefore = userRepository.findByUsername("kitchen-" + run).orElseThrow().getPassword();

		String body = """
				{"password": "corta"}
				""";

		mockMvc.perform(post("/api/platform/clients/" + businessId[0] + "/users/kitchen-" + run + "/password")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("La contraseña debe tener al menos 8 caracteres"));

		assertThat(userRepository.findByUsername("kitchen-" + run).orElseThrow().getPassword())
				.isEqualTo(hashBefore);
	}

	@Test
	@DisplayName("a user not in that locale answers 404")
	void userOfAnotherLocaleAnswersNotFound() throws Exception {

		String[] businessId = new String[1];
		seedClientWithUsers(businessId, "old-pass-123");

		// A user that exists but belongs to a DIFFERENT business.
		Business other = BusinessAuthSeed.seedBusiness(businessRepository,
				987_905_500L + (run.hashCode() & 0xff), "otro-" + run);
		seedUserWithRole(other, "foraneo-" + run, "foraneo-" + run + "@test.dev", "KITCHEN", true,
				"foraneo-pass-123");

		String token = platformAdminToken();

		String body = """
				{"password": "reset-pass-456"}
				""";

		mockMvc.perform(post("/api/platform/clients/" + businessId[0] + "/users/foraneo-" + run + "/password")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound());

		// The foreign user's hash is untouched.
		assertThat(userRepository.findByUsername("foraneo-" + run).orElseThrow().getPassword())
				.isNotEqualTo("reset-pass-456");
		assertThat(userRepository.findByUsername("foraneo-" + run).orElseThrow().getPassword())
				.startsWith("$2");
	}

	@Test
	@DisplayName("unknown client answers 404")
	void unknownClientAnswersNotFound() throws Exception {

		String token = platformAdminToken();

		String body = """
				{"password": "reset-pass-456"}
				""";

		mockMvc.perform(post("/api/platform/clients/999999999/users/nadie-" + run + "/password")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound());
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	private void seedClientWithUsers(String[] businessId, String rawPassword) {

		Business business = BusinessAuthSeed.seedBusiness(businessRepository,
				987_905_600L + (run.hashCode() & 0xff), "biz-" + run);
		businessId[0] = String.valueOf(business.getId());

		seedUserWithRole(business, "owner-" + run, "owner-" + run + "@test.dev", "OWNER", true,
				"test-password");
		seedUserWithRole(business, "kitchen-" + run, "kitchen-" + run + "@test.dev", "KITCHEN", true,
				rawPassword);
	}

	private void seedUserWithRole(Business business, String username, String email, String roleName,
			boolean active, String rawPassword) {

		Role role = roleRepository.findByName(roleName)
				.orElseGet(() -> {
					Role created = new Role();
					created.setName(roleName);
					return roleRepository.saveAndFlush(created);
				});

		User user = new User();
		user.setUsername(username);
		user.setEmail(email);
		user.setPassword(passwordEncoder.encode(rawPassword));
		user.setActive(active);
		user.setRoles(java.util.Set.of(role));
		userRepository.saveAndFlush(user);

		var membership = new com.carrito.saas.repository.entity.BusinessUser();
		membership.setUser(user);
		membership.setBusiness(business);
		membership.setRole(role);
		businessUserRepository.saveAndFlush(membership);
	}

	/** Logs a ADMIN in through the real login endpoint. */
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
