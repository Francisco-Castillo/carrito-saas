package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.carrito.saas.repository.jpa.ComboProductRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Admin panel contract for {@code /api/products} and {@code /api/combos}.
 *
 * <p>The admin panel lists products (active AND inactive, it shows a status
 * column), flips a product's {@code active} flag, deletes products and lists
 * combos. All four routes are authenticated and scoped to the caller's
 * business: the business id comes from the JWT, never from the request, and a
 * foreign id resolves as 404 — never as a silent success.</p>
 *
 * <p>{@code DELETE} on a product that is part of any combo must fail
 * explicitly with 409 and a message naming the combo, because
 * {@code combo_products.producto_id} carries a real RESTRICT foreign key: a
 * hard delete would otherwise surface as a generic 500. A product that only
 * appears in historical orders is deletable ({@code order_items.product_id}
 * has no FK).</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy} (Spring Boot 4 moved
 * {@code @AutoConfigureMockMvc} into a module
 * {@code spring-boot-starter-test} does not bring). {@code @Transactional}
 * rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class AdminProductAndComboEndpointTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_701_101L;
	private static final Long BUSINESS_B_ID = 987_701_102L;

	private static final String SLUG_A = "admin-product-a";
	private static final String SLUG_B = "admin-product-b";

	private static final String OWNER_USERNAME = "admin-product-owner";

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
	private ComboProductRepository comboProductRepository;

	@PersistenceContext
	private EntityManager entityManager;

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

		Business businessB = BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_B_ID, SLUG_B);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Admin Cat A"));
		categoryB = categoryRepository.saveAndFlush(category(businessB, "Admin Cat B"));
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
	 * None of the four admin routes matches a {@code permitAll} matcher: an
	 * anonymous caller is rejected by the filter chain's entry point (401),
	 * never reaches the controller.
	 */
	@Test
	void anonymousCallerIsRejectedOnEveryAdminRoute() throws Exception {

		mockMvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/combos")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/api/products/1/activar?activo=true")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/products/1")).andExpect(status().isUnauthorized());
	}

	/**
	 * The admin list must return ALL of the caller's products — active and
	 * inactive, because the admin shows a status column — and never a row of
	 * another business. The id set of the response must be exactly the two own
	 * rows, both statuses included.
	 */
	@Test
	void listReturnsOnlyOwnProductsIncludingInactive() throws Exception {

		Product ownActive = product(categoryA, "Admin Own Active", true);
		Product ownInactive = product(categoryA, "Admin Own Inactive", false);
		Product foreignActive = product(categoryB, "Admin Foreign Active", true);
		Product foreignInactive = product(categoryB, "Admin Foreign Inactive", false);

		String body = mockMvc.perform(get("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		ObjectMapper mapper = new ObjectMapper();
		JsonNode root = mapper.readTree(body);

		List<Long> returnedIds = new ArrayList<>();
		root.forEach(node -> returnedIds.add(node.path("id").asLong()));

		assertThat(returnedIds)
				.as("the admin list must contain exactly the caller's products, active and inactive")
				.containsExactlyInAnyOrder(ownActive.getId(), ownInactive.getId());

		assertThat(returnedIds)
				.as("no product of another business may ever appear in the list")
				.doesNotContain(foreignActive.getId(), foreignInactive.getId());

		boolean inactiveReturned = false;
		for (JsonNode node : root) {
			if (node.path("id").asLong() == ownInactive.getId()) {
				inactiveReturned = node.path("active").asBoolean();
			}
		}
		assertThat(inactiveReturned)
				.as("the inactive own product must be listed with active=false, not filtered out")
				.isFalse();
	}

	/**
	 * Both A's products and B's products exist; the response must name only
	 * the own rows. Belt-and-braces on top of the id-set assertion above.
	 */
	@Test
	void listContainsExactlyTheOwnProducts() throws Exception {

		Product ownOne = product(categoryA, "Admin Exact One", true);
		Product ownTwo = product(categoryA, "Admin Exact Two", false);
		product(categoryB, "Admin Exact Foreign", true);

		String body = mockMvc.perform(get("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body)
				.contains(String.valueOf(ownOne.getId()))
				.contains(String.valueOf(ownTwo.getId()))
				.doesNotContain("Admin Exact Foreign");
	}

	/**
	 * Activation flips the flag both ways and the new value is persisted
	 * (read back through the repository after flush + clear, i.e. a fresh
	 * SELECT against PostgreSQL).
	 */
	@Test
	void activarFlipsActiveBothWaysAndPersists() throws Exception {

		Product own = product(categoryA, "Admin Flip Product", true);

		mockMvc.perform(put("/api/products/" + own.getId() + "/activar?activo=false")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(own.getId()))
				.andExpect(jsonPath("$.active").value(false));

		entityManager.flush();
		entityManager.clear();

		assertThat(productRepository.findById(own.getId()).orElseThrow().isActive())
				.as("activo=false must persist active=false")
				.isFalse();

		mockMvc.perform(put("/api/products/" + own.getId() + "/activar?activo=true")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));

		entityManager.flush();
		entityManager.clear();

		assertThat(productRepository.findById(own.getId()).orElseThrow().isActive())
				.as("activo=true must persist active=true")
				.isTrue();
	}

	/**
	 * A foreign product id is invisible: activation resolves as 404 and the
	 * foreign row stays exactly as it was.
	 */
	@Test
	void activarForeignProductReturns404AndLeavesItUntouched() throws Exception {

		Product foreign = product(categoryB, "Admin Foreign Flip", true);

		mockMvc.perform(put("/api/products/" + foreign.getId() + "/activar?activo=false")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		assertThat(productRepository.findById(foreign.getId()).orElseThrow().isActive())
				.as("a cross-tenant activation attempt must not mutate the foreign row")
				.isTrue();
	}

	/** A foreign product id must not be deletable: 404, row survives. */
	@Test
	void deleteForeignProductReturns404AndLeavesItUntouched() throws Exception {

		Product foreign = product(categoryB, "Admin Foreign Delete", true);

		mockMvc.perform(delete("/api/products/" + foreign.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		assertThat(productRepository.findById(foreign.getId()))
				.as("a cross-tenant delete attempt must not remove the foreign row")
				.isPresent();
	}

	/**
	 * {@code combo_products.producto_id} carries a RESTRICT foreign key, so a
	 * product used by any combo cannot be hard-deleted. The failure must be
	 * explicit — 409 with a message naming the combo — and no combo row may be
	 * removed as a side effect.
	 */
	@Test
	void deleteProductUsedByComboReturns409WithMessage() throws Exception {

		Product used = product(categoryA, "Admin Used By Combo", true);
		Combo ownCombo = combo(categoryA, "Admin Own Combo", used);

		mockMvc.perform(delete("/api/products/" + used.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message", Matchers.containsStringIgnoringCase("combo")));

		entityManager.flush();
		entityManager.clear();

		assertThat(productRepository.findById(used.getId()))
				.as("a product used by a combo must survive the rejected delete")
				.isPresent();

		assertThat(comboProductRepository.findAll().stream()
						.filter(cp -> cp.getCombo() != null && cp.getCombo().getId().equals(ownCombo.getId()))
						.count())
				.as("the rejected delete must not remove combo rows")
				.isEqualTo(1L);
	}

	/** A product that no combo references is deletable: 204, row gone. */
	@Test
	void deleteFreeProductSucceedsAndIsGone() throws Exception {

		Product free = product(categoryA, "Admin Free Product", true);

		mockMvc.perform(delete("/api/products/" + free.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNoContent());

		entityManager.flush();
		entityManager.clear();

		assertThat(productRepository.findById(free.getId()))
				.as("a free product must be really deleted")
				.isEmpty();
	}

	/** GET /api/combos must list only the caller's combos, with their items. */
	@Test
	void combosListIsScopedToCallerBusiness() throws Exception {

		Product componentA = product(categoryA, "Admin Combo Component A", true);
		Product componentB = product(categoryB, "Admin Combo Component B", true);

		Combo ownCombo = combo(categoryA, "Admin Own Combo List", componentA);
		Combo foreignCombo = combo(categoryB, "Admin Foreign Combo List", componentB);

		mockMvc.perform(get("/api/combos")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].id", Matchers.hasItem(ownCombo.getId().intValue())))
				.andExpect(jsonPath("$[*].id", Matchers.not(Matchers.hasItem(foreignCombo.getId().intValue()))))
				.andExpect(jsonPath("$[0].items[*].productName",
						Matchers.hasItem("Admin Combo Component A")));
	}

	/**
	 * Creating a product answers on the route the rest of the product API uses.
	 *
	 * <p>It used to be {@code POST /api/productos} — the only Spanish segment
	 * among {@code /api/products/**} — and nothing called it: not the panel (it
	 * had no create UI), not the legacy frontend, not the tests. That is why the
	 * inconsistency survived, and why renaming it is safe.</p>
	 */
	@Test
	void createProductAnswersOnTheProductsPath() throws Exception {

		String body = """
				{"categoryId": %d, "name": "Admin Created", "description": "Creado en el test",
				 "price": 150.00, "cost": 30.00, "stock": 3, "active": true}
				""".formatted(categoryA.getId());

		mockMvc.perform(post("/api/products")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Admin Created"));

		// Y el camino viejo ya no responde: no quedan dos puertas al mismo recurso.
		mockMvc.perform(post("/api/productos")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound());
	}

	/**
	 * Updating a product lives under its own collection, like every other product
	 * route. It used to be {@code PUT /api/{id}} — a bare id hanging off the API
	 * root, reachable by nothing — and a foreign id must stay a 404 even on the
	 * corrected path.
	 */
	@Test
	void updateProductAnswersOnTheProductsPathAndStaysScoped() throws Exception {

		Product own = product(categoryA, "Admin Before", true);
		Product foreign = product(categoryB, "Admin Foreign", true);

		String body = """
				{"categoryId": %d, "name": "Admin After", "description": "Editado en el test",
				 "price": 175.00, "cost": 35.00, "stock": 4, "active": true}
				""".formatted(categoryA.getId());

		mockMvc.perform(put("/api/products/" + own.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Admin After"));

		mockMvc.perform(put("/api/products/" + foreign.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound());

		mockMvc.perform(put("/api/" + own.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound());
	}

}
