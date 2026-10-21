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
 * Menu font ({@code menuFont}) contract, end to end.
 *
 * <p>The owner picks the carta typography from the "Personalización" tab
 * ({@code PUT /api/business/profile}); the public menu payload
 * ({@code GET /api/menu} and the legacy slug variant) carries it under
 * {@code business.menuFont} so the carta can load the font on demand.</p>
 *
 * <p>Rules pinned here (mirrors {@link PrimaryColorTests}): a slug from the
 * curated whitelist saves (case-insensitively — the stored value is always
 * lowercase); an unknown slug is rejected with 400 and NOTHING is stored
 * (the owner cannot inject an arbitrary font); blank clears (null in the
 * database); absent (null in the DTO) keeps the stored value.</p>
 *
 * <p>MockMvc is assembled manually with the real security
 * {@link FilterChainProxy} (same reason as {@link PrimaryColorTests});
 * {@code @Transactional} rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class MenuFontTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_802_102L;

	private static final String SLUG = "menu-font-business";

	private static final String OWNER_USERNAME = "menu-font-owner";

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

		// Same single-business isolation as PrimaryColorTests: the slugless
		// menu contract requires EXACTLY ONE business in the deployment.
		SingleBusinessTestIsolation.keepOnlyBusiness(entityManager, BUSINESS_ID);

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);
	}

	// ------------------------------------------------------------------
	// Owner profile: PUT + GET
	// ------------------------------------------------------------------

	@Test
	void putProfileSavesWhitelistedFontAndGetReturnsIt() throws Exception {

		String body = """
				{ "name": "Negocio Fuente", "menuFont": "poppins" }
				""";

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.menuFont").value("poppins"));

		// GET round trip: the stored font comes back.
		mockMvc.perform(get("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.menuFont").value("poppins"));

		assertThat(businessRepository.findById(BUSINESS_ID).orElseThrow().getMenuFont())
				.isEqualTo("poppins");
	}

	@Test
	void putProfileNormalizesCaseBeforeWhitelistCheckAndStorage() throws Exception {

		// The picker/UI may send any casing: the whitelist compares and stores
		// lowercase, so the carta always receives a canonical slug.
		String body = """
				{ "name": "Negocio Fuente", "menuFont": "Playfair-Display" }
				""";

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());

		assertThat(businessRepository.findById(BUSINESS_ID).orElseThrow().getMenuFont())
				.isEqualTo("playfair-display");
	}

	@Test
	void putProfileRejectsUnknownFontWith400AndStoresNothing() throws Exception {

		String body = """
				{ "name": "Negocio Fuente", "menuFont": "comic-sans" }
				""";

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest());

		// Rejected input leaves stored data unchanged: still null. The owner
		// cannot inject a font outside the curated whitelist.
		assertThat(businessRepository.findById(BUSINESS_ID).orElseThrow().getMenuFont())
				.isNull();
	}

	@Test
	void putProfileBlankClearsAndAbsentKeepsStoredFont() throws Exception {

		seedFont("poppins");

		// Blank clears (null in the database): the carta falls back to Inter.
		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "name": "Negocio Fuente", "menuFont": "" }
								"""))
				.andExpect(status().isOk());

		assertThat(businessRepository.findById(BUSINESS_ID).orElseThrow().getMenuFont())
				.isNull();

		seedFont("poppins");

		// Absent (null) means "do not touch": a partial PUT never erases it.
		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "name": "Negocio Fuente" }
								"""))
				.andExpect(status().isOk());

		assertThat(businessRepository.findById(BUSINESS_ID).orElseThrow().getMenuFont())
				.isEqualTo("poppins");
	}

	// ------------------------------------------------------------------
	// Public menu payload
	// ------------------------------------------------------------------

	@Test
	void publicMenuCarriesBusinessMenuFont() throws Exception {

		seedFont("playfair-display");

		// Slugless menu (single-business deployment contract).
		mockMvc.perform(get("/api/menu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.menuFont").value("playfair-display"));

		// Legacy slug menu.
		mockMvc.perform(get("/api/menu/" + SLUG))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.menuFont").value("playfair-display"));
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private void seedFont(String font) {

		var business = businessRepository.findById(BUSINESS_ID).orElseThrow();
		business.setMenuFont(font);
		businessRepository.saveAndFlush(business);
	}
}
