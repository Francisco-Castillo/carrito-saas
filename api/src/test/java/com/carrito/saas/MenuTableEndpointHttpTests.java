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
 * Public contract of {@code GET /api/menu/table/{id}} — the small anonymous
 * endpoint the customer menu calls to show a table badge when the scan of a
 * per-table QR opened the page with {@code ?table=<id>}:
 *
 * <ul>
 * <li>a table of the deployment's single business answers 200 with ONLY its
 * public identity ({@code id}, {@code tableNumber}, {@code tableName}) — no
 * {@code qrToken}, no {@code qrUrl}, no internal state;</li>
 * <li>an unknown id answers 404, and so does a deployment with more than one
 * business (the slugless public surface fails closed, same as
 * {@code GET /api/menu});</li>
 * <li>the endpoint is anonymous — it lives under {@code /api/menu/**}, which
 * the security config already permits.</li>
 * </ul>
 *
 * <p>MockMvc is assembled manually with the real security
 * {@link FilterChainProxy}; every call here carries NO Authorization header,
 * which is the point of the surface.</p>
 */
@SpringBootTest
@Transactional
class MenuTableEndpointHttpTests {

	private static final Long BUSINESS_ID = 987_654_360L;

	private static final String SLUG = "menu-table-endpoint-business";

	private static final String USERNAME = "menu-table-endpoint-owner";

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

		// The slugless public surface resolves THE deployment's single business
		// and fails closed on more than one. The shared dev database carries
		// leftover committed businesses from real usage, so the suite makes the
		// table deterministic inside its own transaction (rolled back at the
		// end — no committed data is ever touched). Deletion follows the FK
		// chain, dependents first.
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
		businessRepository.deleteAll();
	}

	@Test
	void answersThePublicIdentityOfATableOfTheSingleBusinessAnonymously() throws Exception {

		BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, USERNAME);
		RestaurantTable table = saveTable("Salon principal", 5);

		mockMvc.perform(get("/api/menu/table/{id}", table.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(table.getId()))
				.andExpect(jsonPath("$.tableNumber").value(5))
				.andExpect(jsonPath("$.tableName").value("Salon principal"))
				.andExpect(jsonPath("$.qrToken").doesNotExist())
				.andExpect(jsonPath("$.qrUrl").doesNotExist())
				.andExpect(jsonPath("$.status").doesNotExist());
	}

	@Test
	void unknownTableIdAnswersNotFound() throws Exception {

		BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, USERNAME);

		mockMvc.perform(get("/api/menu/table/{id}", 999_999_999L))
				.andExpect(status().isNotFound());
	}

	@Test
	void aDeploymentWithMoreThanOneBusinessFailsClosed() throws Exception {

		BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, USERNAME);
		saveTable("Salon principal", 5);

		// A second business breaks the single-business contract of the public
		// surface: the resolver must fail closed with NOT_FOUND, never guess.
		Business second = new Business();
		second.setId(BUSINESS_ID + 1);
		second.setName("Otro local");
		second.setSlug("otro-local-slug");
		second.setActive(true);
		businessRepository.saveAndFlush(second);

		mockMvc.perform(get("/api/menu/table/{id}", tableIdOfFirstBusiness()))
				.andExpect(status().isNotFound());
	}

	private long tableIdOfFirstBusiness() {
		return tableRepository.findAll().stream()
				.filter(t -> BUSINESS_ID.equals(t.getBusiness().getId()))
				.findFirst()
				.orElseThrow()
				.getId();
	}

	private RestaurantTable saveTable(String tableName, int tableNumber) {

		RestaurantTable table = new RestaurantTable();
		table.setBusiness(businessRepository.findById(BUSINESS_ID).orElseThrow());
		table.setTableNumber(tableNumber);
		table.setTableName(tableName);
		table.setQrToken(UUID.randomUUID().toString());
		table.setStatus(TableStatus.AVAILABLE);
		table.setCreatedAt(LocalDateTime.now());
		table.setUpdatedAt(LocalDateTime.now());
		return tableRepository.saveAndFlush(table);
	}
}
