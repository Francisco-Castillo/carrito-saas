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

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Role;
import com.carrito.saas.repository.entity.User;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * Contract of {@code GET /api/platform/clients/{id}/users}: the operator
 * lists the users of ANY client — username, email, role, deactivation state
 * (S2/S3 of odd/tasks/plataforma-usuarios.md). Behind the existing
 * ROLE_ADMIN barrier.
 */
@SpringBootTest
@Transactional
class PlatformUsersListingHttpTests {

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

		run = "ul" + System.nanoTime();
	}

	@Test
	@DisplayName("GET lists every user of the client with role and state")
	void listingAnswersEveryUserWithRoleAndState() throws Exception {

		String[] businessId = new String[1];

		seedClientWithUsers(businessId);

		String token = platformAdminToken();

		String response = mockMvc.perform(get("/api/platform/clients/" + businessId[0] + "/users")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.username)].username").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		java.util.List<String> usernames = com.jayway.jsonpath.JsonPath.read(response, "$[*].username");
		assertThat(usernames).contains("owner-" + run, "kitchen-" + run, "deactivated-" + run);

		// Roles and state travel per user. The filter expression returns a
		// LIST — the assertions read its first element.
		java.util.List<String> kitchenRole = com.jayway.jsonpath.JsonPath.read(response,
				"$[?(@.username == 'kitchen-" + run + "')].role");
		assertThat(kitchenRole).containsExactly("KITCHEN");

		java.util.List<Boolean> deactivatedActive = com.jayway.jsonpath.JsonPath.read(response,
				"$[?(@.username == 'deactivated-" + run + "')].active");
		assertThat(deactivatedActive).containsExactly(false);
	}

	@Test
	@DisplayName("unknown client answers 404")
	void unknownClientAnswersNotFound() throws Exception {

		String token = platformAdminToken();

		mockMvc.perform(get("/api/platform/clients/999999999/users")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound());
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	/** Seeds a client with an OWNER, a KITCHEN and a deactivated user. */
	private void seedClientWithUsers(String[] businessId) {

		Business business = BusinessAuthSeed.seedBusiness(businessRepository,
				987_905_200L + (run.hashCode() & 0xff), "biz-" + run);
		businessId[0] = String.valueOf(business.getId());

		seedUserWithRole(business, "owner-" + run, "owner-" + run + "@test.dev", "OWNER", true);
		seedUserWithRole(business, "kitchen-" + run, "kitchen-" + run + "@test.dev", "KITCHEN", true);
		seedUserWithRole(business, "deactivated-" + run, "deactivated-" + run + "@test.dev", "KITCHEN", false);
	}

	private void seedUserWithRole(Business business, String username, String email, String roleName,
			boolean active) {

		Role role = roleRepository.findByName(roleName)
				.orElseGet(() -> {
					Role created = new Role();
					created.setName(roleName);
					return roleRepository.saveAndFlush(created);
				});

		User user = new User();
		user.setUsername(username);
		user.setEmail(email);
		user.setPassword(passwordEncoder.encode("test-password"));
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
