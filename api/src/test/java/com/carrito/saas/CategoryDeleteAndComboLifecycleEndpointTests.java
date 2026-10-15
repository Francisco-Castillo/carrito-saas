package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboProductRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.OrderItemRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Delete + toggle contract for the admin panel's remaining gaps:
 *
 * <p>{@code DELETE /api/categorias/{id}} — tenant-scoped (a foreign id is
 * 404 and the foreign row is untouched); a category that still has products
 * is refused with 409 naming the blocker; an empty one is deleted with 204.
 * The delete never cascades into products.</p>
 *
 * <p>{@code PUT /api/combos/{id}/activar?activo=} — the same shape as the
 * products toggle: tenant-scoped, flips only the active flag, never disturbs
 * the combo's items.</p>
 *
 * <p>{@code DELETE /api/combos/{id}} — tenant-scoped; the combo's own items
 * ({@code combo_products}) go with it via {@code Combo.items} cascade +
 * orphanRemoval, but a combo referenced by an order is refused with 409
 * naming the blocker, because {@code order_items.combo_id} carries a real
 * JPA foreign key (unlike {@code order_items.product_id}, which has none) and
 * a hard delete would otherwise surface as a generic 500.</p>
 *
 * <p>Fixtures follow the A/B two-business pattern of
 * {@code CrossTenantCategoryWriteIsolationTests} and the helper shape of
 * {@code AdminProductAndComboEndpointTests}. Every state assertion reads
 * back through the repositories after flush + clear, i.e. a fresh SELECT
 * against PostgreSQL.</p>
 */
@SpringBootTest
@Transactional
class CategoryDeleteAndComboLifecycleEndpointTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_705_101L;
	private static final Long BUSINESS_B_ID = 987_705_102L;

	private static final String SLUG_A = "category-delete-a";
	private static final String SLUG_B = "category-delete-b";

	private static final String OWNER_USERNAME = "category-delete-owner";

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

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OrderItemRepository orderItemRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Business businessA;
	private Business businessB;

	private Category categoryA;
	private Category categoryB;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		businessB = BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_B_ID, SLUG_B);

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Categoria Propia"));
		categoryB = categoryRepository.saveAndFlush(category(businessB, "Categoria Ajena"));
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

	private Combo combo(Category category, String name, boolean active, Product... components) {
		Combo combo = new Combo();
		combo.setName(name);
		combo.setPrice(new BigDecimal("250.00"));
		combo.setCategory(category);
		combo.setActive(active);

		List<ComboProduct> items = new ArrayList<>();
		for (Product component : components) {
			ComboProduct cp = new ComboProduct();
			cp.setCombo(combo);
			cp.setProduct(component);
			cp.setQuantity(BigDecimal.ONE);
			items.add(cp);
		}
		combo.setItems(items);

		return comboRepository.saveAndFlush(combo);
	}

	/**
	 * The order-reference case: {@code order_items.combo_id} is a real FK, so
	 * a combo that an order references cannot be hard-deleted without a 500.
	 * The contract: 409 naming the blocker, and both the combo and the order
	 * survive untouched.
	 */
	private Order orderReferencing(Business business, Combo combo) {
		Order order = new Order();
		order.setBusiness(business);
		order.setTotal(combo.getPrice());
		order = orderRepository.saveAndFlush(order);

		OrderItem item = new OrderItem();
		item.setOrder(order);
		item.setCombo(combo);
		item.setProductName(combo.getName());
		item.setPrice(combo.getPrice());
		item.setQuantity(1);
		item.setSubtotal(combo.getPrice());
		orderItemRepository.saveAndFlush(item);

		return order;
	}

	private long comboProductCount(Long comboId) {
		return comboProductRepository.findAll().stream()
				.filter(cp -> cp.getCombo() != null && cp.getCombo().getId().equals(comboId))
				.count();
	}

	// ------------------------------------------------------------------
	// DELETE /api/categorias/{id}
	// ------------------------------------------------------------------

	/** An own category with no products is deletable: 204, row really gone. */
	@Test
	void deleteOwnEmptyCategoryReturns204AndItIsGone() throws Exception {

		Category empty = categoryRepository.saveAndFlush(category(businessA, "Categoria Vacía"));

		mockMvc.perform(delete("/api/categorias/" + empty.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNoContent());

		entityManager.flush();
		entityManager.clear();

		assertThat(categoryRepository.findById(empty.getId()))
				.as("an empty own category must be really deleted")
				.isEmpty();
	}

	/**
	 * A category that still has products is refused with 409 and a message
	 * naming products: the delete must never cascade into products, and both
	 * the category and its products must survive.
	 */
	@Test
	void deleteCategoryWithProductsReturns409AndBothSurvive() throws Exception {

		Product kept = product(categoryA, "Producto de Categoria Con Hijos");
		Product kept2 = product(categoryA, "Segundo Producto de Categoria Con Hijos");

		mockMvc.perform(delete("/api/categorias/" + categoryA.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message", Matchers.containsStringIgnoringCase("producto")));

		entityManager.flush();
		entityManager.clear();

		assertThat(categoryRepository.findById(categoryA.getId()))
				.as("a category with products must survive the rejected delete")
				.isPresent();
		assertThat(productRepository.findById(kept.getId()))
				.as("the delete must never cascade into products")
				.isPresent();
		assertThat(productRepository.findById(kept2.getId()))
				.as("the delete must never cascade into products")
				.isPresent();
	}

	/** A foreign category id is invisible: 404, foreign row untouched. */
	@Test
	void deleteForeignCategoryReturns404AndLeavesItUntouched() throws Exception {

		mockMvc.perform(delete("/api/categorias/" + categoryB.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		assertThat(categoryRepository.findById(categoryB.getId()))
				.as("a cross-tenant delete attempt must not remove the foreign row")
				.isPresent();
	}

	// ------------------------------------------------------------------
	// PUT /api/combos/{id}/activar
	// ------------------------------------------------------------------

	/**
	 * The combo toggle flips the flag both ways and persists the new value
	 * without disturbing the combo's items.
	 */
	@Test
	void activarFlipsComboBothWaysAndPersistsWithoutTouchingItems() throws Exception {

		Product component = product(categoryA, "Componente de Combo Toggle");
		Combo own = combo(categoryA, "Combo Toggle", true, component);

		// El toggle responde el estado nuevo (ComboDTO.active, mapeado por
		// IComboMapperImpl) y los items quedan intactos.
		mockMvc.perform(put("/api/combos/" + own.getId() + "/activar?activo=false")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(own.getId()))
				.andExpect(jsonPath("$.active").value(false));

		entityManager.flush();
		entityManager.clear();

		assertThat(comboRepository.findById(own.getId()).orElseThrow().getActive())
				.as("activo=false must persist active=false")
				.isFalse();
		assertThat(comboProductCount(own.getId()))
				.as("the toggle must not disturb the combo's items")
				.isEqualTo(1L);

		mockMvc.perform(put("/api/combos/" + own.getId() + "/activar?activo=true")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));

		entityManager.flush();
		entityManager.clear();

		assertThat(comboRepository.findById(own.getId()).orElseThrow().getActive())
				.as("activo=true must persist active=true")
				.isTrue();
		assertThat(comboProductCount(own.getId()))
				.as("the toggle must not disturb the combo's items")
				.isEqualTo(1L);
	}

	/** A foreign combo id is invisible to the toggle: 404, row untouched. */
	@Test
	void activarForeignComboReturns404AndLeavesItUntouched() throws Exception {

		Product component = product(categoryB, "Componente de Combo Ajeno Toggle");
		Combo foreign = combo(categoryB, "Combo Ajeno Toggle", true, component);

		mockMvc.perform(put("/api/combos/" + foreign.getId() + "/activar?activo=false")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		assertThat(comboRepository.findById(foreign.getId()).orElseThrow().getActive())
				.as("a cross-tenant activation attempt must not mutate the foreign row")
				.isTrue();
	}

	// ------------------------------------------------------------------
	// DELETE /api/combos/{id}
	// ------------------------------------------------------------------

	/**
	 * An own combo with no orders referencing it is deletable: 204, the combo
	 * row is gone AND its {@code combo_products} rows are gone with it
	 * (asserted by count, not just the combo row).
	 */
	@Test
	void deleteOwnComboReturns204AndComboProductRowsAreGone() throws Exception {

		Product componentA = product(categoryA, "Componente Uno de Combo Borrable");
		Product componentB = product(categoryA, "Componente Dos de Combo Borrable");
		Combo own = combo(categoryA, "Combo Borrable", true, componentA, componentB);

		assertThat(comboProductCount(own.getId()))
				.as("fixture sanity: the combo starts with two items")
				.isEqualTo(2L);

		mockMvc.perform(delete("/api/combos/" + own.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNoContent());

		entityManager.flush();
		entityManager.clear();

		assertThat(comboRepository.findById(own.getId()))
				.as("an own unreferenced combo must be really deleted")
				.isEmpty();
		assertThat(comboProductCount(own.getId()))
				.as("the combo's combo_products rows must be gone with it")
				.isZero();
	}

	/** A foreign combo id is invisible to the delete: 404, row untouched. */
	@Test
	void deleteForeignComboReturns404AndLeavesItUntouched() throws Exception {

		Product component = product(categoryB, "Componente de Combo Ajeno Delete");
		Combo foreign = combo(categoryB, "Combo Ajeno Delete", true, component);

		mockMvc.perform(delete("/api/combos/" + foreign.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNotFound());

		entityManager.flush();
		entityManager.clear();

		assertThat(comboRepository.findById(foreign.getId()))
				.as("a cross-tenant delete attempt must not remove the foreign row")
				.isPresent();
		assertThat(comboProductCount(foreign.getId()))
				.as("a cross-tenant delete attempt must not remove the foreign items either")
				.isEqualTo(1L);
	}

	/**
	 * A combo referenced by an order is refused with 409 and a message naming
	 * the blocker: {@code order_items.combo_id} carries a real FK, so a hard
	 * delete would surface as a generic 500. Both the combo and the order
	 * survive untouched.
	 */
	@Test
	void deleteComboReferencedByOrderReturns409AndBothSurvive() throws Exception {

		Product component = product(categoryA, "Componente de Combo Vendido");
		Combo own = combo(categoryA, "Combo Vendido", true, component);
		Order order = orderReferencing(businessA, own);

		mockMvc.perform(delete("/api/combos/" + own.getId())
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message", Matchers.containsStringIgnoringCase("pedido")));

		entityManager.flush();
		entityManager.clear();

		assertThat(comboRepository.findById(own.getId()))
				.as("a combo referenced by an order must survive the rejected delete")
				.isPresent();
		assertThat(orderRepository.findById(order.getId()))
				.as("the order referencing the combo must survive untouched")
				.isPresent();
	}

}
