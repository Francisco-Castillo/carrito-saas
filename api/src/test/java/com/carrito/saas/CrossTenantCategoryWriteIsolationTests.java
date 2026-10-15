package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Cross-tenant write isolation contract for the category endpoints.
 *
 * <p>{@code PUT /api/categorias/{id}}, {@code PUT /api/categorias/orden} and
 * {@code GET /api/categorias/{id}} take row ids from the request while the
 * tenant anchor is the JWT. Without a business predicate, an authenticated
 * owner of business A could rename, reorder, deactivate or read business B's
 * category by raw id. The contract under test: a foreign row id resolves as
 * 404 (never a silent success), the foreign row is untouched on rejection,
 * validation matches the create path (name required + trimmed, duplicate
 * rejected with the same Spanish messages), and the positive paths keep
 * working with their existing response codes.</p>
 *
 * <p>Fixtures follow the A/B two-business pattern of
 * {@code CrossTenantProductWriteIsolationTests}: two businesses, each with
 * its own category, seeded through the repositories and rolled back by
 * {@code @Transactional}. Every state assertion reads back through the
 * repository after flush + clear, i.e. a fresh SELECT against PostgreSQL.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy}. The business id comes from the JWT minted by
 * {@link BusinessAuthSeed#seedOwner}, never from the request.</p>
 */
@SpringBootTest
@Transactional
class CrossTenantCategoryWriteIsolationTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_703_101L;
	private static final Long BUSINESS_B_ID = 987_703_102L;

	private static final String SLUG_A = "cross-tenant-cat-a";
	private static final String SLUG_B = "cross-tenant-cat-b";

	private static final String OWNER_USERNAME = "cross-tenant-cat-owner";

	private static final String FOREIGN_NAME = "Categoria Ajena Original";
	private static final Integer FOREIGN_ORDEN = 5;
	private static final Boolean FOREIGN_ACTIVE = Boolean.TRUE;

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

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Category categoryA;
	private Category categoryB;
	private Category categoryB2;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Business businessB = BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_B_ID, SLUG_B);

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Categoria Propia Original", 1, true));
		categoryB = categoryRepository.saveAndFlush(category(businessB, FOREIGN_NAME, FOREIGN_ORDEN, FOREIGN_ACTIVE));
		categoryB2 = categoryRepository.saveAndFlush(category(businessB, "Categoria Ajena Dos", 6, true));
	}

	private Category category(Business business, String name, Integer orden, Boolean active) {
		Category c = new Category();
		c.setBusiness(business);
		c.setName(name);
		c.setOrden(orden);
		c.setActive(active);
		return c;
	}

	private String updateBody(String name, Integer order, Boolean active) {
		return """
				{
				  "name": "%s",
				  "order": %d,
				  "active": %b
				}
				""".formatted(name, order, active);
	}

	private void assertForeignRowByteIdentical() {
		Category foreignAfter = categoryRepository.findById(categoryB.getId()).orElseThrow();
		assertThat(foreignAfter.getName())
				.as("a cross-tenant attempt must not rename the foreign category")
				.isEqualTo(FOREIGN_NAME);
		assertThat(foreignAfter.getOrden())
				.as("a cross-tenant attempt must not reorder the foreign category")
				.isEqualTo(FOREIGN_ORDEN);
		assertThat(foreignAfter.getActive())
				.as("a cross-tenant attempt must not deactivate the foreign category")
				.isEqualTo(FOREIGN_ACTIVE);
	}

	/**
	 * Updating business B's category through A's credentials must resolve as
	 * 404 — never as a silent success — and the foreign row must keep its
	 * original name, order and active flag, byte-identical.
	 */
	@Test
	void updatingForeignCategoryReturns404AndLeavesForeignRowUntouched() throws Exception {

		mockMvc.perform(put("/api/categorias/" + categoryB.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("Nombre Atacante", 9, false)))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		assertForeignRowByteIdentical();
	}

	/**
	 * A foreign id inside the reorder payload must be rejected (404) and the
	 * foreign rows must keep their original order — the whole reorder is one
	 * transaction, so nothing is applied.
	 */
	@Test
	void reorderingWithForeignIdIsRejectedAndForeignRowsKeepTheirOrder() throws Exception {

		mockMvc.perform(put("/api/categorias/orden")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("[%d,%d]".formatted(categoryB.getId(), categoryA.getId())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Categoría no encontrada"));

		entityManager.flush();
		entityManager.clear();

		assertForeignRowByteIdentical();

		Category ownAfter = categoryRepository.findById(categoryA.getId()).orElseThrow();
		assertThat(ownAfter.getOrden())
				.as("a rejected reorder must not move the own category either")
				.isEqualTo(1);
	}

	/**
	 * Reading another business's category by raw id must resolve as 404,
	 * never leak the foreign row's contents.
	 */
	@Test
	void gettingForeignCategoryReturns404AndLeaksNoData() throws Exception {

		mockMvc.perform(get("/api/categorias/" + categoryB.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNotFound());
	}

	/**
	 * Positive control: updating an own category must keep the existing 200
	 * and persist name, order and active.
	 */
	@Test
	void updatingOwnCategoryReturns200AndPersists() throws Exception {

		mockMvc.perform(put("/api/categorias/" + categoryA.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("Categoria Renombrada", 4, false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(categoryA.getId()));

		entityManager.flush();
		entityManager.clear();

		Category ownAfter = categoryRepository.findById(categoryA.getId()).orElseThrow();
		assertThat(ownAfter.getName())
				.as("an accepted update must persist the new name")
				.isEqualTo("Categoria Renombrada");
		assertThat(ownAfter.getOrden())
				.as("an accepted update must persist the new order")
				.isEqualTo(4);
		assertThat(ownAfter.getActive())
				.as("an accepted update must persist the new active flag")
				.isFalse();
	}

	/**
	 * A blank name on update must be rejected with the create path's 400
	 * message: the name is required, exactly as in {@code crearCategoria}.
	 */
	@Test
	void updatingOwnCategoryWithBlankNameReturns400() throws Exception {

		mockMvc.perform(put("/api/categorias/" + categoryA.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("   ", 2, true)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El nombre de la categoría es obligatorio"));
	}

	/**
	 * A duplicate name within the caller's own business must be rejected with
	 * the create path's 409 + message. Renaming a category to its own current
	 * name is not a duplicate.
	 */
	@Test
	void updatingOwnCategoryToDuplicateNameReturns409() throws Exception {

		Category own2 = categoryRepository.saveAndFlush(
				category(businessRepository.findById(BUSINESS_A_ID).orElseThrow(), "Categoria Duplicada", 2, true));

		mockMvc.perform(put("/api/categorias/" + own2.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("Categoria Propia Original", 2, true)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe una categoría con ese nombre para este negocio"));

		entityManager.flush();
		entityManager.clear();

		Category own2After = categoryRepository.findById(own2.getId()).orElseThrow();
		assertThat(own2After.getName())
				.as("a rejected duplicate update must not rename the category")
				.isEqualTo("Categoria Duplicada");
	}

	/**
	 * The persisted name must be trimmed, exactly as the create path does.
	 */
	@Test
	void updatingOwnCategoryTrimsNameOnPersist() throws Exception {

		mockMvc.perform(put("/api/categorias/" + categoryA.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("   Categoria Con Espacios   ", 1, true)))
				.andExpect(status().isOk());

		entityManager.flush();
		entityManager.clear();

		Category ownAfter = categoryRepository.findById(categoryA.getId()).orElseThrow();
		assertThat(ownAfter.getName())
				.as("an accepted update must persist the trimmed name")
				.isEqualTo("Categoria Con Espacios");
	}

}
