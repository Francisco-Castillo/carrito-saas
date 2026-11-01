package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * The two category listings the panel and the pickers need are different
 * reads, and they must stay different.
 *
 * <p>{@code GET /api/categorias} feeds the category selectors of the product
 * and combo forms, so it offers only active categories — that is the
 * pre-existing behavior and it must not change. {@code GET
 * /api/categorias/todas} is the admin table: it has to include the inactive
 * ones, because the ON/OFF switch lives in that table and without the row
 * there is no way to turn a category back on.</p>
 *
 * <p>Fixtures seed one category in the middle of the order that is off, so the
 * admin listing proves both things at once: the inactive row travels and the
 * order is the owner's.</p>
 */
@SpringBootTest
@Transactional
class AdminCategoryListHttpTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_700_403L;

	private static final String SLUG = "admin-category-list-test-business";

	private static final String OWNER_USERNAME = "admin-category-list-owner";

	/** Segundo negocio: el anclaje del tenant en la superficie nueva. */
	private static final Long FOREIGN_BUSINESS_ID = 987_700_407L;
	private static final String FOREIGN_SLUG = "admin-category-list-foreign-business";
	private static final String FOREIGN_OWNER_USERNAME = "admin-category-list-foreign-owner";

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
	private BusinessAuthSeed.AuthenticatedBusiness foreignOwner;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);
		Business business = businessRepository.findById(BUSINESS_ID).orElseThrow();

		// Insertadas fuera del orden del dueño a propósito.
		categoryRepository.saveAndFlush(category(business, "Postres", 3, true));
		categoryRepository.saveAndFlush(category(business, "Bebidas", 1, true));
		categoryRepository.saveAndFlush(category(business, "Promos viejas", 2, false));

		// El negocio ajeno tiene una categoría INACTIVA a propósito: es la fila
		// que sólo la superficie nueva expone.
		foreignOwner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, FOREIGN_BUSINESS_ID, FOREIGN_SLUG, FOREIGN_OWNER_USERNAME);
		categoryRepository.saveAndFlush(
				category(businessRepository.findById(FOREIGN_BUSINESS_ID).orElseThrow(), "Categoria Ajena Inactiva", 1, false));
	}

	@Test
	void theAdminListIncludesInactiveCategoriesSoTheSwitchCanBeUndone() throws Exception {

		mockMvc.perform(get("/api/categorias/todas").header("Authorization", owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].name").value("Bebidas"))
				.andExpect(jsonPath("$[1].name").value("Promos viejas"))
				.andExpect(jsonPath("$[1].active").value(false))
				.andExpect(jsonPath("$[2].name").value("Postres"));
	}

	/**
	 * Same endpoint, the sibling read: the list the product and combo pickers
	 * consume keeps offering only active categories.
	 */
	@Test
	void theActiveOnlyListKeepsOfferingOnlyActiveCategories() throws Exception {

		mockMvc.perform(get("/api/categorias").header("Authorization", owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("Bebidas"))
				.andExpect(jsonPath("$[1].name").value("Postres"));
	}

	/**
	 * El switch de la tabla manda sólo {@code {name, active}}: no toca el orden.
	 * El servicio aplica únicamente los campos no nulos, así que un PUT sin
	 * {@code order} tiene que dejar la categoría donde estaba. Este pin existe
	 * porque el panel depende de esa semántica: si el update pasara a ser un
	 * reemplazo completo, prender o apagar una categoría la reordenaría en
	 * silencio.
	 */
	@Test
	void aPutWithoutOrderLeavesTheCategoryWhereItWas() throws Exception {

		Long promosId = categoryRepository.findByBusinessIdOrdered(BUSINESS_ID).stream()
				.filter(c -> "Promos viejas".equals(c.getName()))
				.findFirst()
				.orElseThrow()
				.getId();

		String body = """
				{
				  "name": "Promos viejas",
				  "active": true
				}
				""";

		mockMvc.perform(put("/api/categorias/" + promosId)
						.header("Authorization", owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/categorias/todas").header("Authorization", owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[1].name").value("Promos viejas"))
				.andExpect(jsonPath("$[1].active").value(true))
				.andExpect(jsonPath("$[1].order").value(2));
	}

	/**
	 * La superficie nueva no puede aflojar el anclaje del tenant: `/todas`
	 * expone también las filas inactivas, así que una fuga acá mostraría
	 * categorías apagadas de otro negocio. El negocio sale del JWT, como en
	 * {@code /api/categorias}, y un token de otro negocio no ve estas filas.
	 */
	@Test
	void theAdminListIsAnchoredToTheTokenBusinessEvenForInactiveRows() throws Exception {

		mockMvc.perform(get("/api/categorias/todas").header("Authorization", foreignOwner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Categoria Ajena Inactiva"));
	}

	private Category category(Business business, String name, Integer orden, boolean active) {

		Category category = new Category();
		category.setBusiness(business);
		category.setName(name);
		category.setOrden(orden);
		category.setActive(active);
		return category;
	}
}
