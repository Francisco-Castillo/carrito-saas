package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.service.interfaces.IQrCodeService;

/**
 * Honest-failure contract of the per-business QR endpoint.
 *
 * <p>{@code ZxingQrCodeServiceImpl} catches EVERY generation exception and
 * returns {@code null} instead of throwing. The endpoint must translate that
 * {@code null} into a clear failure status: a 200 with an empty body hides the
 * outage from the owner printing the QR, and an unguarded {@code null} would
 * only surface as an NPE. Here {@link IQrCodeService} is mocked to return
 * {@code null}, which is exactly what the real implementation produces when
 * encoding fails.</p>
 */
@SpringBootTest
@Transactional
class BusinessQrGenerationFailureTests {

	private static final Long BUSINESS_ID = 987_654_320L;

	private static final String SLUG = "qr-generation-failure-business";

	private static final String USERNAME = "qr-generation-failure-owner";

	private static final Long MISSING_BUSINESS_ID = 987_654_999L;

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

	@MockitoBean
	private IQrCodeService qrCodeService;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, USERNAME);
	}

	@Test
	void nullGenerationFailsHonestlyInsteadOfEmptyBodyOrNpe() throws Exception {

		when(qrCodeService.generateQr(anyString(), any())).thenReturn(null);

		byte[] body = mockMvc.perform(get("/api/business/qr")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(result -> assertThat(result.getResponse().getStatus())
						.as("a null QR generation must fail with a clear status, not 200 (actual: %d)",
								result.getResponse().getStatus())
						.isEqualTo(503))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		assertThat(body)
				.as("the failure must be reported to the caller, not returned as an empty 200")
				.isNotEmpty();
	}

	@Test
	void tokenForMissingBusinessFailsWithNotFound() throws Exception {

		// The user exists (so the JWT filter authenticates), but the token's
		// businessId has no business row: the endpoint must answer 404 instead
		// of letting the missing lookup become an NPE.
		String token = jwtUtil.generateToken(USERNAME, MISSING_BUSINESS_ID);

		mockMvc.perform(get("/api/business/qr")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(result -> assertThat(result.getResponse().getStatus())
						.as("a JWT pointing at a missing business must fail clearly (actual: %d)",
								result.getResponse().getStatus())
						.isEqualTo(404));
	}
}
