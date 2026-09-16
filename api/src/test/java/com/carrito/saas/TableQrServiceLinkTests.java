package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

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
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/**
 * Contract for the QR LINK of a single restaurant table, on both surfaces that
 * expose it:
 *
 * <ul>
 * <li>{@code GET /api/tables/{id}/qr} — the printed PNG must encode the PUBLIC
 * menu URL of the business ({@code <base>/menu/index.html?restaurant=<slug>}),
 * never a bare {@code /<qrToken>} path and never an internal backend host.</li>
 * <li>{@code POST /api/tables} — the created {@code TableResponseDTO.qrUrl}
 * must carry that same public URL, because the phone camera is the only client
 * that ever follows it.</li>
 * </ul>
 *
 * <p>MockMvc is assembled manually with the real security {@link FilterChainProxy}
 * (Spring Boot 4 keeps {@code @AutoConfigureMockMvc} outside
 * {@code spring-boot-starter-test}); the authenticated calls go through the
 * real {@code JwtFilter} with a real {@link JwtUtil}-minted token.</p>
 */
@SpringBootTest
@Transactional
class TableQrServiceLinkTests {

	private static final Long BUSINESS_ID = 987_654_340L;

	private static final String SLUG = "table-qr-link-business";

	private static final String USERNAME = "table-qr-link-owner";

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
	private TableRepository tableRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@Autowired
	private IMenuUrlBuilder menuUrlBuilder;

	// The application context exposes no Jackson ObjectMapper bean, so the test
	// instantiates its own mapper (same convention as PublicMenuOrderHttpTests).
	private final ObjectMapper objectMapper = new ObjectMapper();

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
	void tableQrPngEncodesThePublicMenuUrl() throws Exception {

		JsonNode created = createTableThroughEndpoint(910001, "Mesa QA 1");
		long tableId = created.get("id").asLong();
		String qrToken = created.get("qrToken").asText();

		byte[] png = mockMvc.perform(get("/api/tables/{id}/qr", tableId)
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_PNG))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		String decoded = decodeQrText(png);

		assertThat(decoded)
				.as("the table QR PNG must encode menuUrlBuilder.buildMenuUrl(slug)")
				.isEqualTo(menuUrlBuilder.buildMenuUrl(SLUG));

		assertThat(decoded)
				.as("the table QR PNG must encode the suite's public menu URL literal")
				.isEqualTo("http://localhost:9090/menu/index.html?restaurant=" + SLUG);

		assertThat(decoded)
				.as("the table QR PNG must never point at the abandoned frontend port 3030")
				.doesNotContain("localhost:3030");

		assertThat(decoded)
				.as("the table QR PNG must never point at the internal backend port 8080")
				.doesNotContain("localhost:8080");

		assertThat(decoded)
				.as("the table QR PNG must not be the bare token path /<qrToken>")
				.isNotEqualTo("/" + qrToken);
	}

	@Test
	void createdTableReturnsThePublicMenuUrlAsQrUrl() throws Exception {

		JsonNode created = createTableThroughEndpoint(910002, "Mesa QA 2");
		String qrUrl = created.get("qrUrl").asText();
		String qrToken = created.get("qrToken").asText();

		assertThat(qrUrl)
				.as("the created table's qrUrl must equal menuUrlBuilder.buildMenuUrl(slug)")
				.isEqualTo(menuUrlBuilder.buildMenuUrl(SLUG));

		assertThat(qrUrl)
				.as("the created table's qrUrl must equal the suite's public menu URL literal")
				.isEqualTo("http://localhost:9090/menu/index.html?restaurant=" + SLUG);

		assertThat(qrUrl)
				.as("the created table's qrUrl must never point at the abandoned frontend port 3030")
				.doesNotContain("localhost:3030");

		assertThat(qrUrl)
				.as("the created table's qrUrl must never point at the internal backend port 8080")
				.doesNotContain("localhost:8080");

		assertThat(qrUrl)
				.as("the created table's qrUrl must not be the bare token path /<qrToken>")
				.isNotEqualTo("/" + qrToken);
	}

	private JsonNode createTableThroughEndpoint(int tableNumber, String tableName) throws Exception {

		String body = mockMvc.perform(post("/api/tables")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"tableNumber\":" + tableNumber + ",\"tableName\":\"" + tableName + "\"}"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		return objectMapper.readTree(body);
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
