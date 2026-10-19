package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.hamcrest.Matchers;
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

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.ComboProduct;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Contract for the public menu's combo visibility: the carta
 * ({@code GET /api/menu/{slug}}) must only offer ACTIVE combos — the panel's
 * on/off switch ({@code PUT /api/combos/{id}/activar}) has to be able to take
 * a combo off the storefront.
 *
 * <p>The admin list ({@code GET /api/combos}) is the other side of the same
 * contract and must keep returning INACTIVE combos too: the panel needs them
 * in the list to be able to switch them back on. Asserting both in the same
 * unit is what proves the right query changed.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy}. {@code @Transactional} rolls back every fixture
 * at test end.</p>
 */
@SpringBootTest
@Transactional
class PublicMenuComboActiveFilterTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_708_101L;

	private static final String SLUG = "combo-active-filter-a";

	private static final String OWNER_USERNAME = "combo-active-filter-owner";

	private static final BigDecimal COMBO_PRICE = new BigDecimal("250.00");

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
	private JwtUtil jwtUtil;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private ComboRepository comboRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Category category;

	private Product component;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);

		Business business = businessRepository.findById(BUSINESS_ID).orElseThrow();

		category = new Category();
		category.setBusiness(business);
		category.setName("Combo Active Filter Cat");
		category = categoryRepository.saveAndFlush(category);

		component = new Product();
		component.setCategory(category);
		component.setName("Combo Active Filter Component");
		component.setPrice(new BigDecimal("100.00"));
		component.setCost(new BigDecimal("20.00"));
		component.setStock(5);
		component.setActive(true);
		component = productRepository.saveAndFlush(component);
	}

	private Combo combo(String name, boolean active) {
		Combo c = new Combo();
		c.setName(name);
		c.setPrice(COMBO_PRICE);
		c.setCategory(category);
		c.setActive(active);

		ComboProduct cp = new ComboProduct();
		cp.setCombo(c);
		cp.setProduct(component);
		cp.setQuantity(BigDecimal.ONE);
		c.setItems(new ArrayList<>(List.of(cp)));

		return comboRepository.saveAndFlush(c);
	}

	/**
	 * The storefront offers the active combo and the combo toggled off is
	 * GONE from the carta — the switch is functional, not cosmetic.
	 */
	@Test
	void publicMenuContainsActiveComboButNotInactiveOne() throws Exception {

		combo("Combo Visible", true);
		combo("Combo Apagado", false);

		entityManager.flush();
		entityManager.clear();

		mockMvc.perform(get("/api/menu/" + SLUG)
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.combos[*].name", Matchers.contains("Combo Visible")));
	}

	/**
	 * The admin list still returns BOTH combos — active and inactive. The
	 * panel needs the inactive one present to be able to switch it back on;
	 * filtering it away here would orphan the switch.
	 */
	@Test
	void adminComboListStillReturnsBothActiveAndInactiveCombos() throws Exception {

		combo("Combo Visible", true);
		combo("Combo Apagado", false);

		entityManager.flush();
		entityManager.clear();

		mockMvc.perform(get("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name", Matchers.containsInAnyOrder(
						"Combo Visible", "Combo Apagado")))
				.andExpect(jsonPath("$[?(@.name == 'Combo Apagado')].active", Matchers.contains(false)));
	}

	/**
	 * Regression: the active combo that stays on the carta is still fully
	 * mapped — price and its component items, same shape as before the
	 * WHERE clause gained the active predicate.
	 */
	@Test
	void activeComboInPublicMenuIsStillFullyMapped() throws Exception {

		combo("Combo Visible", true);

		entityManager.flush();
		entityManager.clear();

		String body = mockMvc.perform(get("/api/menu/" + SLUG)
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.combos", Matchers.hasSize(1)))
				.andExpect(jsonPath("$.combos[0].name").value("Combo Visible"))
				.andExpect(jsonPath("$.combos[0].price").value(250.00))
				.andExpect(jsonPath("$.combos[0].items", Matchers.hasSize(1)))
				.andExpect(jsonPath("$.combos[0].items[*].productName",
						Matchers.contains("Combo Active Filter Component")))
				.andReturn().getResponse().getContentAsString();

		assertThat(body)
				.as("the menu payload must carry the active combo with its full mapping")
				.contains("Combo Visible");
	}

}
