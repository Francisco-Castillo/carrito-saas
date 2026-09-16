package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.carrito.saas.service.interfaces.IQrCodeService;
import com.carrito.saas.service.strategies.impl.PdfTemplateFactory;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

/**
 * Row completeness of the {@code GRID_3X3} QR sheet: an OpenPDF
 * {@link com.lowagie.text.pdf.PdfPTable} row is only rendered when the row is
 * complete, so a trailing group of 1 or 2 tables used to be silently dropped
 * from the printed document, and a sheet with fewer than 3 tables (one
 * incomplete row) used to fail the whole rendering with
 * {@code The document has no pages.} -- HTTP 500 on
 * {@code GET /api/tables/qr/pdf?template=GRID_3X3}.
 *
 * <p>{@code PdfPTable.completeRow()} pads the trailing row, so every table
 * handed to the strategy must be printed whatever the total count is.</p>
 *
 * <p>The template is reached through {@link PdfTemplateFactory} (Spring
 * wiring), never constructed directly, so the test observes exactly what the
 * production bean does. The QR encoder is mocked to return a real, decodable
 * PNG (built with the project's own encoder) because
 * {@code Image.getInstance(qr)} rejects anything that is not a valid image.
 * The table names are the extractable evidence: every cell renders
 * {@code table.getTableName()} as a paragraph.</p>
 */
@SpringBootTest
@Transactional
class Grid3RowRenderingTests {

	private static final Long BUSINESS_ID = 987_654_370L;

	private static final String SLUG = "grid3-row-rendering-business";

	private static final String USERNAME = "grid3-row-rendering-owner";

	@Autowired
	private PdfTemplateFactory pdfTemplateFactory;

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
	void singleTableRendersOnePageWithItsName() throws Exception {

		byte[] pdf = pdfTemplateFactory.get(QrTemplate.GRID_3X3)
				.generate(List.of(inMemoryTable(1)), config());

		String extractedText = extractPdfText(pdf);

		assertThat(extractedText)
				.as("a GRID_3X3 sheet with a single table must still render a page carrying that table's name (actual: %s)",
						extractedText)
				.contains("Mesa1");
	}

	@Test
	void twoTablesRenderBothNames() throws Exception {

		byte[] pdf = pdfTemplateFactory.get(QrTemplate.GRID_3X3)
				.generate(List.of(inMemoryTable(1), inMemoryTable(2)), config());

		String extractedText = extractPdfText(pdf);

		assertThat(extractedText)
				.as("a GRID_3X3 sheet with two tables must render both names (actual: %s)", extractedText)
				.contains("Mesa1")
				.contains("Mesa2");
	}

	@Test
	void trailingRowWithOneTableIsNotDropped() throws Exception {

		byte[] pdf = pdfTemplateFactory.get(QrTemplate.GRID_3X3)
				.generate(List.of(inMemoryTable(1), inMemoryTable(2), inMemoryTable(3), inMemoryTable(4)), config());

		String extractedText = extractPdfText(pdf);

		assertThat(extractedText)
				.as("the completed first row must keep its three names (actual: %s)", extractedText)
				.contains("Mesa1", "Mesa2", "Mesa3");

		assertThat(extractedText)
				.as("the trailing one-cell row must not be silently dropped (actual: %s)", extractedText)
				.contains("Mesa4");
	}

	@Test
	void trailingRowWithTwoTablesIsNotDropped() throws Exception {

		byte[] pdf = pdfTemplateFactory.get(QrTemplate.GRID_3X3)
				.generate(List.of(inMemoryTable(1), inMemoryTable(2), inMemoryTable(3), inMemoryTable(4),
						inMemoryTable(5)), config());

		String extractedText = extractPdfText(pdf);

		assertThat(extractedText)
				.as("the trailing two-cell row must keep both of its names (actual: %s)", extractedText)
				.contains("Mesa4", "Mesa5");
	}

	@Test
	void httpEndpointRendersASingleTableWithoutFailing() throws Exception {

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, USERNAME);

		RestaurantTable table = new RestaurantTable();
		table.setBusiness(businessRepository.findById(BUSINESS_ID).orElseThrow());
		table.setTableNumber(1);
		table.setTableName("Mesa 1");
		table.setQrToken(UUID.randomUUID().toString());
		table.setStatus(TableStatus.AVAILABLE);
		table.setCreatedAt(LocalDateTime.now());
		table.setUpdatedAt(LocalDateTime.now());
		table = tableRepository.saveAndFlush(table);

		byte[] pdf = mockMvc
				.perform(get("/api/tables/qr/pdf")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.param("template", "GRID_3X3"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_PDF))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
				.as("the body must start with the PDF magic bytes %PDF-")
				.isEqualTo("%PDF-");

		String extractedText = extractPdfText(pdf);

		assertThat(extractedText)
				.as("the single persisted table's name must be printed on the GRID_3X3 sheet (actual: %s)",
						extractedText)
				.contains("Mesa1");
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

	private QrPdfRequestDTO config() {

		QrPdfRequestDTO config = new QrPdfRequestDTO();
		config.setTemplate(QrTemplate.GRID_3X3);
		config.setShowUrl(false);
		config.setShowTableName(true);
		config.setQrSize(180);
		return config;
	}

	/**
	 * Text of the WHOLE document, all pages concatenated with ALL whitespace
	 * stripped, so page layout and line wrapping cannot make the assertions
	 * fragile ("Mesa 1" becomes "Mesa1").
	 */
	private String extractPdfText(byte[] pdf) throws IOException {

		StringBuilder text = new StringBuilder();
		try (PdfReader reader = new PdfReader(pdf)) {
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				text.append(extractor.getTextFromPage(page));
			}
		}
		return text.toString().replaceAll("\\s+", "");
	}
}
