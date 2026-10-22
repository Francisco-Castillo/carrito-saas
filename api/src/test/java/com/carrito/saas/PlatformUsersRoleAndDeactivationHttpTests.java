package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Contracts of {@code PUT /api/platform/clients/{id}/users/{username}} (role
 * change, optional reactivation) and {@code DELETE
 * /api/platform/clients/{id}/users/{username}} (LOGICAL deactivation) —
 * S3/S4 of odd/tasks/plataforma-usuarios.md. Behind the existing
 * ROLE_ADMIN barrier.
 *
 * <p>Rules under test:</p>
 *
 * <ul>
 * <li>PUT changes the role of the membership; reactivation travels in the
 * optional active flag; unknown user answers 404; unknown role 400.</li>
 * <li>DELETE deactivates logically (active=false, row survives,
 * idempotent); unknown user 404.</li>
 * <li>The PROTECTION rule: the operator can never leave a locale without
 * an ACTIVE owner — demoting or deactivating the last one answers 409 with
 * a legible message and nothing changes.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class PlatformUsersRoleAndDeactivationHttpTests {

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

	private String run;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		run = "ur" + System.nanoTime();
	}

	@Test
	@DisplayName("PUT changes the role of a membership")
	void putChangesRole() throws Exception {

		String[] businessId = new String[1];
		seedClientWithUsers(businessId);

		String token = platformAdminToken();

		String body = """
				{"role": "OWNER"}
				""";

		mockMvc.perform(put("/api/platform/clients/" + businessId[0] + "/users/kitchen-" + run)
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("kitchen-" + run))
				.andExpect(jsonPath("$.role").value("OWNER"));

		// The membership is real: the role row changed.
		var membership = businessUserRepository
				.findByUserUsernameAndBusinessSlug("kitchen-" + run, "biz-" + run);
		assertThat(membership).isPresent();
		assertThat(membership.get().getRole().getName()).isEqualTo("OWNER");
	}

	@Test
	@DisplayName("PUT with active=true reactivates a deactivated user")
	void putReactivates() throws Exception {

		String[] businessId = new String[1];
		seedClientWithUsers(businessId);

		String token = platformAdminToken();

		String body = """
				{"active": true}
				""";

		mockMvc.perform(put("/api/platform/clients/" + businessId[0] + "/users/deactivated-" + run)
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));

		assertThat(userRepository.findByUsername("deactivated-" + run).orElseThrow().getActive()).isTrue();
	}

	@Test
	@DisplayName("unknown user in PUT answers 404")
	void putUnknownUserAnswersNotFound() throws Exception {

		String[] businessId = new String[1];
		seedClientWithUsers(businessId);

		String token = platformAdminToken();

		String body = """
				{"role": "OWNER"}
				""";

		mockMvc.perform(put("/api/platform/clients/" + businessId[0] + "/users/nadie-" + run)
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Usuario no encontrado en este local"));
	}

	@Test
	@DisplayName("PUT with an unknown role answers 400")
	void putUnknownRoleAnswersValidation() throws Exception {

		String[] businessId = new String[1];
		seedClientWithUsers(businessId);

		String token = platformAdminToken();

		String body = """
				{"role": "MAGO"}
				""";

		mockMvc.perform(put("/api/platform/clients/" + businessId[0] + "/users/kitchen-" + run)
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El rol indicado no existe"));
	}

	@Test
	@DisplayName("PUT to role ADMIN answers 400: the platform-exclusive role never reaches a locale")
	void putAdminRoleAnswersValidation() throws Exception {

		// S2 de simplificacion-roles.md: ni por edición un usuario de local
		// termina con el rol de plataforma.
		String[] businessId = new String[1];
		seedClientWithUsers(businessId);

		String token = platformAdminToken();

		String body = """
				{"role": "ADMIN"}
				""";

		mockMvc.perform(put("/api/platform/clients/" + businessId[0] + "/users/kitchen-" + run)
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El rol ADMIN es exclusivo de la plataforma"));
	}

	@Test
	@DisplayName("DELETE deactivates logically (row survives) and is idempotent")
	void deleteDeactivatesLogically() throws Exception {

		String[] businessId = new String[1];
		seedClientWithUsers(businessId);

		String token = platformAdminToken();

		mockMvc.perform(delete("/api/platform/clients/" + businessId[0] + "/users/kitchen-" + run)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		// Logical: the row and the membership survive.
		User user = userRepository.findByUsername("kitchen-" + run).orElseThrow();
		assertThat(user.getActive()).isFalse();
		assertThat(businessUserRepository
				.findByUserUsernameAndBusinessSlug("kitchen-" + run, "biz-" + run)).isPresent();

		// Idempotent: the second DELETE answers 200 without changes.
		mockMvc.perform(delete("/api/platform/clients/" + businessId[0] + "/users/kitchen-" + run)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));
	}

	@Test
	@DisplayName("deactivating the LAST ACTIVE OWNER answers 409 and changes nothing")
	void deleteLastActiveOwnerAnswersConflict() throws Exception {

		String[] businessId = new String[1];
		seedClientWithUsers(businessId);

		String token = platformAdminToken();

		mockMvc.perform(delete("/api/platform/clients/" + businessId[0] + "/users/owner-" + run)
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("El local necesita al menos un dueño activo"));

		assertThat(userRepository.findByUsername("owner-" + run).orElseThrow().getActive()).isTrue();
	}

	@Test
	@DisplayName("demoting the LAST ACTIVE OWNER answers 409 and changes nothing")
	void putLastActiveOwnerAnswersConflict() throws Exception {

		String[] businessId = new String[1];
		seedClientWithUsers(businessId);

		String token = platformAdminToken();

		String body = """
				{"role": "KITCHEN"}
				""";

		mockMvc.perform(put("/api/platform/clients/" + businessId[0] + "/users/owner-" + run)
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("El local necesita al menos un dueño activo"));

		var membership = businessUserRepository
				.findByUserUsernameAndBusinessSlug("owner-" + run, "biz-" + run);
		assertThat(membership).isPresent();
		assertThat(membership.get().getRole().getName()).isEqualTo("OWNER");
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	/**
	 * Seeds a client with an ACTIVE owner and a KITCHEN (active) plus a
	 * deactivated KITCHEN — enough shapes for the role and deactivation
	 * contracts.
	 */
	private void seedClientWithUsers(String[] businessId) {

		Business business = BusinessAuthSeed.seedBusiness(businessRepository,
				987_905_400L + (run.hashCode() & 0xff), "biz-" + run);
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
