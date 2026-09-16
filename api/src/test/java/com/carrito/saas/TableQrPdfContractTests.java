package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

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

import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.TableStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * HTTP contract of the per-table QR PDF endpoints:
 * {@code GET /api/tables/{id}/qr/pdf} and {@code GET /api/tables/qr/pdf}.
 *
 * <p>A GET request has no body by definition, so the PDF options must be
 * selectable through query parameters (e.g. {@code ?template=SINGLE}); a
 * browser can never satisfy a required {@code @RequestBody} on a GET. Both
 * endpoints must answer a bodyless GET with a real PDF (magic bytes
 * {@code %PDF-}), never with a 400 "required request body is missing".</p>
 */
@SpringBootTest
@Transactional
class TableQrPdfContractTests {

	private static final Long BUSINESS_ID = 987_654_330L;

	private static final String SLUG = "table-pdf-contract-business";

	private static final String USERNAME = "table-pdf-contract-owner";

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

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private RestaurantTable table;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, USERNAME);

		table = new RestaurantTable();
		table.setBusiness(businessRepository.findById(BUSINESS_ID).orElseThrow());
		table.setTableNumber(7);
		table.setTableName("Mesa 7");
		table.setQrToken(UUID.randomUUID().toString());
		table.setStatus(TableStatus.AVAILABLE);
		table.setCreatedAt(LocalDateTime.now());
		table.setUpdatedAt(LocalDateTime.now());
		table = tableRepository.saveAndFlush(table);
	}

	@Test
	void singleTablePdfIsDownloadableWithQueryParametersOnly() throws Exception {

		byte[] pdf = mockMvc.perform(get("/api/tables/{id}/qr/pdf", table.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.param("template", "SINGLE"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_PDF))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		assertThat(pdf.length)
				.as("the PDF must be a real document, not an empty body")
				.isGreaterThan(100);

		assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
				.as("the body must start with the PDF magic bytes %PDF-")
				.isEqualTo("%PDF-");
	}

	@Test
	void allTablesPdfIsDownloadableWithQueryParametersOnly() throws Exception {

		byte[] pdf = mockMvc.perform(get("/api/tables/qr/pdf")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.param("template", "SINGLE"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_PDF))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		assertThat(pdf.length)
				.as("the PDF must be a real document, not an empty body")
				.isGreaterThan(100);

		assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1))
				.as("the body must start with the PDF magic bytes %PDF-")
				.isEqualTo("%PDF-");
	}

	@Test
	void anonymousRequestIsRejected() throws Exception {

		mockMvc.perform(get("/api/tables/qr/pdf"))
				.andExpect(result -> Assertions.assertThat(result.getResponse().getStatus())
						.as("GET /api/tables/qr/pdf must stay authenticated (actual: %d)",
								result.getResponse().getStatus())
						.isIn(401, 403));
	}
}
