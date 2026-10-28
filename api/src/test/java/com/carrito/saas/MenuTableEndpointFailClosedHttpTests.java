package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.TableStatus;
import com.carrito.saas.repository.jpa.BusinessDateExceptionRepository;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.OrderItemRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.repository.jpa.TableRequestRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * Fail-closed contract of {@code GET /api/menu/{slug}/table/{id}} beyond the
 * happy path pinned by {@link MenuTableEndpointHttpTests} (independently probed
 * during the verification of odd/tasks/pedido-qr-mesa.md):
 *
 * <ul>
 * <li>a slug that resolves to NO business answers 404 — the public surface
 * must never guess (S4 enmendada de odd/tasks/retire-menu-route.md: the
 * slug-scoped endpoint keeps the fail-closed 404 the old slugless resolver
 * produced for zero businesses);</li>
 * <li>the table of a DEACTIVATED business answers 404 — baja lógica (S5 de
 * odd/tasks/plataforma-editar-baja.md): a deactivated local does not exist
 * publicly, the same rule as the menu;</li>
 * <li>the control: one ACTIVE business still answers 200 with ONLY the
 * public identity ({@code id}, {@code tableNumber}, {@code tableName}).</li>
 * </ul>
 *
 * <p>The suite makes the shared dev database's {@code businesses} table
 * deterministic inside its own rolled-back transaction (deletion follows the
 * FK chain, dependents first).</p>
 */
@SpringBootTest
@Transactional
class MenuTableEndpointFailClosedHttpTests {

	private static final Long BUSINESS_ID = 987_654_370L;

	private static final String SLUG = "menu-table-failclosed-business";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BranchRepository branchRepository;

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
	private OrderItemRepository orderItemRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TableRequestRepository tableRequestRepository;

	@Autowired
	private ComboRepository comboRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private BusinessHoursRepository businessHoursRepository;

	@Autowired
	private BusinessDateExceptionRepository businessDateExceptionRepository;

	@Autowired
	private JwtUtil jwtUtil;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		// Same determinism rule as MenuTableEndpointHttpTests: leftover
		// committed businesses must go — inside this test's transaction only —
		// so the slug under test resolves exactly what the test seeds.
		orderItemRepository.deleteAll();
		orderRepository.deleteAll();
		tableRequestRepository.deleteAll();
		comboRepository.deleteAll();
		productRepository.deleteAll();
		categoryRepository.deleteAll();
		businessUserRepository.deleteAll();
		businessHoursRepository.deleteAll();
		businessDateExceptionRepository.deleteAll();
		tableRepository.deleteAll();
		// Multi-sucursal: las branches son dependientes del negocio (y los
		// pedidos ya fueron borrados, así que fk_orders_branch no molesta).
		branchRepository.deleteAll();
		businessRepository.deleteAll();
	}

	@Test
	void anUnknownSlugAnswersNotFound() throws Exception {

		// No business seeded: the slug resolves to nothing. The endpoint must
		// refuse with NOT_FOUND, never guess.
		mockMvc.perform(get("/api/menu/{slug}/table/{id}", "no-such-slug", 1L))
				.andExpect(status().isNotFound());
	}

	@Test
	void deactivatedBusinessAnswers404() throws Exception {

		Business business = saveBusiness(false);
		saveTable(business, "Mesa baja", 1);

		mockMvc.perform(get("/api/menu/{slug}/table/{id}", SLUG, tableIdOf(business)))
				.andExpect(status().isNotFound());
	}

	@Test
	void activeBusinessControlStill200Minimal() throws Exception {

		Business business = saveBusiness(true);
		saveTable(business, "Mesa control", 2);

		mockMvc.perform(get("/api/menu/{slug}/table/{id}", SLUG, tableIdOf(business)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(tableIdOf(business)))
				.andExpect(jsonPath("$.tableNumber").value(2))
				.andExpect(jsonPath("$.tableName").value("Mesa control"))
				.andExpect(jsonPath("$.qrToken").doesNotExist())
				.andExpect(jsonPath("$.qrUrl").doesNotExist())
				.andExpect(jsonPath("$.status").doesNotExist());
	}

	private Business saveBusiness(boolean active) {

		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Business " + SLUG);
		business.setSlug(SLUG);
		business.setActive(active);
		return businessRepository.saveAndFlush(business);
	}

	private RestaurantTable saveTable(Business business, String tableName, int tableNumber) {

		RestaurantTable table = new RestaurantTable();
		table.setBusiness(business);
		table.setTableNumber(tableNumber);
		table.setTableName(tableName);
		table.setQrToken(UUID.randomUUID().toString());
		table.setStatus(TableStatus.AVAILABLE);
		table.setCreatedAt(LocalDateTime.now());
		table.setUpdatedAt(LocalDateTime.now());
		return tableRepository.saveAndFlush(table);
	}

	private long tableIdOf(Business business) {

		return tableRepository.findAll().stream()
				.filter(t -> business.getId().equals(t.getBusiness().getId()))
				.findFirst()
				.orElseThrow()
				.getId();
	}
}
