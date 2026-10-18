package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;

/**
 * Brand color ({@code primaryColor}) contract, end to end.
 *
 * <p>The owner sets the brand color on the storefront profile
 * ({@code PUT /api/business/profile}); the public menu payload
 * ({@code GET /api/menu} and the legacy slug variant) carries it under
 * {@code business.primaryColor} so the carta can theme itself with the
 * local's color.</p>
 *
 * <p>Rules pinned here (same partial-update semantics as the other text
 * profile fields, messages in Spanish, 400 on invalid input): a valid
 * {@code #RRGGBB} hex color saves; an invalid one is rejected with 400 and
 * NOTHING is stored; blank clears (null in the database); absent (null in
 * the DTO) keeps the stored value — a PUT that does not mention the color
 * never erases it.</p>
 *
 * <p>MockMvc is assembled manually with the real security
 * {@link FilterChainProxy} (same reason as
 * {@link BusinessProfileAndStorefrontTests}); {@code @Transactional} rolls
 * back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class PrimaryColorTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_802_101L;

	private static final String SLUG = "primary-color-business";

	private static final String OWNER_USERNAME = "primary-color-owner";

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
	private com.carrito.saas.security.JwtUtil jwtUtil;

	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		// The slugless menu contract requires EXACTLY ONE business in the
		// deployment; the shared dev database keeps seed rows from other
		// sessions. TRUNCATE ... CASCADE clears every table referencing
		// businesses in one transactional statement (Postgres TRUNCATE is
		// transactional, so the rollback restores everything) and the fixture
		// re-seeds its own single business right after.
		entityManager.createNativeQuery("TRUNCATE TABLE businesses CASCADE").executeUpdate();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);
	}

	// ------------------------------------------------------------------
	// Owner profile: PUT + GET
	// ------------------------------------------------------------------

	@Test
	void putProfileSavesValidHexColorAndGetReturnsIt() throws Exception {

		String body = """
				{ "name": "Negocio Color", "primaryColor": "#7C3AED" }
				""";

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.primaryColor").value("#7C3AED"));

		// GET round trip: the stored color comes back.
		mockMvc.perform(get("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.primaryColor").value("#7C3AED"));

		assertThat(businessRepository.findById(BUSINESS_ID).orElseThrow().getPrimaryColor())
				.isEqualTo("#7C3AED");
	}

	@Test
	void putProfileRejectsInvalidColorWith400AndStoresNothing() throws Exception {

		String body = """
				{ "name": "Negocio Color", "primaryColor": "violeta" }
				""";

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest());

		// Rejected input leaves stored data unchanged: still null.
		assertThat(businessRepository.findById(BUSINESS_ID).orElseThrow().getPrimaryColor())
				.isNull();
	}

	@Test
	void putProfileBlankClearsAndAbsentKeepsStoredColor() throws Exception {

		seedColor("#7C3AED");

		// Blank clears (null in the database), like the other text fields.
		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "name": "Negocio Color", "primaryColor": "" }
								"""))
				.andExpect(status().isOk());

		assertThat(businessRepository.findById(BUSINESS_ID).orElseThrow().getPrimaryColor())
				.isNull();

		seedColor("#7C3AED");

		// Absent (null) means "do not touch": a partial PUT never erases it.
		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "name": "Negocio Color" }
								"""))
				.andExpect(status().isOk());

		assertThat(businessRepository.findById(BUSINESS_ID).orElseThrow().getPrimaryColor())
				.isEqualTo("#7C3AED");
	}

	// ------------------------------------------------------------------
	// Public menu payload
	// ------------------------------------------------------------------

	@Test
	void publicMenuCarriesBusinessPrimaryColor() throws Exception {

		seedColor("#7C3AED");

		// Slugless menu (single-business deployment contract).
		mockMvc.perform(get("/api/menu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.primaryColor").value("#7C3AED"));

		// Legacy slug menu.
		mockMvc.perform(get("/api/menu/" + SLUG))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.primaryColor").value("#7C3AED"));
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private void seedColor(String color) {

		var business = businessRepository.findById(BUSINESS_ID).orElseThrow();
		business.setPrimaryColor(color);
		businessRepository.saveAndFlush(business);
	}
}
