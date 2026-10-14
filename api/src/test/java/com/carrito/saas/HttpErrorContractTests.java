package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;

import com.carrito.saas.exception.ErrorResponse;
import com.carrito.saas.exception.GlobalExceptionHandler;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * HTTP error contract of {@link com.carrito.saas.exception.GlobalExceptionHandler}.
 *
 * <p>Spring MVC's own exceptions (404 {@code NoResourceFoundException},
 * 405 {@code HttpRequestMethodNotSupportedException}, 400
 * {@code HttpMessageNotReadableException}, 415
 * {@code HttpMediaTypeNotSupportedException}) already carry the right status,
 * but the catch-all {@code @ExceptionHandler(Exception.class)} used to swallow
 * them into a 500 — the API lied about client errors. After the fix they keep
 * their own status while the response body keeps the
 * {@code {status, error, message, path}} shape every client parses.</p>
 *
 * <p>Regression guards pinned here too: {@code MethodArgumentNotValidException}
 * still answers 400 with the {@code <field> <message>} text,
 * {@code BusinessException} still maps through {@code ErrorMapper}, and the
 * catch-all still answers 500 with the generic message for a genuinely
 * unexpected failure (exercised by invoking {@code handleGeneral} directly:
 * the plain-{@code RuntimeException} slug endpoint that used to be the
 * trigger now answers an honest 404).</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy}: Spring Boot 4 moved {@code @AutoConfigureMockMvc}
 * into the separate {@code spring-boot-webmvc-test} module, which
 * {@code spring-boot-starter-test} does not bring. {@code @Transactional}
 * rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class HttpErrorContractTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_809_101L;

	private static final String SLUG = "http-error-contract-owner";

	private static final String OWNER_USERNAME = "http-error-contract-owner-user";

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
	private GlobalExceptionHandler globalExceptionHandler;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);
	}

	/**
	 * A path with no controller and no static resource under the public menu
	 * must answer 404 (not the catch-all's 500), and the body must keep the
	 * four keys every client parses: {@code status}, {@code error},
	 * {@code message}, {@code path}.
	 */
	@Test
	void noHandlerOnMenuPathReturns404WithFourKeyBody() throws Exception {

		mockMvc.perform(get("/api/menu/demo-e2e/x/y"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.path").value("/api/menu/demo-e2e/x/y"));
	}

	/**
	 * DELETE against a GET-only public route must answer 405 (method not
	 * supported), not the catch-all's 500.
	 */
	@Test
	void deleteOnGetOnlyMenuRouteReturns405() throws Exception {

		mockMvc.perform(delete("/api/menu/demo-e2e"))
				.andExpect(status().isMethodNotAllowed());
	}

	/**
	 * Malformed JSON on the public order endpoint must answer 400 (message
	 * not readable), not the catch-all's 500.
	 */
	@Test
	void malformedJsonOnPublicOrderReturns400() throws Exception {

		mockMvc.perform(post("/api/orders/menu/demo-e2e")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ this is not json"))
				.andExpect(status().isBadRequest());
	}

	/**
	 * A {@code text/plain} body on the JSON-only public order endpoint must
	 * answer 415 (unsupported media type), not the catch-all's 500.
	 */
	@Test
	void textPlainContentTypeOnPublicOrderReturns415() throws Exception {

		mockMvc.perform(post("/api/orders/menu/demo-e2e")
						.contentType(MediaType.TEXT_PLAIN)
						.content("pedido"))
				.andExpect(status().isUnsupportedMediaType());
	}

	/**
	 * The catch-all keeps its purpose: a genuinely unexpected
	 * {@code RuntimeException} still answers 500 with the generic message.
	 *
	 * <p>The old trigger (an endpoint that threw a plain
	 * {@code RuntimeException} for an unknown slug) is gone: those public
	 * paths now answer honest 4xx via {@code BusinessException} (see
	 * {@code PublicPathErrorStatusHttpTests}), so the catch-all is exercised
	 * by invoking {@link GlobalExceptionHandler#handleGeneral} directly with a
	 * real unexpected failure — the stable way to pin its contract.</p>
	 */
	@Test
	void unexpectedRuntimeFailureStillReturns500WithGenericMessage() {

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/some-endpoint");

		ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleGeneral(
				new RuntimeException("fallo genuinamente inesperado"), request);

		org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
				.isEqualTo(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
		org.assertj.core.api.Assertions.assertThat(response.getBody()).isNotNull();
		org.assertj.core.api.Assertions.assertThat(response.getBody().getMessage())
				.isEqualTo("Ocurrió un error inesperado");
		org.assertj.core.api.Assertions.assertThat(response.getBody().getPath()).isEqualTo("/api/some-endpoint");
	}

	/**
	 * Regression: {@code MethodArgumentNotValidException} keeps its dedicated
	 * handler — 400 with the {@code <field> <message>} text — even though the
	 * exception also implements the Spring {@code ErrorResponse} interface
	 * covered by the new handler. The specific handler must win.
	 */
	@Test
	void beanValidationStillReturns400WithFieldMessage() throws Exception {

		mockMvc.perform(post("/api/tables")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": 1 }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("tableName must not be blank"));
	}

	/**
	 * Regression: {@code BusinessException} keeps mapping through
	 * {@code ErrorMapper} — an owner asking for another business's products
	 * gets the mapped 404, not the catch-all's 500 or the new handler's body.
	 */
	@Test
	void businessExceptionStillMapsThroughErrorMapper() throws Exception {

		Long otherBusinessId = BUSINESS_ID + 999_999;

		mockMvc.perform(get("/api/restaurants/" + otherBusinessId + "/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message")
						.value("Restaurante no encontrado: " + otherBusinessId));
	}

}
