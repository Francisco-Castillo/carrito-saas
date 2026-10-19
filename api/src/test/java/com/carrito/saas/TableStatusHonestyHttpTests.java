package com.carrito.saas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/**
 * Honesty contract of the table responses: what the API reports must be what
 * the database holds, not a hardcoded {@code AVAILABLE}. The wiring of the
 * table endpoints (PR #22) exposed four service-layer defects and this class
 * pins their fixes at the HTTP level, where a client can observe them:
 *
 * <ul>
 * <li>after a soft DELETE, GET / GET-list must report DISABLED — the
 * assertion that was impossible while
 * {@code RestaurantTableMapperImpl.toDTO} hardcoded
 * {@code TableStatus.AVAILABLE} and never mapped {@code updatedAt};</li>
 * <li>PUT must report a non-null {@code updatedAt} and the exact status the
 * request carried (the update DTO requires it; discarding it is a lie in the
 * other direction);</li>
 * <li>POST /api/tables/bulk must report a status (never null) for the rows it
 * creates;</li>
 * <li>a duplicate table number inside the same business is a conflict — 409,
 * same as a duplicate username — while updating a table that keeps its own
 * number must still succeed.</li>
 * </ul>
 *
 * <p>Same HTTP-level style as {@code TableEndpointsHttpTests}: manual MockMvc
 * with the real filter chain, {@code @Transactional} rollback of every
 * fixture, and the effect asserted against the database after a
 * flush + clear, not just echoed back.</p>
 */
@SpringBootTest
@Transactional
class TableStatusHonestyHttpTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_810_201L;

	private static final Long OTHER_BUSINESS_ID = 987_810_202L;

	private static final String SLUG = "table-status-honesty-test-business";

	private static final String OTHER_SLUG = "table-status-honesty-other-business";

	private static final String USERNAME = "table-status-honesty-owner";

	private static final String PASSWORD = "table-status-honesty-password";

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
	 * Same principal, but the token carries the OTHER business' id — the
	 * tenant boundary must keep answering 404, never someone else's data.
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
	// DELETE then GET: the read-back must report DISABLED over HTTP
	// ==================================================================

	@Test
	void deleteThenGetReportsDisabledStatusOverHttp() throws Exception {

		long id = createTable(7, "Mesa 7");

		mockMvc.perform(delete("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk());

		// The assertion that was impossible before the mapper fix: the HTTP
		// response must agree with the soft delete the service performed,
		// instead of every response claiming AVAILABLE.
		mockMvc.perform(get("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value((int) id))
				.andExpect(jsonPath("$.status").value("DISABLED"));
	}

	@Test
	void deleteThenListReportsDisabledStatusOverHttp() throws Exception {

		long id = createTable(8, "Mesa 8");

		mockMvc.perform(delete("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/tables?page=0&size=50")
						.header(HttpHeaders.AUTHORIZATION, ownerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].status").value("DISABLED"));
	}

	// ==================================================================
	// PUT: reports a non-null updatedAt and the status the DTO sent
	// ==================================================================

	@Test
	void updateReportsSentStatusAndNonNullUpdatedAt() throws Exception {

		long id = createTable(9, "Mesa 9");

		String body = mockMvc.perform(put("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": 9, "tableName": "Mesa 9 Editada", "status": "OCCUPIED" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tableNumber").value(9))
				.andExpect(jsonPath("$.tableName").value("Mesa 9 Editada"))
				// The DTO requires the status, so discarding it silently is a
				// lie: the response must carry back exactly what was sent.
				.andExpect(jsonPath("$.status").value("OCCUPIED"))
				.andExpect(jsonPath("$.updatedAt").isNotEmpty())
				.andReturn()
				.getResponse()
				.getContentAsString();

		// Effect, not just echo: the sent status must reach the database.
		tableRepository.flush();
		entityManager.clear();
		RestaurantTable reloaded = tableRepository.findById(id).orElseThrow();
		assertEquals(TableStatus.OCCUPIED, reloaded.getStatus(),
				"PUT must persist the status the request carried, not discard it");
		assertEquals("Mesa 9 Editada", reloaded.getTableName());
	}

	// ==================================================================
	// POST /api/tables/bulk: created rows report a status, never null
	// ==================================================================

	@Test
	void bulkCreatesTablesWhoseStatusIsReportedNotNull() throws Exception {

		mockMvc.perform(post("/api/tables/bulk")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "quantity": 3 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].status").value("AVAILABLE"))
				.andExpect(jsonPath("$[1].status").value("AVAILABLE"))
				.andExpect(jsonPath("$[2].status").value("AVAILABLE"))
				.andExpect(jsonPath("$[0].updatedAt").isNotEmpty());

		var statuses = tableRepository.findByBusinessId(BUSINESS_ID).stream()
				.map(RestaurantTable::getStatus)
				.toList();

		assertEquals(3, statuses.size());
		statuses.forEach(status -> assertEquals(TableStatus.AVAILABLE, status,
				"bulk rows must be created AVAILABLE, not with a null status"));
	}

	// ==================================================================
	// Duplicate table number inside the same business: 409
	// ==================================================================

	@Test
	void createWithDuplicateTableNumberReturns409() throws Exception {

		createTable(7, "Mesa 7");

		mockMvc.perform(post("/api/tables")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": 7, "tableName": "Mesa 7 Duplicada" }
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value("Ya existe una mesa con ese número"));

		// The duplicate must not have been created.
		assertEquals(1, tableRepository.findByBusinessId(BUSINESS_ID).size());
	}

	@Test
	void updateToDuplicateTableNumberReturns409() throws Exception {

		createTable(1, "Mesa 1");
		long id2 = createTable(2, "Mesa 2");

		mockMvc.perform(put("/api/tables/" + id2)
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": 1, "tableName": "Mesa 2", "status": "AVAILABLE" }
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value("Ya existe una mesa con ese número"));

		// Mesa 1 keeps its number: the rejected update changed nothing.
		assertEquals(1, tableRepository.findByBusinessId(BUSINESS_ID).stream()
				.filter(t -> t.getTableNumber().equals(1))
				.count());
	}

	@Test
	void updateKeepingItsOwnTableNumberStillSucceeds() throws Exception {

		long id = createTable(5, "Mesa 5");

		mockMvc.perform(put("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": 5, "tableName": "Mesa 5 Renombrada", "status": "AVAILABLE" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tableNumber").value(5))
				.andExpect(jsonPath("$.tableName").value("Mesa 5 Renombrada"));
	}

	// ==================================================================
	// Tenant boundary regression: a foreign business' table stays 404
	// ==================================================================

	@Test
	void foreignBusinessTableStillReturns404() throws Exception {

		long id = createTable(21, "Mesa Ajena");

		mockMvc.perform(get("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, foreignBusinessToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Mesa no encontrada"));

		mockMvc.perform(put("/api/tables/" + id)
						.header(HttpHeaders.AUTHORIZATION, foreignBusinessToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "tableNumber": 99, "tableName": "Robada", "status": "DISABLED" }
								"""))
				.andExpect(status().isNotFound());

		// Neither attempt touched the foreign row.
		assertEquals(TableStatus.AVAILABLE, tableRepository.findById(id).orElseThrow().getStatus());
	}

}
