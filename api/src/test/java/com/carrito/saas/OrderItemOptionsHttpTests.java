package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;

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
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.entity.ProductOption;
import com.carrito.saas.repository.entity.ProductOptionGroup;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.impl.OrderServiceImpl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Order-flow contract for product options (odd/tasks/product-options.md T3,
 * S4-S6) through the exact anonymous path the browser takes: POST
 * {@code /api/orders/menu/{slug}}.
 *
 * <p>Selections are validated against the product's groups (required must be
 * answered, non-multiple takes exactly one, multiple respects min/max, ids
 * must belong to the product), the unit price is recalculated with the price
 * deltas, the snapshot is persisted on the order item and exposed to the
 * kitchen DTO as "Grupo: Opción". A product without groups keeps working
 * exactly as before (S6).</p>
 */
@SpringBootTest
@Transactional
class OrderItemOptionsHttpTests {

	private static final Long BUSINESS_ID = 987_804_101L;

	private static final String SLUG = "order-item-options-test";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private BusinessHoursRepository businessHoursRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private OrderRepository orderRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private Business business;

	private Product product;

	private Long breadWhiteId;

	private Long breadIntegralId;

	private Long cheddarId;

	private Long pancetaId;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Order Item Options Test");
		business.setSlug(SLUG);
		businessRepository.saveAndFlush(business);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, business.getId());

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Menu");
		category = categoryRepository.saveAndFlush(category);

		product = new Product();
		product.setCategory(category);
		product.setName("Burger Opciones Test");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("20.00"));
		product.setStock(10);
		product.setActive(true);
		product.setOptionGroups(new ArrayList<>());

		ProductOptionGroup bread = new ProductOptionGroup();
		bread.setProduct(product);
		bread.setName("Tipo de pan");
		bread.setRequired(true);
		bread.setMultiple(false);
		bread.setOptions(new ArrayList<>());
		breadWhiteId = option(bread, "Blanco", "0");
		breadIntegralId = option(bread, "Integral", "200");

		ProductOptionGroup extras = new ProductOptionGroup();
		extras.setProduct(product);
		extras.setName("Extras");
		extras.setRequired(false);
		extras.setMultiple(true);
		extras.setMinSelections(0);
		extras.setMaxSelections(1);
		extras.setOptions(new ArrayList<>());
		cheddarId = option(extras, "Cheddar", "300");
		pancetaId = option(extras, "Panceta", "500");

		product.getOptionGroups().add(bread);
		product.getOptionGroups().add(extras);
		product = productRepository.saveAndFlush(product);
	}

	private Long option(ProductOptionGroup group, String name, String delta) {
		ProductOption o = new ProductOption();
		o.setOptionGroup(group);
		o.setName(name);
		o.setPriceDelta(new BigDecimal(delta));
		o.setPosition(group.getOptions().size());
		group.getOptions().add(o);
		return null; // ids assigned on flush; resolved after save below
	}

	private Product freshProduct() {
		entityManager.flush();
		entityManager.clear();
		product = productRepository.findById(product.getId()).orElseThrow();
		return product;
	}

	private String orderBody(String itemsJson) {
		return """
				{
				  "customerName": "Cliente Opciones",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": %s
				}
				""".formatted(itemsJson);
	}

	private String itemsJson(String selections) {
		return """
				[ { "productId": %d, "quantity": 2, "selectedOptionIds": [%s] } ]
				""".formatted(product.getId(), selections);
	}

	/** Happy path: price recalculated with deltas, snapshot persisted, kitchen sees "Grupo: Opción". */
	@Test
	void orderWithValidSelectionsRecalculatesPriceAndPersistsSnapshot() throws Exception {

		freshProduct();
		Long white = product.getOptionGroups().get(0).getOptions().get(0).getId();
		Long integral = product.getOptionGroups().get(0).getOptions().get(1).getId();
		Long cheddar = product.getOptionGroups().get(1).getOptions().get(0).getId();

		// Integral (+200) + Cheddar (+300) → 100 + 500 = 600 unit, 1200 subtotal.
		String response = mockMvc
				.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(itemsJson(integral + ", " + cheddar))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1200.00))
				.andReturn().getResponse().getContentAsString();

		entityManager.clear();
		var order = orderRepository.findAll().stream()
				.filter(o -> o.getBusiness() != null && BUSINESS_ID.equals(o.getBusiness().getId()))
				.findFirst().orElseThrow();
		var item = order.getItems().stream()
				.filter(i -> i.getSelectedOptions() != null).findFirst().orElseThrow();

		// Unit price snapshot with deltas, same as the response price.
		assertThat(item.getPrice()).isEqualByComparingTo("600.00");
		var resolved = OrderServiceImpl.leerOpcionesJson(item.getSelectedOptions());
		assertThat(resolved).hasSize(2);
		assertThat(resolved.get(0).getGroupName()).isEqualTo("Tipo de pan");
		assertThat(resolved.get(0).getOptionName()).isEqualTo("Integral");
		assertThat(resolved.get(0).getPriceDelta()).isEqualByComparingTo("200");
		assertThat(resolved.get(1).getGroupName()).isEqualTo("Extras");

		// Response carries the resolved selections too (history + client).
		assertThat(response).contains("Tipo de pan").contains("Integral");
		assertThat(response).contains("Cheddar");
	}

	/** Missing required group answer is a 400 naming the group; nothing is stored. */
	@Test
	void missingRequiredGroupReturns400NamingTheGroup() throws Exception {

		freshProduct();
		Long cheddar = product.getOptionGroups().get(1).getOptions().get(0).getId();

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(itemsJson(String.valueOf(cheddar)))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("Tenés que elegir una opción para \"Tipo de pan\""));

		entityManager.flush();
		entityManager.clear();
		assertThat(orderRepository.findAll().stream()
				.filter(o -> o.getBusiness() != null && BUSINESS_ID.equals(o.getBusiness().getId())))
				.as("a rejected order must not be stored")
				.isEmpty();
	}

	/** More choices than maxSelections is a 400. */
	@Test
	void tooManySelectionsReturns400() throws Exception {

		freshProduct();
		Long white = product.getOptionGroups().get(0).getOptions().get(0).getId();
		Long cheddar = product.getOptionGroups().get(1).getOptions().get(0).getId();
		Long panceta = product.getOptionGroups().get(1).getOptions().get(1).getId();

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(itemsJson(white + ", " + cheddar + ", " + panceta))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("Podés elegir como máximo 1 opción(es) para \"Extras\""));
	}

	/** Two choices in a non-multiple group is a 400. */
	@Test
	void twoChoicesInNonMultipleGroupReturns400() throws Exception {

		freshProduct();
		Long white = product.getOptionGroups().get(0).getOptions().get(0).getId();
		Long integral = product.getOptionGroups().get(0).getOptions().get(1).getId();

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(itemsJson(white + ", " + integral))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("En \"Tipo de pan\" podés elegir solo una opción"));
	}

	/** An option id belonging to another product's group is a 404. */
	@Test
	void foreignOptionIdReturns404() throws Exception {

		freshProduct();
		Long white = product.getOptionGroups().get(0).getOptions().get(0).getId();
		Long cheddar = product.getOptionGroups().get(1).getOptions().get(0).getId();

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(orderBody(itemsJson(white + ", " + cheddar + ", 999999999"))))
				.andExpect(status().isNotFound());
	}

	/** A product without groups keeps the old behavior (S6): no options needed, base price. */
	@Test
	void productWithoutGroupsKeepsWorkingUnchanged() throws Exception {

		freshProduct();
		Category category = product.getCategory();
		Product plain = new Product();
		plain.setCategory(category);
		plain.setName("Burger Sin Opciones");
		plain.setPrice(new BigDecimal("80.00"));
		plain.setCost(new BigDecimal("10.00"));
		plain.setStock(5);
		plain.setActive(true);
		plain = productRepository.saveAndFlush(plain);

		String body = """
				{
				  "customerName": "Cliente Simple",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(plain.getId());

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(80.00));
	}
}
