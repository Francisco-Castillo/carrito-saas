package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import org.assertj.core.api.Assertions;
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
import com.carrito.saas.security.JwtUtil;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/**
 * Contract for the per-business QR entry point under
 * {@code BusinessController}'s {@code /api/business} mapping.
 *
 * <p>The QR is the owner's print material for the pickup/delivery channel, so
 * it must encode EXACTLY the URL the anonymous public menu expects:
 * {@code <base>/menu/index.html?restaurant=<slug>}. The business identity must
 * come from the JWT (via {@code ISecurityService.getCurrentBusinessId()}); no
 * request parameter may ever select the business, otherwise one owner could
 * fetch or poison another's QR. The returned body must be a real PNG — verified
 * through the {@code 89 50 4E 47} magic bytes AND by decoding the QR payload
 * back out of the image.</p>
 *
 * <p>MockMvc is assembled manually with the real security {@link FilterChainProxy}
 * (Spring Boot 4 keeps {@code @AutoConfigureMockMvc} outside
 * {@code spring-boot-starter-test}); the authenticated calls go through the
 * real {@code JwtFilter} with a real {@link JwtUtil}-minted token.</p>
 */
@SpringBootTest
@Transactional
class BusinessQrEndpointTests {

	private static final Long OWNER_BUSINESS_ID = 987_654_310L;

	private static final Long OTHER_BUSINESS_ID = 987_654_311L;

	private static final String OWNER_SLUG = "qr-endpoint-owner-business";

	private static final String OTHER_SLUG = "qr-endpoint-other-business";

	private static final String OWNER_USERNAME = "qr-endpoint-owner";

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

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, OWNER_BUSINESS_ID, OWNER_SLUG, OWNER_USERNAME);

		// A second business that the owner must NEVER be able to reach.
		BusinessAuthSeed.seedBusiness(businessRepository, OTHER_BUSINESS_ID, OTHER_SLUG);
	}

	@Test
	void authenticatedOwnerReceivesRealPng() throws Exception {

		byte[] body = mockMvc.perform(get("/api/business/qr")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_PNG))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		assertThat(body.length)
				.as("the QR must be a real image, not an empty body")
				.isGreaterThan(100);

		assertThat(new int[] { body[0] & 0xFF, body[1], body[2], body[3] })
				.as("the body must start with the PNG magic bytes 89 50 4E 47")
				.containsExactly(0x89, 0x50, 0x4E, 0x47);
	}

	@Test
	void qrEncodesTheAuthenticatedBusinessAndIgnoresClientParameters() throws Exception {

		// The request carries a second business's id and slug; the endpoint has
		// no parameter for either, so both must be ignored.
		byte[] body = mockMvc.perform(get("/api/business/qr")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.param("businessId", String.valueOf(OTHER_BUSINESS_ID))
						.param("slug", OTHER_SLUG))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		String decoded = decodeQrText(body);

		assertThat(decoded)
				.as("the QR must encode the authenticated owner's public menu URL, "
						+ "not any client-supplied business identity")
				.isEqualTo("http://localhost:9090/menu/index.html?restaurant=" + OWNER_SLUG);
	}

	@Test
	void anonymousRequestIsRejected() throws Exception {

		mockMvc.perform(get("/api/business/qr"))
				.andExpect(result -> Assertions.assertThat(result.getResponse().getStatus())
						.as("GET /api/business/qr must stay authenticated (actual: %d)",
								result.getResponse().getStatus())
						.isIn(401, 403));
	}

	private String decodeQrText(byte[] png) {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
			return new QRCodeReader()
					.decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))))
					.getText();
		}
		catch (Exception ex) {
			throw new AssertionError("the returned PNG did not decode as a QR code", ex);
		}
	}
}
