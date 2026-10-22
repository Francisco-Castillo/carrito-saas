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
 * Contract of {@code POST /api/platform/clients/{id}/users}: the operator
 * registers a user for ANY client's locale, with a role and a working
 * password (S3 of odd/tasks/plataforma-usuarios.md). Behind the existing
 * ROLE_ADMIN barrier.
 *
 * <p>Rules under test:</p>
 *
 * <ul>
 * <li>201 with the created user (username, email, role, active): the rows
 * are real (user + membership) and the user logs in right after with the
 * password the operator typed — the S4-style proof that the alta creates a
 * working account.</li>
 * <li>409 CONFLICT on duplicate username/email; 400 on unknown role,
 * blank fields and a password shorter than 8; 404 unknown client.</li>
 * <li>The created user's account starts ACTIVE.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class PlatformUsersAltaHttpTests {

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

		run = "ua" + System.nanoTime();
	}

	@Test
	@DisplayName("alta creates the user with a role and the user logs in right after")
	void altaCreatesUserThatLogsIn() throws Exception {

		String[] businessId = new String[1];
		seedClient(businessId);

		String token = platformAdminToken();

		String body = """
				{
				  "username": "cajero-%s",
				  "email": "cajero-%s@test.dev",
				  "password": "cajero-pass-123",
				  "role": "KITCHEN"
				}
				""".formatted(run, run);

		mockMvc.perform(post("/api/platform/clients/" + businessId[0] + "/users")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value("cajero-" + run))
				.andExpect(jsonPath("$.email").value("cajero-" + run + "@test.dev"))
				.andExpect(jsonPath("$.role").value("KITCHEN"))
				.andExpect(jsonPath("$.active").value(true));

		// Rows are real: the membership links the new user with the role.
		var membership = businessUserRepository
				.findByUserUsernameAndBusinessSlug("cajero-" + run, "biz-" + run);
		assertThat(membership).isPresent();
		assertThat(membership.get().getRole().getName()).isEqualTo("KITCHEN");

		// The password works: the user logs in with it right away.
		String loginBody = """
				{"username": "cajero-%s", "password": "cajero-pass-123"}
				""".formatted(run);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(loginBody))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("duplicate username answers 409")
	void duplicateUsernameAnswersConflict() throws Exception {

		String[] businessId = new String[1];
		seedClient(businessId);

		String token = platformAdminToken();

		String body = """
				{
				  "username": "owner-%s",
				  "email": "otro-%s@test.dev",
				  "password": "cajero-pass-123",
				  "role": "KITCHEN"
				}
				""".formatted(run, run);

		mockMvc.perform(post("/api/platform/clients/" + businessId[0] + "/users")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe un usuario con ese nombre"));
	}

	@Test
	@DisplayName("duplicate email answers 409")
	void duplicateEmailAnswersConflict() throws Exception {

		String[] businessId = new String[1];
		seedClient(businessId);

		String token = platformAdminToken();

		String body = """
				{
				  "username": "nuevo-%s",
				  "email": "owner-%s@test.dev",
				  "password": "cajero-pass-123",
				  "role": "KITCHEN"
				}
				""".formatted(run, run);

		mockMvc.perform(post("/api/platform/clients/" + businessId[0] + "/users")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe un usuario con ese email"));
	}

	@Test
	@DisplayName("short password answers 400 and leaves no rows")
	void shortPasswordAnswersValidation() throws Exception {

		String[] businessId = new String[1];
		seedClient(businessId);

		String token = platformAdminToken();

		String body = """
				{
				  "username": "nuevo-%s",
				  "email": "nuevo-%s@test.dev",
				  "password": "corta",
				  "role": "KITCHEN"
				}
				""".formatted(run, run);

		mockMvc.perform(post("/api/platform/clients/" + businessId[0] + "/users")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("La contraseña debe tener al menos 8 caracteres"));

		assertThat(userRepository.findByUsername("nuevo-" + run)).isEmpty();
	}

	@Test
	@DisplayName("alta with role ADMIN answers 400: the platform-exclusive role never reaches a locale")
	void altaWithAdminRoleAnswersValidation() throws Exception {

		// S2 de simplificacion-roles.md: ADMIN es el rol exclusivo de la
		// plataforma. Un usuario de local con ROLE_ADMIN tendría authorities
		// globales y podría golpear /api/platform/** con token de negocio.
		String[] businessId = new String[1];
		seedClient(businessId);

		String token = platformAdminToken();

		String body = """
				{
				  "username": "admin-%s",
				  "email": "admin-%s@test.dev",
				  "password": "admin-pass-123",
				  "role": "ADMIN"
				}
				""".formatted(run, run);

		mockMvc.perform(post("/api/platform/clients/" + businessId[0] + "/users")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El rol ADMIN es exclusivo de la plataforma"));

		// Ni el usuario ni la membresía ni la fila del rol quedan: el guard
		// corre ANTES del find-or-create del catálogo.
		assertThat(userRepository.findByUsername("admin-" + run)).isEmpty();
		assertThat(businessUserRepository.findByUserUsernameAndBusinessSlug("admin-" + run, "biz-" + run)).isEmpty();
	}

	@Test
	@DisplayName("alta with an unknown role creates the role on the fly (catalogue seeds itself)")
	void altaWithUnknownRoleCreatesTheRole() throws Exception {

		String[] businessId = new String[1];
		seedClient(businessId);

		String token = platformAdminToken();

		String body = """
				{
				  "username": "mozo-%s",
				  "email": "mozo-%s@test.dev",
				  "password": "cajero-pass-123",
				  "role": "MOZO"
				}
				""".formatted(run, run);

		mockMvc.perform(post("/api/platform/clients/" + businessId[0] + "/users")
					.header("Authorization", "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("MOZO"));

		// The role row now exists (find-or-create keeps the CI catalogue
		// self-seeding) and the membership uses it.
		assertThat(roleRepository.findByName("MOZO")).isPresent();
		var membership = businessUserRepository
				.findByUserUsernameAndBusinessSlug("mozo-" + run, "biz-" + run);
		assertThat(membership).isPresent();
		assertThat(membership.get().getRole().getName()).isEqualTo("MOZO");
	}

	@Test
	@DisplayName("blank fields answer 400 and leave no rows")
	void blankFieldsAnswerValidation() throws Exception {

		String[] businessId = new String[1];
		seedClient(businessId);

		String token = platformAdminToken();

		String body = """
				{
				  "username": "   ",
				  "email": "nuevo-%s@test.dev",
				  "password": "cajero-pass-123",
				  "role": "KITCHEN"
				}
				""".formatted(run);

		mockMvc.perform(post("/api/platform/clients/" + businessId[0] + "/users")
					.header("Authorization", "Bearer " + token)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El usuario es obligatorio"));

		assertThat(userRepository.findByEmail("nuevo-" + run + "@test.dev")).isEmpty();
	}

	@Test
	@DisplayName("unknown client answers 404")
	void unknownClientAnswersNotFound() throws Exception {

		String token = platformAdminToken();

		String body = """
				{
				  "username": "nuevo-%s",
				  "email": "nuevo-%s@test.dev",
				  "password": "cajero-pass-123",
				  "role": "KITCHEN"
				}
				""".formatted(run, run);

		mockMvc.perform(post("/api/platform/clients/999999999/users")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound());
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	/** Seeds a client with its OWNER membership (the alta target). */
	private void seedClient(String[] businessId) {

		Business business = BusinessAuthSeed.seedBusiness(businessRepository,
				987_905_300L + (run.hashCode() & 0xff), "biz-" + run);
		businessId[0] = String.valueOf(business.getId());

		Role role = roleRepository.findByName("OWNER")
				.orElseGet(() -> {
					Role created = new Role();
					created.setName("OWNER");
					return roleRepository.saveAndFlush(created);
				});

		User user = new User();
		user.setUsername("owner-" + run);
		user.setEmail("owner-" + run + "@test.dev");
		user.setPassword(passwordEncoder.encode("test-password"));
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
