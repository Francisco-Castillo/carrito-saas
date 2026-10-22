package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Contract of {@code DELETE /api/platform/clients/{id}}: the LOGICAL
 * deactivation of a client (S3 of odd/tasks/plataforma-editar-baja.md).
 *
 * <p>Rules under test:</p>
 *
 * <ul>
 * <li>200 with the deactivated client: the ROW SURVIVES (active=false) —
 * physical DELETE is forbidden; the owner account and the membership stay
 * untouched (history intact; reactivation owns the way back).</li>
 * <li>Idempotent: deactivating an already-deactivated client answers 200
 * without changes; 404 only for an unknown id.</li>
 * <li>403 for a business caller (OWNER) — the barrier is owned by
 * {@code PlatformPrivilegeHttpTests}; here it guards the WRITE route.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class PlatformClientDeactivateHttpTests {

	private static final Long OWNER_BUSINESS_ID = 987_904_301L;

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

		run = "de" + System.nanoTime();
	}

	@Test
	@DisplayName("DELETE deactivates the row (survives with active=false) and keeps the owner")
	void deleteDeactivatesAndKeepsRows() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness client = seedClient("local-" + run, "owner-" + run);

		mockMvc.perform(delete("/api/platform/clients/" + client.businessId())
						.header("Authorization", "Bearer " + platformAdminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.businessId").isNumber());

		// The row SURVIVES — logical, not physical: the business, the owner
		// user and the membership all stay (history intact, reactivation
		// owns the way back).
		Business business = businessRepository.findById(client.businessId()).orElseThrow();
		assertThat(business.getActive()).isFalse();
		assertThat(businessRepository.findBySlug("local-" + run)).isPresent();
		assertThat(businessUserRepository.findByUserUsername("owner-" + run)).isNotEmpty();
		assertThat(userRepository.findByUsername("owner-" + run)).isPresent();
	}

	@Test
	@DisplayName("deactivating an already-deactivated client is idempotent (200, no changes)")
	void deleteIsIdempotent() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness client = seedClient("local-" + run, "owner-" + run);

		Business business = businessRepository.findById(client.businessId()).orElseThrow();
		business.setActive(Boolean.FALSE);
		businessRepository.saveAndFlush(business);

		mockMvc.perform(delete("/api/platform/clients/" + client.businessId())
						.header("Authorization", "Bearer " + platformAdminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		assertThat(businessRepository.findById(client.businessId()).orElseThrow().getActive()).isFalse();
	}

	@Test
	@DisplayName("unknown id answers 404")
	void unknownIdAnswersNotFound() throws Exception {

		mockMvc.perform(delete("/api/platform/clients/999999999")
						.header("Authorization", "Bearer " + platformAdminToken()))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("business OWNER caller of the deactivation gets 403")
	void ownerCallerIsForbidden() throws Exception {

		BusinessAuthSeed.AuthenticatedBusiness owner = BusinessAuthSeed.seedOwner(
				businessRepository, userRepository, roleRepository, businessUserRepository,
				jwtUtil, OWNER_BUSINESS_ID, "deactivate-owner-" + run, "deactivate-owner-" + run);

		mockMvc.perform(delete("/api/platform/clients/" + owner.businessId())
						.header("Authorization", owner.authorizationHeader()))
				.andExpect(status().isForbidden());
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	/**
	 * Seeds a full client through the SHARED seed (business + owner +
	 * membership), then stamps the owner's email — the shared fixture does
	 * not set emails and the panel contract needs one.
	 */
	private BusinessAuthSeed.AuthenticatedBusiness seedClient(String slug, String username) {

		BusinessAuthSeed.AuthenticatedBusiness client = BusinessAuthSeed.seedOwner(
				businessRepository, userRepository, roleRepository, businessUserRepository,
				jwtUtil, 987_904_400L + (slug.hashCode() & 0xff), slug, username);

		User owner = userRepository.findByUsername(username).orElseThrow();
		owner.setEmail(username + "@test.dev");
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
