package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * Contract of the LOGICAL deactivation of users (S4 of
 * odd/tasks/plataforma-usuarios.md): a deactivated user CANNOT log in —
 * the credential answers invalid (401), the same as a wrong password, so
 * the error does not reveal account state. History stays untouched; there
 * is no physical DELETE.
 *
 * <p>The gate lives in {@code CustomUserDetailsService} (disabled account),
 * so EVERY login path — business and platform — honors it.</p>
 */
@SpringBootTest
@Transactional
class UserDeactivationLoginHttpTests {

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

		run = "ud" + System.nanoTime();
	}

	@Test
	@DisplayName("an ACTIVE user logs in (the gate only removes deactivated ones)")
	void activeUserLogsIn() throws Exception {

		seedUser("activo-" + run, true);

		String body = """
				{"username": "activo-%s", "password": "test-password"}
				""".formatted(run);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("a DEACTIVATED user cannot log in: 401, indistinguishable from bad credentials")
	void deactivatedUserCannotLogIn() throws Exception {

		seedUser("baja-" + run, false);

		String body = """
				{"username": "baja-%s", "password": "test-password"}
				""".formatted(run);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isUnauthorized());

		// The row is intact (logical, not physical): reactivation owns the
		// way back.
		User user = userRepository.findByUsername("baja-" + run).orElseThrow();
		assertThat(user.getActive()).isFalse();
		assertThat(user.getPassword()).startsWith("$2");
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	/**
	 * Seeds a user with the given deactivation state and an OWNER membership
	 * in its own business (the business login path needs the membership).
	 */
	private void seedUser(String username, boolean active) {

		Business business = BusinessAuthSeed.seedBusiness(businessRepository,
				987_905_100L + (username.hashCode() & 0xff), "biz-" + username);

		Role role = roleRepository.findByName("OWNER")
				.orElseGet(() -> {
					Role created = new Role();
					created.setName("OWNER");
					return roleRepository.saveAndFlush(created);
				});

		User user = new User();
		user.setUsername(username);
		user.setEmail(username + "@test.dev");
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

}
