package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contract for {@code GET /api/restaurants/{restaurantId}/products}.
 *
 * <p>The route used to be a dead stub returning {@code null} (an empty 200
 * for any id). It must resolve the caller's business from the JWT and return
 * that business's product list ONLY when the path's {@code restaurantId} is
 * the caller's business; a foreign or non-existent id answers 404 — never a
 * null body, never a silent 200. Without a token the filter chain answers
 * 403, because the route is not in any {@code permitAll} matcher.</p>
 */
@SpringBootTest
@Transactional
class ProductCatalogByIdTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_803_101L;
	private static final Long BUSINESS_B_ID = 987_803_102L;

	private static final String SLUG_A = "catalog-by-id-a";
	private static final String SLUG_B = "catalog-by-id-b";

	private static final String OWNER_USERNAME = "catalog-by-id-owner";

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

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Catalog By Id Cat A"));
		categoryB = categoryRepository.saveAndFlush(category(businessB, "Catalog By Id Cat B"));
	}

	private Category category(Business business, String name) {
		Category c = new Category();
		c.setBusiness(business);
		c.setName(name);
		return c;
	}

	private Product product(Category category, String name, boolean active) {
		Product p = new Product();
		p.setCategory(category);
		p.setName(name);
		p.setPrice(new BigDecimal("100.00"));
		p.setCost(new BigDecimal("20.00"));
		p.setStock(5);
		p.setActive(active);
		return productRepository.saveAndFlush(p);
	}

	/**
	 * Own id + token: 200 with the caller's products — active and inactive —
	 * and never the rows of another business.
	 */
	@Test
	void ownRestaurantIdReturnsCallerProducts() throws Exception {

		Product ownOne = product(categoryA, "Catalog By Id Own One", true);
		Product ownTwo = product(categoryA, "Catalog By Id Own Two", false);
		Product foreign = product(categoryB, "Catalog By Id Foreign", true);

		String body = mockMvc.perform(get("/api/restaurants/" + BUSINESS_A_ID + "/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		ObjectMapper mapper = new ObjectMapper();
		JsonNode root = mapper.readTree(body);

		assertThat(root.isArray())
				.as("the response must be a JSON product list, never an empty null body")
				.isTrue();

		List<Long> returnedIds = new ArrayList<>();
		List<Long> returnedCategoryIds = new ArrayList<>();
		root.forEach(node -> {
			returnedIds.add(node.path("id").asLong());
			returnedCategoryIds.add(node.path("categoryId").asLong());
		});

		// Se compara contra el id del PRODUCTO ajeno. Comparar contra el id de su
		// categoría era una aserción sin sentido —ids de producto contra un id de
		// categoría— que fallaba en cuanto las secuencias compartidas de la base
		// hacían coincidir los números.
		assertThat(returnedIds)
				.as("the catalog must contain exactly the caller's products, active and inactive")
				.containsExactlyInAnyOrder(ownOne.getId(), ownTwo.getId())
				.doesNotContain(foreign.getId());

		// Y ninguna fila devuelta pertenece a la categoría de otro negocio.
		assertThat(returnedCategoryIds)
				.as("no product of the response may live in another business's category")
				.doesNotContain(categoryB.getId());
	}

	/** Another business's id: 404, never a silent 200 with foreign data. */
	@Test
	void foreignRestaurantIdReturns404() throws Exception {

		mockMvc.perform(get("/api/restaurants/" + BUSINESS_B_ID + "/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNotFound());
	}

	/** A non-existent id resolves exactly like a foreign one: 404. */
	@Test
	void unknownRestaurantIdReturns404() throws Exception {

		mockMvc.perform(get("/api/restaurants/987803999/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNotFound());
	}

	/**
	 * No token: 403. The route matches no {@code permitAll} matcher, so the
	 * anonymous caller is rejected by the filter chain and never reaches the
	 * controller.
	 */
	@Test
	void noTokenIsRejectedWith403() throws Exception {

		mockMvc.perform(get("/api/restaurants/" + BUSINESS_A_ID + "/products"))
				.andExpect(status().isForbidden());
	}

}
