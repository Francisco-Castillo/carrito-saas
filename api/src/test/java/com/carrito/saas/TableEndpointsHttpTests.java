package com.carrito.saas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

import java.io.IOException;
import java.util.List;

import jakarta.persistence.EntityManager;

import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.TableStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessUser;
import com.carrito.saas.repository.entity.Role;
import com.carrito.saas.repository.entity.User;
import com.carrito.saas.security.JwtUtil;
import com.jayway.jsonpath.JsonPath;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

/**
 * Wiring contract of the table endpoints the class javadoc declares
 * ({@code TableController}): each one must reach {@code ITableService} and
 * produce the DECLARED EFFECT, not a 200 with an empty body and no change —
 * an endpoint that answers 200 and does nothing is worse than a missing one
 * because the client believes it worked.
 *
 * <p>Same HTTP-level style as {@code PanelErrorStatusHttpTests}: manual
 * MockMvc with the real filter chain, {@code ownerToken()}-style auth,
 * {@code @Transactional} rollback of every fixture. Effect is asserted by
 * reading the table back through the API AND the repository, so a stub
 * controller cannot pass by accident.</p>
 */
@SpringBootTest
@Transactional
class TableEndpointsHttpTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_810_101L;

	private static final Long OTHER_BUSINESS_ID = 987_810_102L;

	private static final String SLUG = "table-endpoints-test-business";

	private static final String OTHER_SLUG = "table-endpoints-other-business";

	private static final String USERNAME = "table-endpoints-owner";

	private static final String PASSWORD = "table-endpoints-password";

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

	@Autowired
	private TableRepository tableRepository;

	@Autowired
	private EntityManager entityManager;

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

	private void seedBusinessWithOwner(long id, String slug) {

		seedBusiness(id, slug);

		Role role = roleRepository.findByName("OWNER")
				.orElseGet(() -> {
					Role created = new Role();
					created.setName("OWNER");
					return roleRepository.saveAndFlush(created);
				});
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

	/**
	 * Same principal, but the token carries the OTHER business' id — exactly
	 * what {@code SecurityService.getCurrentBusinessId()} trusts. The table
	 * must not leak: 404, never someone else's data.
	 */
	private String foreignBusinessToken() {
		return "Bearer " + jwtUtil.generateToken(USERNAME, OTHER_BUSINESS_ID);
	}

	private long createTable(int number, String name) throws Exception {

		String body = mockMvc.perform(post("/api/tables")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": %d, "tableName": "%s" }
								""".formatted(number, name)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	// ==================================================================
	// DELETE /api/tables/{id}: soft delete, visible on read-back
	// ==================================================================

	@Test
	void deleteSoftDeletesTableSoReadBackShowsDisabled() throws Exception {

		long id = createTable(7, "Mesa 7");

		mockMvc.perform(delete("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk());

		// Effect, not just status: the row must be DISABLED afterwards.
		// flush + clear forces a real DB read, proving the soft delete PERSISTS
		// through dirty checking, not just that the managed entity changed.
		tableRepository.flush();
		entityManager.clear();
		RestaurantTable reloaded = tableRepository.findById(id).orElseThrow();
		assertEquals(TableStatus.DISABLED, reloaded.getStatus(),
				"DELETE must soft-delete the table, not answer 200 and change nothing");

		// Read-back through the API: identity and name must round-trip.
		// NOTE: $.status is deliberately NOT asserted over HTTP —
		// RestaurantTableMapperImpl.toDTO hardcodes TableStatus.AVAILABLE and
		// never maps updatedAt (service layer, outside this task's edit
		// surfaces); the real status is asserted against the DB above.
		mockMvc.perform(get("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value((int) id))
				.andExpect(jsonPath("$.tableName").value("Mesa 7"));
	}

	// ==================================================================
	// POST /api/tables/bulk: agrega N mesas NUEVAS, numeradas a
	// continuación de la más alta que ya tiene el local
	// ==================================================================

	@Test
	void bulkAddsRequestedNewTablesContinuingTheNumbering() throws Exception {

		mockMvc.perform(post("/api/tables/bulk")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "quantity": 3 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].tableNumber").value(1))
				.andExpect(jsonPath("$[2].tableNumber").value(3));

		assertEquals(3, tableRepository.findByBusinessId(BUSINESS_ID).size(),
				"the first bulk must persist exactly the 3 requested tables");

		// quantity es cuántas se AGREGAN, no el total que debería existir:
		// con Mesa 1..3 ocupadas, pedir 2 crea Mesa 4 y Mesa 5 (antes pedir
		// "2" no creaba nada, y pedir "5" creaba 2, que es lo que confundía
		// al dueño en la UI).
		mockMvc.perform(post("/api/tables/bulk")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "quantity": 2 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].tableNumber").value(4))
				.andExpect(jsonPath("$[1].tableNumber").value(5));

		var numbers = tableRepository.findByBusinessId(BUSINESS_ID).stream()
				.map(RestaurantTable::getTableNumber)
				.sorted()
				.toList();

		assertEquals(5, numbers.size(), "3 + 2 new tables must persist 5 tables, got " + numbers);
		assertEquals(List.of(1, 2, 3, 4, 5), numbers,
				"every bulk must number its new tables after the highest existing one, got " + numbers);
	}

	@Test
	void bulkReusesTheNumberFreedByADeletedTable() throws Exception {

		mockMvc.perform(post("/api/tables/bulk")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "quantity": 3 }
								"""))
				.andExpect(status().isOk());

		// Baja de la mesa del medio: es soft delete (status DISABLED), la fila
		// sobrevive para el historial de pedidos y para no romper el QR ya
		// impreso, pero su NÚMERO vuelve a estar libre.
		long deletedId = tableRepository.findByBusinessId(BUSINESS_ID).stream()
				.filter(table -> table.getTableNumber() == 2)
				.findFirst()
				.orElseThrow()
				.getId();

		mockMvc.perform(delete("/api/tables/" + deletedId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/tables/bulk")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "quantity": 1 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].tableNumber").value(2));

		var liveTables = tableRepository.findByBusinessId(BUSINESS_ID).stream()
				.filter(table -> table.getStatus() != TableStatus.DISABLED)
				.toList();

		assertEquals(3, liveTables.size(), "the local must keep 3 usable tables");

		var newNumberTwo = liveTables.stream()
				.filter(table -> table.getTableNumber() == 2)
				.findFirst()
				.orElseThrow();

		assertTrue(!newNumberTwo.getId().equals(deletedId),
				"the reused number must belong to the NEW row, not to the deleted one");
	}

	@Test
	void bulkFillsGapsBeforeExtendingTheNumbering() throws Exception {

		mockMvc.perform(post("/api/tables/bulk")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "quantity": 5 }
								"""))
				.andExpect(status().isOk());

		// Se dan de baja la 2 y la 4: quedan vivas 1, 3 y 5.
		for (int number : new int[] { 2, 4 }) {

			long id = tableRepository.findByBusinessId(BUSINESS_ID).stream()
					.filter(table -> table.getTableNumber() == number)
					.findFirst()
					.orElseThrow()
					.getId();

			mockMvc.perform(delete("/api/tables/" + id)
							.header(HttpHeaders.AUTHORIZATION, ownerToken()))
					.andExpect(status().isOk());
		}

		// Dos nuevas: toman los huecos 2 y 4, no 6 y 7. La numeración del local
		// vuelve a ser contigua 1..5 sin que el dueño tenga que renombrar nada.
		mockMvc.perform(post("/api/tables/bulk")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "quantity": 2 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].tableNumber").value(2))
				.andExpect(jsonPath("$[1].tableNumber").value(4));
	}
	// ==================================================================
	// GET /api/tables/qr/pdf: el PDF no imprime QRs de mesas dadas de baja
	// ==================================================================

	@Test
	void allTablesPdfSkipsDeletedTables() throws Exception {

		createTable(1, "Mesa 1");
		long deletedId = createTable(2, "Mesa 2");
		createTable(3, "Mesa 3");

		mockMvc.perform(delete("/api/tables/" + deletedId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk());

		byte[] pdf = mockMvc.perform(get("/api/tables/qr/pdf")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.param("template", "SINGLE"))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_PDF))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		String text = extractPdfText(pdf);

		assertTrue(text.contains("Mesa1") && text.contains("Mesa3"),
				"the live tables must be printed, got " + text);
		assertTrue(!text.contains("Mesa2"),
				"a deleted table's QR must not be printed: its QR no longer works, got " + text);
	}

	@Test
	void allTablesPdfWithoutLiveTablesAnswers404() throws Exception {

		long onlyTableId = createTable(1, "Mesa 1");

		mockMvc.perform(delete("/api/tables/" + onlyTableId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/tables/qr/pdf")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.param("template", "SINGLE"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("No hay mesas activas para generar el PDF"));
	}

	/** Texto del PDF con TODO el espaciado quitado: "Mesa 1" queda "Mesa1". */
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

	// ==================================================================
	// Número liberado por una baja: sirve para crear y para renombrar
	// ==================================================================

	@Test
	void deletedTableNumberIsFreeForCreateAndUpdate() throws Exception {

		long deletedId = createTable(1, "Mesa 1");
		long otherId = createTable(3, "Mesa 3");

		mockMvc.perform(delete("/api/tables/" + deletedId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk());

		// Crear con el número que liberó la baja: antes era 409 porque la fila
		// dada de baja seguía "ocupando" el 1.
		mockMvc.perform(post("/api/tables")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": 1, "tableName": "Mesa 1" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tableNumber").value(1));

		// Y renombrar a un hueco libre: la Mesa 3 pasa a ser la Mesa 2.
		mockMvc.perform(put("/api/tables/" + otherId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": 2, "tableName": "Mesa 2", "status": "AVAILABLE" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tableNumber").value(2));
	}

	// ==================================================================
	// PUT /api/tables/{id}: changes number/name as sent
	// ==================================================================

	@Test
	void updateChangesTableNumberAndNameAsSent() throws Exception {

		long id = createTable(9, "Mesa 9");

		mockMvc.perform(put("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": 10, "tableName": "Mesa Renombrada", "status": "AVAILABLE" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tableNumber").value(10))
				.andExpect(jsonPath("$.tableName").value("Mesa Renombrada"));

		// Effect: persisted, not just echoed. flush + clear forces a real DB
		// read, proving the service's dirty checking persists the change.
		tableRepository.flush();
		entityManager.clear();
		RestaurantTable reloaded = tableRepository.findById(id).orElseThrow();
		assertEquals(10, reloaded.getTableNumber());
		assertEquals("Mesa Renombrada", reloaded.getTableName());
	}

	@Test
	void updateWithMissingTableNumberReturns400() throws Exception {

		long id = createTable(11, "Mesa 11");

		mockMvc.perform(put("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableName": "Sin numero" }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));
	}

	// ==================================================================
	// GET /api/tables and GET /api/tables/{id}: the caller's data
	// ==================================================================

	@Test
	void findAllReturnsTheCallerTables() throws Exception {

		createTable(1, "Mesa 1");
		createTable(2, "Mesa 2");

		mockMvc.perform(get("/api/tables?page=0&size=50")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].tableNumber").value(1))
				.andExpect(jsonPath("$.content[1].tableNumber").value(2));
	}

	@Test
	void findByIdReturnsTheTable() throws Exception {

		long id = createTable(5, "Mesa 5");

		mockMvc.perform(get("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value((int) id))
				.andExpect(jsonPath("$.tableNumber").value(5))
				.andExpect(jsonPath("$.tableName").value("Mesa 5"));
	}

	// ==================================================================
	// Unknown id: 404 from the service's BusinessException, not 200/null
	// ==================================================================

	@Test
	void unknownTableIdOnGetByIdReturns404WithBusinessMessage() throws Exception {

		mockMvc.perform(get("/api/tables/999999999")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message").value("Mesa no encontrada"));
	}

	@Test
	void unknownTableIdOnUpdateReturns404() throws Exception {

		mockMvc.perform(put("/api/tables/999999999")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": 1, "tableName": "Fantasma", "status": "AVAILABLE" }
								"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Mesa no encontrada"));
	}

	@Test
	void unknownTableIdOnDeleteReturns404() throws Exception {

		mockMvc.perform(delete("/api/tables/999999999")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Mesa no encontrada"));
	}

	// ==================================================================
	// Cross-tenant: another business' table is 404, not leaked data
	// ==================================================================

	@Test
	void foreignBusinessTableReturns404NotSomeoneElsesData() throws Exception {

		long id = createTable(21, "Mesa Ajena");

		mockMvc.perform(get("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, foreignBusinessToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Mesa no encontrada"));

		mockMvc.perform(delete("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, foreignBusinessToken()))
				.andExpect(status().isNotFound());

		// The delete attempt of a foreign caller must not have touched it.
		assertEquals(TableStatus.AVAILABLE, tableRepository.findById(id).orElseThrow().getStatus());
	}

	// ==================================================================
	// bulk validation: quantity outside 1..300 is a client error
	// ==================================================================

	@Test
	void bulkWithZeroQuantityReturns400() throws Exception {

		mockMvc.perform(post("/api/tables/bulk")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "quantity": 0 }
								"""))
				.andExpect(status().isBadRequest());

		assertEquals(0, tableRepository.findByBusinessId(BUSINESS_ID).size(),
				"a rejected bulk must not create tables");
	}

}
