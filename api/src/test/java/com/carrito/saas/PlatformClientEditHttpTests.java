package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Contract of {@code PUT /api/platform/clients/{id}}: the operator edits an
 * EXISTING client — the SAFE fields only (name, phone, owner email) and may
 * reactivate a deactivated one (S2/S3 of odd/tasks/plataforma-editar-baja.md).
 *
 * <p>Rules under test:</p>
 *
 * <ul>
 * <li>200 with the updated client: name/phone/ownerEmail persist and the
 * slug NEVER moves — it is the public identity (printed QR codes, menu
 * URLs); a payload carrying a slug keeps the original one.</li>
 * <li>404 for an unknown id; 409 CONFLICT when the owner email collides
 * with a DIFFERENT user's email; 400 for a malformed phone.</li>
 * <li>Reactivation: a deactivated client (active=false, seeded directly)
 * comes back with active=true.</li>
 * <li>403 for a business caller (OWNER) — the barrier is owned by
 * {@code PlatformPrivilegeHttpTests}; here it guards the WRITE route.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class PlatformClientEditHttpTests {

	private static final Long OWNER_BUSINESS_ID = 987_904_101L;

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

		run = "ed" + System.nanoTime();
	}

	@Test
	@DisplayName("PUT edits the safe fields and NEVER moves the slug")
	void putEditsSafeFieldsAndKeepsSlug() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness client = seedClient("local-" + run, "owner-" + run);

		String body = """
				{
				  "name": "Local Editado %s",
				  "phone": "1160002222",
				  "ownerEmail": "nuevo-%s@test.dev",
				  "slug": "intenté-cambiar-el-slug"
				}
				""".formatted(run, run);

		mockMvc.perform(put("/api/platform/clients/" + client.businessId())
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.slug").value("local-" + run))
				.andExpect(jsonPath("$.businessName").value("Local Editado " + run))
				.andExpect(jsonPath("$.phone").value("1160002222"))
				.andExpect(jsonPath("$.ownerEmail").value("nuevo-" + run + "@test.dev"));

		// Rows are real: the slug survived verbatim, the fields moved.
		Business business = businessRepository.findById(client.businessId()).orElseThrow();
		assertThat(business.getSlug()).isEqualTo("local-" + run);
		assertThat(business.getName()).isEqualTo("Local Editado " + run);
		assertThat(business.getPhone()).isEqualTo("1160002222");
	}

	@Test
	@DisplayName("PUT keeps the original owner when the payload omits owner fields")
	void putKeepsOwnerWhenPayloadOmitsOwnerFields() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness client = seedClient("local-" + run, "owner-" + run);

		String body = """
				{"name": "Sólo nombre %s"}
				""".formatted(run);

		mockMvc.perform(put("/api/platform/clients/" + client.businessId())
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerUsername").value("owner-" + run))
				.andExpect(jsonPath("$.ownerEmail").value("owner-" + run + "@test.dev"));
	}

	@Test
	@DisplayName("unknown id answers 404")
	void unknownIdAnswersNotFound() throws Exception {

		String body = """
				{"name": "Nadie %s"}
				""".formatted(run);

		mockMvc.perform(put("/api/platform/clients/999999999")
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("owner email colliding with a DIFFERENT user answers 409 and changes nothing")
	void collidingOwnerEmailAnswersConflict() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness client = seedClient("local-" + run, "owner-" + run);

		// A DIFFERENT user holds the email the payload wants.
		String takenEmail = "taken-" + run + "@test.dev";
		seedUserWithEmail("otro-" + run, takenEmail);

		String body = """
				{"ownerEmail": "%s"}
				""".formatted(takenEmail);

		mockMvc.perform(put("/api/platform/clients/" + client.businessId())
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe un usuario con ese email"));

		// Nothing moved: the client keeps its own email.
		assertThat(businessRepository.findById(client.businessId()).orElseThrow().getName())
				.isEqualTo("Business local-" + run);
	}

	@Test
	@DisplayName("the client's OWN current email is accepted (no self-collision)")
	void ownEmailIsAccepted() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness client = seedClient("local-" + run, "owner-" + run);

		String body = """
				{"ownerEmail": "owner-%s@test.dev"}
				""".formatted(run);

		mockMvc.perform(put("/api/platform/clients/" + client.businessId())
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("malformed phone answers 400")
	void malformedPhoneAnswersValidation() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness client = seedClient("local-" + run, "owner-" + run);

		String body = """
				{"phone": "15-4444"}
				""".formatted(run);

		mockMvc.perform(put("/api/platform/clients/" + client.businessId())
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El teléfono debe ser código de área y número, sin 0 ni 15"));
	}

	@Test
	@DisplayName("PUT with active=true reactivates a deactivated client")
	void putReactivatesDeactivatedClient() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness client = seedClient("local-" + run, "owner-" + run);

		// Deactivated directly in the fixture (the DELETE route lands in its
		// own task): the reactivation contract must not depend on it.
		Business business = businessRepository.findById(client.businessId()).orElseThrow();
		business.setActive(Boolean.FALSE);
		businessRepository.saveAndFlush(business);

		String body = """
				{"active": true}
				""".formatted();

		mockMvc.perform(put("/api/platform/clients/" + client.businessId())
						.header("Authorization", "Bearer " + platformAdminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));

		assertThat(businessRepository.findById(client.businessId()).orElseThrow().getActive()).isTrue();
	}

	@Test
	@DisplayName("business OWNER caller of the edit gets 403")
	void ownerCallerIsForbidden() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness owner = BusinessAuthSeed.seedOwner(
				businessRepository, userRepository, roleRepository, businessUserRepository,
				jwtUtil, OWNER_BUSINESS_ID, "edit-owner-" + run, "edit-owner-" + run);

		mockMvc.perform(put("/api/platform/clients/" + owner.businessId())
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isForbidden());
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	/**
	 * Seeds a full client through the SHARED seed (business + owner +
	 * membership), then stamps the owner's email — the shared fixture does
	 * not set emails and the edit contract needs one.
	 *
	 * @return the seeded client with its businessId
	 */
	private BusinessAuthSeed.AuthenticatedBusiness seedClient(String slug, String username) {

		BusinessAuthSeed.AuthenticatedBusiness client = BusinessAuthSeed.seedOwner(
				businessRepository, userRepository, roleRepository, businessUserRepository,
				jwtUtil, 987_904_200L + (slug.hashCode() & 0xff), slug, username);

		User owner = userRepository.findByUsername(username).orElseThrow();
		owner.setEmail(username + "@test.dev");
		userRepository.saveAndFlush(owner);

		return client;
	}

	/** Seeds a standalone user WITH an email (for collision tests). */
	private void seedUserWithEmail(String username, String email) {

		Role role = roleRepository.findByName("KITCHEN")
				.orElseGet(() -> {
					Role created = new Role();
					created.setName("KITCHEN");
					return roleRepository.saveAndFlush(created);
				});

		User user = new User();
		user.setUsername(username);
		user.setEmail(email);
		user.setPassword(passwordEncoder.encode("test-password"));
		user.setRoles(java.util.Set.of(role));
		userRepository.saveAndFlush(user);
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
