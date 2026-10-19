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
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.dto.ComboDTO;
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
import com.carrito.saas.service.mapper.interfaces.IComboMapper;

/**
 * Contract for {@link IComboMapper#toListDTO(List)} and the owner-scoped
 * {@code GET /api/combos} list built on top of it.
 *
 * <p>{@code toListDTO} used to be a TODO returning {@code null}, so
 * {@code ComboServiceImpl.findAllByBusinessId} worked around it mapping item
 * by item. The mapper must map each entity with the same logic as
 * {@code toDTO} (id, name, price, {@code categoryId} from the category, and
 * the items), and the endpoint must return every own combo through it.</p>
 */
@SpringBootTest
@Transactional
class ComboListMappingTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_801_101L;
	private static final Long BUSINESS_B_ID = 987_801_102L;

	private static final String SLUG_A = "combo-list-a";
	private static final String SLUG_B = "combo-list-b";

	private static final String OWNER_USERNAME = "combo-list-owner";

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

	@Autowired
	private IComboMapper iComboMapper;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Category categoryA;
	private Category categoryB;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_B_ID, SLUG_B);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Business businessB = businessRepository.findById(BUSINESS_B_ID).orElseThrow();

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Combo List Cat A"));
		categoryB = categoryRepository.saveAndFlush(category(businessB, "Combo List Cat B"));
	}

	private Category category(Business business, String name) {
		Category c = new Category();
		c.setBusiness(business);
		c.setName(name);
		return c;
	}

	private Product product(Category category, String name) {
		Product p = new Product();
		p.setCategory(category);
		p.setName(name);
		p.setPrice(new BigDecimal("100.00"));
		p.setCost(new BigDecimal("20.00"));
		p.setStock(5);
		p.setActive(true);
		return productRepository.saveAndFlush(p);
	}

	private Combo combo(Category category, String name, Product component) {
		Combo combo = new Combo();
		combo.setName(name);
		combo.setPrice(new BigDecimal("250.00"));
		combo.setCategory(category);

		ComboProduct comboProduct = new ComboProduct();
		comboProduct.setCombo(combo);
		comboProduct.setProduct(component);
		comboProduct.setQuantity(BigDecimal.ONE);
		combo.setItems(new ArrayList<>(List.of(comboProduct)));

		return comboRepository.saveAndFlush(combo);
	}

	/**
	 * {@code toListDTO} must map every entity with the same logic as
	 * {@code toDTO}: id, price, a NON-NULL categoryId resolved from the
	 * category, and the item list with the product name.
	 */
	@Test
	void toListDTOMapsEveryEntityWithItemsAndCategoryId() {

		Combo first = combo(categoryA, "Combo List First", product(categoryA, "Combo List Product One"));
		Combo second = combo(categoryA, "Combo List Second", product(categoryA, "Combo List Product Two"));

		List<ComboDTO> dtos = iComboMapper.toListDTO(List.of(first, second));

		assertThat(dtos)
				.as("toListDTO must not return null: it maps one DTO per entity")
				.isNotNull()
				.hasSize(2);

		ComboDTO firstDto = dtos.stream()
				.filter(d -> d.getId().equals(first.getId()))
				.findFirst()
				.orElseThrow();

		assertThat(firstDto.getName()).isEqualTo("Combo List First");
		assertThat(firstDto.getPrice()).isEqualByComparingTo("250.00");
		assertThat(firstDto.getCategoryId())
				.as("categoryId must be resolved from the combo's category, never null")
				.isNotNull()
				.isEqualTo(categoryA.getId());
		assertThat(firstDto.getItems())
				.as("the combo items must travel with the DTO")
				.hasSize(1);
		assertThat(firstDto.getItems().get(0).getProductName()).isEqualTo("Combo List Product One");
		assertThat(firstDto.getItems().get(0).getQuantity()).isEqualByComparingTo(BigDecimal.ONE);

		ComboDTO secondDto = dtos.stream()
				.filter(d -> d.getId().equals(second.getId()))
				.findFirst()
				.orElseThrow();

		assertThat(secondDto.getName()).isEqualTo("Combo List Second");
		assertThat(secondDto.getCategoryId()).isEqualTo(categoryA.getId());
		assertThat(secondDto.getItems()).hasSize(1);
	}

	/**
	 * {@code GET /api/combos} must expose {@code productId} on every item (on
	 * top of the existing productName and quantity): the admin panel prefills
	 * the edit picker by id because two products can share a name.
	 */
	@Test
	void combosEndpointExposesProductIdPerItemWithNamesAndQuantitiesPreserved() throws Exception {

		Product component = product(categoryA, "Combo List ProductId Product");
		Combo ownCombo = combo(categoryA, "Combo List ProductId Combo", component);

		mockMvc.perform(get("/api/combos")
					.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
					.accept(org.springframework.http.MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == " + ownCombo.getId() + ")].items[0].productId",
					Matchers.hasItem(component.getId().intValue())))
			.andExpect(jsonPath("$[?(@.id == " + ownCombo.getId() + ")].items[0].productName",
					Matchers.hasItem("Combo List ProductId Product")))
			.andExpect(jsonPath("$[?(@.id == " + ownCombo.getId() + ")].items[0].quantity",
					Matchers.hasItem(1)));
	}

	/**
	 * {@code GET /api/combos} must return both own combos through the list
	 * mapper, scoped to the caller's business, each carrying its items and a
	 * non-null categoryId.
	 */
	@Test
	void combosEndpointReturnsBothOwnCombosWithItemsAndCategory() throws Exception {

		Combo ownCombo = combo(categoryA, "Combo List Endpoint Combo", product(categoryA, "Combo List Endpoint Product"));
		combo(categoryB, "Combo List Foreign Combo", product(categoryB, "Combo List Foreign Product"));

		mockMvc.perform(get("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.accept(org.springframework.http.MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].id", Matchers.hasItem(ownCombo.getId().intValue())))
				.andExpect(jsonPath("$[*].name", Matchers.hasItem("Combo List Endpoint Combo")))
				.andExpect(jsonPath("$[*].categoryId", Matchers.everyItem(Matchers.notNullValue())))
				.andExpect(jsonPath("$[0].items[*].productName",
						Matchers.hasItem("Combo List Endpoint Product")))
				.andExpect(jsonPath("$[*].name", Matchers.not(Matchers.hasItem("Combo List Foreign Combo"))));
	}

}
