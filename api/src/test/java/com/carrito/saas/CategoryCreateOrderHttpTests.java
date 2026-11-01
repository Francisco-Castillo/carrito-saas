package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * A new category belongs at the END of the owner's list. The server owns that
 * place: {@code orden} is assigned as {@code max(orden) + 1} for the business
 * and the value the client sends is ignored.
 *
 * <p>The defect these tests pin: creation stored whatever {@code order} the
 * client sent, and the panel sent "table rows + 1". With a category deleted in
 * the middle, rows + 1 repeats a number that already exists, so the new
 * category tied with an older one and landed in the middle of the list instead
 * of at the end — the owner added it and could not find it where expected.
 * Ordering ties are already broken by id (see
 * {@code PublicMenuCategoryOrderTests}), which makes the wrong place stable but
 * still wrong.</p>
 */
@SpringBootTest
@Transactional
class CategoryCreateOrderHttpTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_700_404L;
	private static final Long EMPTY_BUSINESS_ID = 987_700_405L;

	private static final String SLUG = "category-create-order-test-business";
	private static final String EMPTY_SLUG = "category-create-order-empty-business";

	private static final String OWNER_USERNAME = "category-create-order-owner";
	private static final String EMPTY_OWNER_USERNAME = "category-create-order-empty-owner";

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

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;
	private BusinessAuthSeed.AuthenticatedBusiness emptyBusinessOwner;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);
		emptyBusinessOwner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, EMPTY_BUSINESS_ID, EMPTY_SLUG, EMPTY_OWNER_USERNAME);

		Business business = businessRepository.findById(BUSINESS_ID).orElseThrow();
		categoryRepository.saveAndFlush(category(business, "Bebidas", 1));
		categoryRepository.saveAndFlush(category(business, "Postres", 2));
	}

	/**
	 * The client asks for {@code order = 1}, which already belongs to
	 * "Bebidas": the server must place the new category after the last one
	 * instead of tying with the first.
	 */
	@Test
	void aCollidingClientOrderIsIgnoredAndTheCategoryAppendsAtTheEnd() throws Exception {

		String body = """
				{
				  "name": "Empanadas",
				  "order": 1,
				  "active": true
				}
				""";

		mockMvc.perform(post("/api/categorias")
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Empanadas"))
				.andExpect(jsonPath("$.order").value(3));

		// La lista completa es la que ve el dueño en la tabla: la nueva va última.
		mockMvc.perform(get("/api/categorias/todas").header("Authorization", owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].name").value("Bebidas"))
				.andExpect(jsonPath("$[1].name").value("Postres"))
				.andExpect(jsonPath("$[2].name").value("Empanadas"));
	}

	/**
	 * Boundary of the same rule: the first category of a business has nothing
	 * to come after, so it opens the list at 1 — {@code max(orden)} is null
	 * and a null must not leak into the row.
	 */
	@Test
	void theFirstCategoryOfABusinessOpensTheList() throws Exception {

		String body = """
				{
				  "name": "Primera",
				  "active": true
				}
				""";

		mockMvc.perform(post("/api/categorias")
						.header("Authorization", emptyBusinessOwner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.order").value(1));
	}

	private Category category(Business business, String name, Integer orden) {

		Category category = new Category();
		category.setBusiness(business);
		category.setName(name);
		category.setOrden(orden);
		category.setActive(true);
		return category;
	}
}
