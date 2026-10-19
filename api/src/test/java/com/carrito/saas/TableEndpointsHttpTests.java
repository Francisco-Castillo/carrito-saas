package com.carrito.saas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

		// Reuse the role when the environment's dev seed already created it
		// (uq_roles_name): a blind insert fails against a seeded database.
		Role role = roleRepository.findByName("OWNER").orElseGet(() -> {
			Role r = new Role();
			r.setName("OWNER");
			return roleRepository.saveAndFlush(r);
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
	// POST /api/tables/bulk: creates N, rerun fills the gaps only
	// ==================================================================

	@Test
	void bulkCreatesRequestedTablesAndRerunDoesNotDuplicate() throws Exception {

		String first = mockMvc.perform(post("/api/tables/bulk")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "quantity": 3 }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].tableNumber").value(1))
				.andExpect(jsonPath("$[2].tableNumber").value(3))
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertEquals(3, tableRepository.findByBusinessId(BUSINESS_ID).size(),
				"bulk with quantity 3 must persist 3 tables");

		String second = mockMvc.perform(post("/api/tables/bulk")
						.header(HttpHeaders.AUTHORIZATION, ownerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "quantity": 5 }
								"""))
				.andExpect(status().isOk())
				// 1..3 already exist: only 4 and 5 are new.
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].tableNumber").value(4))
				.andExpect(jsonPath("$[1].tableNumber").value(5))
				.andReturn()
				.getResponse()
				.getContentAsString();

		var numbers = tableRepository.findByBusinessId(BUSINESS_ID).stream()
				.map(RestaurantTable::getTableNumber)
				.toList();

		assertEquals(5, numbers.size(), "rerunning bulk must not duplicate existing numbers");
		assertTrue(numbers.stream().distinct().count() == numbers.size(),
				"table numbers must be unique within the business, got " + numbers);
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
