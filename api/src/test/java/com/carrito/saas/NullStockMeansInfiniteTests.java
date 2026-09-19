package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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

import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.ComboProduct;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IMenuService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@code Product.stock} documents a three-case convention:
 * {@code null} → infinite stock (drinks, for example), {@code 0} → no stock,
 * {@code > 0} → limited stock. These tests pin that convention end to end.
 *
 * <p>Two defects made an infinite-stock product impossible to order:</p>
 * <ol>
 * <li>the public menu read path filtered with {@code stock > 0}, and in SQL
 * {@code NULL > 0} is not true, so a null-stock product was invisible to the
 * customers it is meant for;</li>
 * <li>the atomic {@code decrementStock} guarded with {@code stock >= :quantity},
 * which is false for {@code NULL}, so even a visible infinite-stock product
 * failed with "Stock insuficiente".</li>
 * </ol>
 *
 * <p>Both halves must hold together: visibility alone would promise a product
 * the order path then refuses. The floor guarantees added by the first
 * hardening slice (no overselling of {@code stock = 0}, no decrement of a
 * numeric stock below the requested quantity, no cross-tenant write) must
 * keep holding unchanged.</p>
 *
 * <p>Menu visibility is asserted through {@link IMenuService#getMenu(String)}
 * (the public read path); orders go through the anonymous public endpoint
 * {@code POST /api/orders/menu/{slug}} with the exact body shape the fixed
 * menu page sends, same as {@code PublicMenuOrderHttpTests}. Repository-level
 * floor/tenant tests read back after flushing and clearing the persistence
 * context — a fresh SELECT against PostgreSQL, not a cached value.
 * {@code @Transactional} rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class NullStockMeansInfiniteTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_700_301L;

	private static final String SLUG = "null-stock-infinite-test-business";

	private static final Long REPO_BUSINESS_ID = 987_700_303L;
	private static final Long FOREIGN_BUSINESS_ID = 987_700_304L;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private IMenuService menuService;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ComboRepository comboRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private OrderRepository orderRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	private Business seedBusiness(Long id, String slug, String name) {

		Business business = new Business();
		business.setId(id);
		business.setName(name);
		business.setSlug(slug);
		return businessRepository.saveAndFlush(business);
	}

	private Category seedCategory(Business business, String name) {

		Category category = new Category();
		category.setBusiness(business);
		category.setName(name);
		return categoryRepository.saveAndFlush(category);
	}

	private Product seedProduct(Category category, String name, String price, Integer stock) {

		Product product = new Product();
		product.setCategory(category);
		product.setName(name);
		product.setPrice(new BigDecimal(price));
		product.setCost(new BigDecimal("10.00"));
		product.setStock(stock);
		product.setActive(true);
		return productRepository.saveAndFlush(product);
	}

	/**
	 * A null-stock product (documented as infinite) must be visible in the
	 * public menu of its own business, while a {@code stock = 0} product must
	 * stay filtered out — the three-case convention, not "no filter at all".
	 */
	@Test
	void nullStockProductIsVisibleInPublicMenuWhileZeroStockStaysHidden() {

		Business business = seedBusiness(BUSINESS_ID, SLUG, "Null Stock Infinite Business");
		Category category = seedCategory(business, "Bebidas");

		seedProduct(category, "Jugo de naranja", "120.00", null);
		seedProduct(category, "Agua sin stock", "90.00", 0);

		MenuDTO menu = menuService.getMenu(SLUG);

		List<String> names = menu.getProducts().stream().map(ProductDTO::getName).toList();
		assertThat(names)
				.as("null stock is infinite, so the product must be offered; 0 is no stock, so it must not")
				.containsExactly("Jugo de naranja");
	}

	/**
	 * The end-to-end property: a customer can order a null-stock product
	 * through the anonymous public menu path and the order is created.
	 */
	@Test
	void nullStockProductCanBeOrderedThroughPublicMenuPath() throws Exception {

		Business business = seedBusiness(BUSINESS_ID, SLUG, "Null Stock Infinite Business");
		Category category = seedCategory(business, "Bebidas");
		Product juice = seedProduct(category, "Jugo de naranja", "120.00", null);

		String body = """
				{
				  "customerName": "Cliente Publico",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 2 } ]
				}
				""".formatted(juice.getId());

		String responseBody = mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").isNotEmpty())
				.andExpect(jsonPath("$.status").value("NEW"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		long createdOrderId = objectMapper.readTree(responseBody).path("orderId").asLong();
		assertThat(createdOrderId).isPositive();

		entityManager.clear();
		assertThat(orderRepository.findById(createdOrderId)).as("the order must be persisted").isPresent();
	}

	/**
	 * Infinite stock is not "decremented to null and then treated as zero":
	 * after a successful order the stock must still be {@code null}.
	 */
	@Test
	void nullStockStaysNullAfterAnOrder() throws Exception {

		Business business = seedBusiness(BUSINESS_ID, SLUG, "Null Stock Infinite Business");
		Category category = seedCategory(business, "Bebidas");
		Product juice = seedProduct(category, "Jugo de naranja", "120.00", null);

		String body = """
				{
				  "customerName": "Cliente Publico",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 3 } ]
				}
				""".formatted(juice.getId());

		String responseBody = mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		long createdOrderId = objectMapper.readTree(responseBody).path("orderId").asLong();

		entityManager.clear();

		assertThat(productRepository.findById(juice.getId()).orElseThrow().getStock())
				.as("an infinite-stock product must remain infinite after an order")
				.isNull();
		assertThat(orderRepository.findById(createdOrderId)).as("the order must be persisted").isPresent();
	}

	/** No regression for the limited-stock case: numeric stock decrements exactly as before. */
	@Test
	void numericStockProductStillDecrementsExactly() throws Exception {

		Business business = seedBusiness(BUSINESS_ID, SLUG, "Null Stock Infinite Business");
		Category category = seedCategory(business, "Comida");
		Product milanesa = seedProduct(category, "Milanesa", "250.00", 10);

		String body = """
				{
				  "customerName": "Cliente Publico",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 2 } ]
				}
				""".formatted(milanesa.getId());

		String responseBody = mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("NEW"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		long createdOrderId = objectMapper.readTree(responseBody).path("orderId").asLong();

		entityManager.clear();

		assertThat(productRepository.findById(milanesa.getId()).orElseThrow().getStock())
				.as("10 - 2: the limited-stock case must not change")
				.isEqualTo(8);
		assertThat(orderRepository.findById(createdOrderId)).isPresent();
	}

	/**
	 * The atomic decrement itself: a null-stock own product must affect
	 * exactly 1 row and leave the stock {@code null}.
	 */
	@Test
	void decrementOfNullStockOwnProductAffectsOneRowAndKeepsStockNull() {

		Business business = seedBusiness(REPO_BUSINESS_ID, "null-stock-repo-business", "Null Stock Repo Business");
		Category category = seedCategory(business, "Bebidas");
		Product infinite = seedProduct(category, "Gaseosa infinita", "80.00", null);

		int updatedRows = productRepository.decrementStock(infinite.getId(), 5, REPO_BUSINESS_ID);

		entityManager.flush();
		entityManager.clear();

		assertThat(updatedRows).as("null stock is infinite: the decrement must succeed").isEqualTo(1);
		assertThat(productRepository.findById(infinite.getId()).orElseThrow().getStock())
				.as("NULL - 5 stays NULL in SQL: infinite is never turned into a number")
				.isNull();
	}

	/**
	 * The hardening floor, case 1: {@code stock = 0} with a quantity above 0
	 * must still affect 0 rows — no overselling, and 0 is not "infinite".
	 */
	@Test
	void zeroStockProductStillCannotBeDecremented() {

		Business business = seedBusiness(REPO_BUSINESS_ID, "null-stock-repo-business", "Null Stock Repo Business");
		Category category = seedCategory(business, "Comida");
		Product empty = seedProduct(category, "Producto sin stock", "80.00", 0);

		int updatedRows = productRepository.decrementStock(empty.getId(), 1, REPO_BUSINESS_ID);

		entityManager.flush();
		entityManager.clear();

		assertThat(updatedRows).as("stock 0 with quantity 1 must affect 0 rows").isZero();
		assertThat(productRepository.findById(empty.getId()).orElseThrow().getStock())
				.as("the failed decrement must not touch the row")
				.isZero();
	}

	/**
	 * The hardening floor, case 2: a numeric stock below the requested
	 * quantity must still affect 0 rows.
	 */
	@Test
	void numericStockBelowQuantityStillAffectsZeroRows() {

		Business business = seedBusiness(REPO_BUSINESS_ID, "null-stock-repo-business", "Null Stock Repo Business");
		Category category = seedCategory(business, "Comida");
		Product scarce = seedProduct(category, "Producto escaso", "80.00", 3);

		int updatedRows = productRepository.decrementStock(scarce.getId(), 5, REPO_BUSINESS_ID);

		entityManager.flush();
		entityManager.clear();

		assertThat(updatedRows).as("stock 3 with quantity 5 must affect 0 rows").isZero();
		assertThat(productRepository.findById(scarce.getId()).orElseThrow().getStock())
				.as("the failed decrement must not touch the row")
				.isEqualTo(3);
	}

	/**
	 * The hardening floor, case 3: a product belonging to another business
	 * must still affect 0 rows, whether its stock is numeric or null.
	 */
	@Test
	void foreignProductIdStillAffectsZeroRowsWhateverItsStock() {

		Business business = seedBusiness(REPO_BUSINESS_ID, "null-stock-repo-business", "Null Stock Repo Business");
		Business foreign = seedBusiness(FOREIGN_BUSINESS_ID, "null-stock-foreign-business", "Null Stock Foreign Business");
		Category ownCategory = seedCategory(business, "Comida");
		Category foreignCategory = seedCategory(foreign, "Comida");

		Product foreignNumeric = seedProduct(foreignCategory, "Ajeno con stock", "80.00", 100);
		Product foreignInfinite = seedProduct(foreignCategory, "Ajeno infinito", "80.00", null);
		seedProduct(ownCategory, "Propio", "80.00", 10);

		assertThat(productRepository.decrementStock(foreignNumeric.getId(), 1, REPO_BUSINESS_ID))
				.as("a foreign numeric-stock product must affect 0 rows")
				.isZero();
		assertThat(productRepository.decrementStock(foreignInfinite.getId(), 1, REPO_BUSINESS_ID))
				.as("a foreign null-stock product must affect 0 rows too — infinite is not cross-tenant")
				.isZero();

		entityManager.flush();
		entityManager.clear();

		assertThat(productRepository.findById(foreignNumeric.getId()).orElseThrow().getStock()).isEqualTo(100);
		assertThat(productRepository.findById(foreignInfinite.getId()).orElseThrow().getStock()).isNull();
	}

	/**
	 * A combo whose component has null stock: the component is infinite, so
	 * the combo must be orderable through the public path and the component
	 * must stay infinite. The combo is validated at order time, not menu time
	 * — the menu has always shown it — so making the component orderable here
	 * keeps the menu promise and the order path consistent.
	 */
	@Test
	void comboWithNullStockComponentIsOrderableAndStaysNull() throws Exception {

		Business business = seedBusiness(BUSINESS_ID, SLUG, "Null Stock Infinite Business");
		Category category = seedCategory(business, "Combos");

		Product soda = seedProduct(category, "Gaseosa infinita", "80.00", null);
		Product burger = seedProduct(category, "Hamburguesa limitada", "400.00", 10);

		Combo combo = new Combo();
		combo.setName("Combo Gaseosa + Hamburguesa");
		combo.setPrice(new BigDecimal("450.00"));
		combo.setCategory(category);

		ComboProduct comboSoda = new ComboProduct();
		comboSoda.setCombo(combo);
		comboSoda.setProduct(soda);
		comboSoda.setQuantity(new BigDecimal("1"));

		ComboProduct comboBurger = new ComboProduct();
		comboBurger.setCombo(combo);
		comboBurger.setProduct(burger);
		comboBurger.setQuantity(new BigDecimal("2"));

		combo.setItems(new ArrayList<>(List.of(comboSoda, comboBurger)));
		combo = comboRepository.saveAndFlush(combo);

		final Long sodaId = soda.getId();
		final Long burgerId = burger.getId();

		String body = """
				{
				  "customerName": "Cliente Combo",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "comboId": %d, "quantity": 2 } ]
				}
				""".formatted(combo.getId());

		String responseBody = mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("NEW"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		long createdOrderId = objectMapper.readTree(responseBody).path("orderId").asLong();

		entityManager.clear();

		assertThat(productRepository.findById(sodaId).orElseThrow().getStock())
				.as("the null-stock component stays infinite")
				.isNull();
		assertThat(productRepository.findById(burgerId).orElseThrow().getStock())
				.as("the numeric component decrements exactly: 10 - 2 x 2")
				.isEqualTo(6);
		assertThat(orderRepository.findById(createdOrderId)).as("the combo order must be persisted").isPresent();
	}

}
