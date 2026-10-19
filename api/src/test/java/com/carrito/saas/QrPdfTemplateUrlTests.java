package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.QrTemplate;
import com.carrito.saas.repository.enums.TableStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.service.impl.ZxingQrCodeServiceImpl;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;
import com.carrito.saas.service.interfaces.IQrCodeService;
import com.carrito.saas.service.strategies.impl.PdfTemplateFactory;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

/**
 * The URL every per-table QR PDF template hands to the QR encoder (and, when
 * {@code showUrl=true}, prints as text) must be the configured PUBLIC menu URL
 * {@code <base>/menu/index.html?restaurant=<slug>}, never the dead
 * {@code http://localhost:3030/<qrToken>} path.
 *
 * <p>The {@code qr.public-menu-url} override points at a host that appears
 * nowhere in the production code, so a hardcoded URL can never satisfy these
 * assertions. The QR encoder is mocked to return a real, decodable PNG (built
 * with the project's own encoder) because {@code Image.getInstance(qr)}
 * rejects anything that is not a valid image.</p>
 *
 * <p>The strategies are reached through {@link PdfTemplateFactory} (Spring
 * wiring), not constructed directly, so the test observes exactly what the
 * production beans do. All tables of one business are pinned to share the
 * same public menu URL.</p>
 */
@SpringBootTest(properties = "qr.public-menu-url=https://qr.example.test/")
@Transactional
class QrPdfTemplateUrlTests {

	private static final Long BUSINESS_ID = 987_654_350L;

	private static final String SLUG = "qr-pdf-url-business";

	private static final String USERNAME = "qr-pdf-url-owner";

	private static final String EXPECTED_URL = "https://qr.example.test/menu/index.html?restaurant=" + SLUG;

	@Autowired
	private PdfTemplateFactory pdfTemplateFactory;

	@Autowired
	private IMenuUrlBuilder menuUrlBuilder;

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

	@MockitoBean
	private IQrCodeService qrCodeService;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		// A real, decodable PNG: Image.getInstance(qr) throws on anything else.
		when(qrCodeService.generateQr(anyString(), any()))
				.thenReturn(new ZxingQrCodeServiceImpl().generateQr("stub", 180));
	}

	@Test
	void singleTemplateHandsTheConfiguredPublicMenuUrlToTheQrEncoder() {

		pdfTemplateFactory.get(QrTemplate.SINGLE)
				.generate(List.of(inMemoryTable(1)), config(QrTemplate.SINGLE));

		String capturedUrl = capturedQrUrls(1).get(0);

		assertThat(capturedUrl)
				.as("the SINGLE template must hand menuUrlBuilder.buildMenuUrl(slug) to the QR encoder, not a hardcoded host (actual: %s)",
						capturedUrl)
				.isEqualTo(menuUrlBuilder.buildMenuUrl(SLUG));
		assertThat(capturedUrl)
				.as("the configured base https://qr.example.test must be used verbatim (actual: %s)", capturedUrl)
				.isEqualTo(EXPECTED_URL);
		assertThat(capturedUrl)
				.as("the dead localhost:3030 host must be gone from the QR content (actual: %s)", capturedUrl)
				.doesNotContain("localhost:3030");
	}

	@Test
	void grid3TemplateHandsTheConfiguredPublicMenuUrlToTheQrEncoder() {

		// GRID_3X3 lays cells out on a 3-column PdfPTable, and three tables
		// complete one row. Grid3Template now calls completeRow() so a partial
		// trailing row is no longer dropped, but this test keeps a complete row
		// so it isolates the URL contract from row rendering.
		pdfTemplateFactory.get(QrTemplate.GRID_3X3)
				.generate(List.of(inMemoryTable(1), inMemoryTable(2), inMemoryTable(3)), config(QrTemplate.GRID_3X3));

		List<String> capturedUrls = capturedQrUrls(3);

		assertThat(capturedUrls)
				.as("every table of one business must receive the same public menu URL, whatever its qrToken "
						+ "-- the accepted trade-off of dropping per-table identity (actual: %s)",
						capturedUrls)
				.containsExactly(menuUrlBuilder.buildMenuUrl(SLUG), menuUrlBuilder.buildMenuUrl(SLUG),
						menuUrlBuilder.buildMenuUrl(SLUG));
		assertThat(capturedUrls)
				.as("the configured base https://qr.example.test must be used verbatim (actual: %s)", capturedUrls)
				.containsOnly(EXPECTED_URL);
		assertThat(capturedUrls)
				.as("the dead localhost:3030 host must be gone from the QR content (actual: %s)", capturedUrls)
				.doesNotContain("localhost:3030");
	}

	@Test
	void acrylicTemplateHandsTheConfiguredPublicMenuUrlToTheQrEncoder() {

		pdfTemplateFactory.get(QrTemplate.ACRYLIC)
				.generate(List.of(inMemoryTable(1)), config(QrTemplate.ACRYLIC));

		String capturedUrl = capturedQrUrls(1).get(0);

		assertThat(capturedUrl)
				.as("the ACRYLIC template must hand menuUrlBuilder.buildMenuUrl(slug) to the QR encoder, not a hardcoded host (actual: %s)",
						capturedUrl)
				.isEqualTo(menuUrlBuilder.buildMenuUrl(SLUG));
		assertThat(capturedUrl)
				.as("the configured base https://qr.example.test must be used verbatim (actual: %s)", capturedUrl)
				.isEqualTo(EXPECTED_URL);
		assertThat(capturedUrl)
				.as("the dead localhost:3030 host must be gone from the QR content (actual: %s)", capturedUrl)
				.doesNotContain("localhost:3030");
	}

	@Test
	void printedSingleTablePdfContainsTheConfiguredPublicMenuUrl() throws Exception {

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, USERNAME);

		RestaurantTable table = new RestaurantTable();
		table.setBusiness(businessRepository.findById(BUSINESS_ID).orElseThrow());
		table.setTableNumber(3);
		table.setTableName("Mesa 3");
		table.setQrToken(UUID.randomUUID().toString());
		table.setStatus(TableStatus.AVAILABLE);
		table.setCreatedAt(LocalDateTime.now());
		table.setUpdatedAt(LocalDateTime.now());
		table = tableRepository.saveAndFlush(table);

		byte[] pdf = mockMvc
				.perform(get("/api/tables/{id}/qr/pdf", table.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.param("template", "SINGLE")
						.param("showUrl", "true"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_PDF))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
				.as("the body must start with the PDF magic bytes %PDF-")
				.isEqualTo("%PDF-");

		// Whitespace is stripped on BOTH sides so PDF line wrapping cannot make
		// the comparison flaky.
		String extractedText = extractPdfText(pdf).replaceAll("\\s+", "");

		assertThat(extractedText)
				.as("the printed PDF must show the configured public menu URL, not a dead host (actual: %s)",
						extractedText)
				.contains(EXPECTED_URL.replaceAll("\\s+", ""));
		assertThat(extractedText)
				.as("the printed PDF must not carry the dead localhost:3030 host (actual: %s)", extractedText)
				.doesNotContain("localhost:3030");
	}

	private RestaurantTable inMemoryTable(int number) {

		Business business = new Business();
		business.setSlug(SLUG);

		RestaurantTable table = new RestaurantTable();
		table.setBusiness(business);
		table.setTableName("Mesa " + number);
		table.setTableNumber(number);
		table.setQrToken(UUID.randomUUID().toString());
		return table;
	}

	private QrPdfRequestDTO config(QrTemplate template) {

		QrPdfRequestDTO config = new QrPdfRequestDTO();
		config.setTemplate(template);
		config.setShowUrl(false);
		config.setShowTableName(true);
		config.setQrSize(180);
		return config;
	}

	private List<String> capturedQrUrls(int expectedInvocations) {

		ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
		verify(qrCodeService, times(expectedInvocations)).generateQr(urlCaptor.capture(), any());
		return urlCaptor.getAllValues();
	}

	private String extractPdfText(byte[] pdf) throws IOException {

		StringBuilder text = new StringBuilder();
		try (PdfReader reader = new PdfReader(pdf)) {
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				text.append(extractor.getTextFromPage(page));
			}
		}
		return text.toString();
	}
}
