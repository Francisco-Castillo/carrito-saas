package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessUser;
import com.carrito.saas.repository.entity.Role;
import com.carrito.saas.repository.entity.User;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * Status contract of the OWNER/PANEL path (task 3 of the HTTP error
 * contract): client-caused failures behind the login and the panel services
 * must answer honest 4xx through {@code BusinessException} +
 * {@code ErrorMapper}, not the catch-all's 500
 * {@code "Ocurrió un error inesperado"}.
 *
 * <p>The login is the most visible case: {@code frontend/src/pages/Login.tsx}
 * renders the backend's {@code message} verbatim, so a mistyped password used
 * to show the customer "Ocurrió un error inesperado" and left a stack trace
 * at ERROR level on every failed attempt. Wrong password and unknown username
 * must both answer 401 with the SAME neutral message (no username
 * enumeration); valid credentials for a business the user does not belong to
 * answer 403 (they already proved they hold the password, so naming the
 * reason leaks nothing); a blank or missing {@code restaurantSlug} is a 400,
 * not a server fault.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy} (Spring Boot 4 moved
 * {@code @AutoConfigureMockMvc} out of {@code spring-boot-starter-test}).
 * {@code @Transactional} rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class PanelErrorStatusHttpTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_809_301L;

	private static final Long OTHER_BUSINESS_ID = 987_809_302L;

	private static final String SLUG = "panel-error-status-test-business";

	private static final String OTHER_SLUG = "panel-error-status-other-business";

	private static final String USERNAME = "panel-error-status-owner";

	private static final String PASSWORD = "panel-error-status-password";

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

	@Autowired
	private JwtUtil jwtUtil;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		seedBusinessWithOwner(BUSINESS_ID, SLUG);
		seedBusiness(OTHER_BUSINESS_ID, OTHER_SLUG);
	}

	private void seedBusiness(long id, String slug) {

		Business business = new Business();
		business.setId(id);
		business.setName("Business " + slug);
		business.setSlug(slug);
		businessRepository.saveAndFlush(business);
	}

	/**
	 * Seeds the owner with a REAL encoded password so the login probes can
	 * exercise the actual {@code AuthenticationManager} path.
	 */
	private void seedBusinessWithOwner(long id, String slug) {

		seedBusiness(id, slug);

		Role role = new Role();
		role.setName("OWNER");
		role = roleRepository.saveAndFlush(role);

		User user = new User();
		user.setUsername(USERNAME);
		user.setPassword(passwordEncoder.encode(PASSWORD));
		user.setRoles(java.util.Set.of(role));
		user = userRepository.saveAndFlush(user);

		BusinessUser businessUser = new BusinessUser();
		businessUser.setUser(user);
		businessUser.setBusiness(businessRepository.findById(id).orElseThrow());
		businessUser.setRole(role);
		businessUserRepository.saveAndFlush(businessUser);
	}

	private String ownerToken() {
		return "Bearer " + jwtUtil.generateToken(USERNAME, BUSINESS_ID);
	}

	// ==================================================================
	// Login: wrong password / unknown username → 401, same message
	// ==================================================================

	@Test
	void loginWithWrongPasswordReturns401WithNeutralMessage() throws Exception {

		String body = """
				{
				  "username": "%s",
				  "password": "contrasena-equivocada",
				  "restaurantSlug": "%s"
				}
				""".formatted(USERNAME, SLUG);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.message").value("Usuario o contraseña incorrectos"));
	}

	@Test
	void loginWithUnknownUsernameReturns401WithSameMessage() throws Exception {

		String wrongPasswordBody = """
				{
				  "username": "%s",
				  "password": "contrasena-equivocada",
				  "restaurantSlug": "%s"
				}
				""".formatted(USERNAME, SLUG);

		String unknownUserBody = """
				{
				  "username": "no-existe-este-usuario",
				  "password": "contrasena-equivocada",
				  "restaurantSlug": "%s"
				}
				""".formatted(SLUG);

		String wrongPasswordMessage = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(wrongPasswordBody))
				.andExpect(status().isUnauthorized())
				.andReturn()
				.getResponse()
				.getContentAsString();

		// No-enumeration pinned: same status AND same message, so the response
		// reveals nothing about whether the username exists.
		String wrongPasswordBodyMessage =
				(String) com.jayway.jsonpath.JsonPath.read(wrongPasswordMessage, "$.message");

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(unknownUserBody))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(wrongPasswordBodyMessage));
	}

	// ==================================================================
	// Login: valid credentials for a business the user does not belong to
	// ==================================================================

	@Test
	void loginWithValidCredentialsForForeignBusinessReturns403() throws Exception {

		String body = """
				{
				  "username": "%s",
				  "password": "%s",
				  "restaurantSlug": "%s"
				}
				""".formatted(USERNAME, PASSWORD, OTHER_SLUG);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.message").value("El usuario no pertenece a este negocio"));
	}

	// ==================================================================
	// Login: blank / missing restaurantSlug → 400
	// ==================================================================

	@Test
	void loginWithBlankRestaurantSlugReturns400() throws Exception {

		String body = """
				{
				  "username": "%s",
				  "password": "%s",
				  "restaurantSlug": ""
				}
				""".formatted(USERNAME, PASSWORD);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void loginWithMissingRestaurantSlugReturns400() throws Exception {

		String body = """
				{
				  "username": "%s",
				  "password": "%s"
				}
				""".formatted(USERNAME, PASSWORD);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest());
	}

	// ==================================================================
	// Login regression: valid credentials still succeed
	// ==================================================================

	@Test
	void validLoginStillSucceeds() throws Exception {

		String body = """
				{
				  "username": "%s",
				  "password": "%s",
				  "restaurantSlug": "%s"
				}
				""".formatted(USERNAME, PASSWORD, SLUG);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andExpect(jsonPath("$.username").value(USERNAME))
				.andExpect(jsonPath("$.businessId").value(BUSINESS_ID));
	}

	// ==================================================================
	// Categories: unknown id on GET / PUT / ordenar → 404, four-key body
	// ==================================================================

	@Test
	void unknownCategoryIdOnGetReturns404WithFourKeyBody() throws Exception {

		mockMvc.perform(get("/api/categorias/999999999")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").value("Categoría no encontrada"))
				.andExpect(jsonPath("$.path").value("/api/categorias/999999999"));
	}

	@Test
	void unknownCategoryIdOnPutReturns404WithFourKeyBody() throws Exception {

		mockMvc.perform(put("/api/categorias/999999999")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "name": "Categoria Inexistente" }
								"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").value("Categoría no encontrada"))
				.andExpect(jsonPath("$.path").value("/api/categorias/999999999"));
	}

	@Test
	void unknownCategoryIdOnOrdenarReturns404() throws Exception {

		mockMvc.perform(put("/api/categorias/orden")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("[999999999]"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Categoría no encontrada"));
	}

	// ==================================================================
	// Products: unknown id on PUT → 404 (no GET-by-id route exists)
	// ==================================================================

	@Test
	void unknownProductIdOnActivarReturns404WithFourKeyBody() throws Exception {

		mockMvc.perform(put("/api/products/999999999/activar?activo=true")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").value("Producto no encontrado"))
				.andExpect(jsonPath("$.path").value("/api/products/999999999/activar"));
	}

	// ==================================================================
	// Tables: unknown id → 404 (reachable via the QR GET endpoints; the
	// wired update/delete/findAll/findById share the same findOwnedTable
	// guard, covered in depth by TableEndpointsHttpTests)
	// ==================================================================

	@Test
	void unknownTableIdOnQrReturns404WithFourKeyBody() throws Exception {

		mockMvc.perform(get("/api/tables/999999999/qr")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").value("Mesa no encontrada"))
				.andExpect(jsonPath("$.path").value("/api/tables/999999999/qr"));
	}

	@Test
	void unknownTableIdOnQrPdfReturns404() throws Exception {

		mockMvc.perform(get("/api/tables/999999999/qr/pdf")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Mesa no encontrada"));
	}

	// ==================================================================
	// Duplicate username → 409 (UserServiceImpl.createUser)
	// ==================================================================

	@Test
	void duplicateUsernameOnCreateUserReturns409() throws Exception {

		String body = """
				{
				  "username": "%s",
				  "password": "otra-contrasena",
				  "role": "OWNER"
				}
				""".formatted(USERNAME);

		mockMvc.perform(post("/api/restaurants/" + SLUG + "/users")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value("Ya existe un usuario con ese nombre"));
	}

	// ==================================================================
	// Dashboard / Analytics / Business slug: unknown slug → 404
	// ==================================================================

	@Test
	void unknownSlugOnDashboardTodayReturns404() throws Exception {

		mockMvc.perform(get("/api/restaurants/no-such-panel-slug/dashboard/today")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Local no encontrado"));
	}

	@Test
	void unknownSlugOnOrderStatusSummaryReturns404() throws Exception {

		mockMvc.perform(get("/api/restaurants/no-such-panel-slug/orders/status-summary")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Local no encontrado"));
	}

	@Test
	void unknownSlugOnAnalyticsTopProductsReturns404() throws Exception {

		mockMvc.perform(get("/api/restaurants/no-such-panel-slug/analytics/top-products")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Local no encontrado"));
	}

	@Test
	void unknownSlugOnAnalyticsSalesByHourReturns404() throws Exception {

		mockMvc.perform(get("/api/restaurants/no-such-panel-slug/analytics/sales-by-hour")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Local no encontrado"));
	}

	// ==================================================================
	// Business slug: solved at the HTTP level. GET /api/business/slug/{slug}
	// used to be a genuine server fault — an NPE, hence a 500 for EVERY slug,
	// valid or not — because BusinessController declared iBusinessService and
	// never injected it. With the constructor fixed the endpoint answers 200
	// for a real slug and 404 for an unknown one.
	// ==================================================================

	@Test
	void businessBySlugReturns200ForAuthenticatedCaller() throws Exception {

		mockMvc.perform(get("/api/business/slug/" + SLUG)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.slug").value(SLUG));
	}

	@Test
	void unknownSlugOnBusinessBySlugReturns404WithFourKeyBody() throws Exception {

		mockMvc.perform(get("/api/business/slug/no-such-panel-slug")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").value("Local no encontrado"))
				.andExpect(jsonPath("$.path").value("/api/business/slug/no-such-panel-slug"));
	}

}
