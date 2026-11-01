package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.ArrayList;
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
 * {@code GRID_4X4} es una plantilla que el enum ofrecía y el backend no sabía
 * generar: {@link PdfTemplateFactory} no tenía estrategia para ella y
 * {@code GET /api/tables/qr/pdf?template=GRID_4X4} respondía 500, así que la
 * UI terminó escondiéndola. Estos tests fijan lo que tiene que cumplir ahora
 * que existe {@code Grid4Template}:
 *
 * <ul>
 * <li>la fábrica la resuelve (el 500 no puede volver por wiring);</li>
 * <li>16 mesas entran en una sola hoja A4 y ninguna mesa se pierde cuando el
 * último grupo no completa la fila de 4 (la lección de
 * {@code Grid3RowRenderingTests});</li>
 * <li>con muchas mesas el documento pagina y no se cae ninguna;</li>
 * <li>cada QR codifica la URL pública configurada con su mesa, nunca un host
 * fijo;</li>
 * <li>el endpoint responde un PDF de verdad, no un 500.</li>
 * </ul>
 *
 * <p>La estrategia se toma siempre de {@link PdfTemplateFactory} (el bean de
 * producción), nunca se construye a mano. El encoder de QR está mockeado con
 * un PNG real del encoder del proyecto porque {@code Image.getInstance(qr)}
 * rechaza cualquier cosa que no sea una imagen válida; el override de
 * {@code qr.public-menu-url} apunta a un host que no existe en el código, así
 * que una URL hardcodeada no puede satisfacer las aserciones.</p>
 */
@SpringBootTest(properties = "qr.public-menu-url=https://qr.example.test/")
@Transactional
class Grid4TemplateTests {

	private static final Long BUSINESS_ID = 987_654_380L;

	private static final String SLUG = "grid4-template-business";

	private static final String USERNAME = "grid4-template-owner";

	private static final String EXPECTED_MENU_URL = "https://qr.example.test/grid4-template-business";

	private static String expectedTableUrl(long tableId) {
		return EXPECTED_MENU_URL + "?table=" + tableId;
	}

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
	void factoryResolvesTheGrid4Strategy() {
		assertThat(pdfTemplateFactory.get(QrTemplate.GRID_4X4).getTemplate())
				.as("the factory must have a strategy for GRID_4X4; without it the endpoint answers 500")
				.isEqualTo(QrTemplate.GRID_4X4);
	}

	@Test
	void sixteenTablesPrintOnASingleA4Page() throws Exception {
		byte[] pdf = pdfTemplateFactory.get(QrTemplate.GRID_4X4)
				.generate(tables(16), config());

		try (PdfReader reader = new PdfReader(pdf)) {
			assertThat(reader.getNumberOfPages())
					.as("4 columns x 4 rows is exactly one page")
					.isEqualTo(1);
		}

		String text = extractPdfText(pdf);

		for (int number = 1; number <= 16; number++) {
			assertThat(text)
					.as("every table of a full 4x4 sheet must be printed (actual: %s)", text)
					.contains("Mesa" + number);
		}
	}

	@Test
	void trailingPartialRowIsNotDropped() throws Exception {
		// 5 tables leave a row with a single cell, which PdfPTable drops unless
		// the row is completed.
		byte[] pdf = pdfTemplateFactory.get(QrTemplate.GRID_4X4)
				.generate(tables(5), config());

		String text = extractPdfText(pdf);

		for (int number = 1; number <= 5; number++) {
			assertThat(text)
					.as("an incomplete trailing row must still print its table (actual: %s)", text)
					.contains("Mesa" + number);
		}
	}

	@Test
	void manyTablesPaginateWithoutLosingAnyTable() throws Exception {
		byte[] pdf = pdfTemplateFactory.get(QrTemplate.GRID_4X4)
				.generate(tables(40), config());

		try (PdfReader reader = new PdfReader(pdf)) {
			assertThat(reader.getNumberOfPages())
					.as("40 tables are ten rows: the sheet has to continue on another page")
					.isGreaterThan(1);
		}

		String text = extractPdfText(pdf);
		// The counters that overflow a page are the interesting ones.
		for (int number : new int[] { 1, 16, 17, 32, 40 }) {
			assertThat(text)
					.as("no table may be lost when the grid spills to the next page (actual: %s)", text)
					.contains("Mesa" + number);
		}
	}

	@Test
	void handsTheConfiguredPublicMenuUrlToTheQrEncoder() {
		pdfTemplateFactory.get(QrTemplate.GRID_4X4)
				.generate(tables(3), config());

		ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
		verify(qrCodeService, times(3)).generateQr(urlCaptor.capture(), any());

		List<String> capturedUrls = urlCaptor.getAllValues();

		assertThat(capturedUrls)
				.as("each table must carry its OWN id in the QR URL (actual: %s)", capturedUrls)
				.containsExactly(expectedTableUrl(1L), expectedTableUrl(2L), expectedTableUrl(3L));
		assertThat(capturedUrls.get(0))
				.as("the URL must come from menuUrlBuilder.buildTableMenuUrl, not a hardcoded host")
				.isEqualTo(menuUrlBuilder.buildTableMenuUrl(SLUG, 1L));
		assertThat(capturedUrls)
				.as("the dead localhost:3030 host must be gone (actual: %s)", capturedUrls)
				.doesNotContain("localhost:3030");
	}

	@Test
	void honoursTheRequestedQrSizeAndTheOptionalUrlText() throws Exception {

		QrPdfRequestDTO config = config();
		config.setQrSize(80);
		config.setShowUrl(true);

		byte[] pdf = pdfTemplateFactory.get(QrTemplate.GRID_4X4)
				.generate(tables(1), config);

		// El tamaño pedido llega al encoder (80pt impresos x 4 de render).
		verify(qrCodeService).generateQr(anyString(), eq(320));

		String text = extractPdfText(pdf);

		assertThat(text)
				.as("showUrl=true must print the destination under the QR (actual: %s)", text)
				.contains("table=1");
		assertThat(text)
				.as("the name is still printed by default (actual: %s)", text)
				.contains("Mesa1");
	}

	@Test
	void omitsTheTableNameWhenTheCallerTurnsItOff() throws Exception {

		QrPdfRequestDTO config = config();
		config.setShowTableName(false);

		byte[] pdf = pdfTemplateFactory.get(QrTemplate.GRID_4X4)
				.generate(tables(1), config);

		assertThat(extractPdfText(pdf))
				.as("showTableName=false must not print the table name")
				.doesNotContain("Mesa1");
	}

	@Test
	void httpEndpointRendersGrid4InsteadOfFailing() throws Exception {
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
		tableRepository.saveAndFlush(table);

		byte[] pdf = mockMvc
				.perform(get("/api/tables/qr/pdf")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.param("template", "GRID_4X4"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_PDF))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
				.as("the body must start with the PDF magic bytes %PDF-")
				.isEqualTo("%PDF-");

		assertThat(extractPdfText(pdf))
				.as("the persisted table must be printed on the GRID_4X4 sheet")
				.contains("Mesa1");
	}

	private List<RestaurantTable> tables(int count) {

		List<RestaurantTable> tables = new ArrayList<>();

		for (int number = 1; number <= count; number++) {
			tables.add(inMemoryTable(number));
		}

		return tables;
	}

	private RestaurantTable inMemoryTable(int number) {

		Business business = new Business();
		business.setSlug(SLUG);

		RestaurantTable table = new RestaurantTable();
		table.setBusiness(business);
		table.setTableName("Mesa " + number);
		table.setTableNumber(number);
		table.setQrToken(UUID.randomUUID().toString());
		// The QR URL carries the table id, so an in-memory table needs a
		// deterministic identity.
		table.setId((long) number);
		return table;
	}

	private QrPdfRequestDTO config() {

		QrPdfRequestDTO config = new QrPdfRequestDTO();
		config.setTemplate(QrTemplate.GRID_4X4);
		config.setShowUrl(false);
		config.setShowTableName(true);
		config.setQrSize(110);
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
