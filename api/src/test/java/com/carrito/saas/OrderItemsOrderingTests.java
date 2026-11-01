package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.resource.jdbc.spi.StatementInspector;

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
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Los ítems de un pedido tienen que salir **siempre en el mismo orden**: el
 * orden de carga (su id). Sin eso, la lista es la que devuelva PostgreSQL —
 * orden físico del heap—, y cualquier {@code UPDATE} de una fila la reescribe
 * al final de la tabla: el mismo pedido puede mostrar sus ítems en otro orden
 * entre dos lecturas, en la consola y en la comanda.
 *
 * <p>El test fuerza exactamente ese movimiento con un {@code UPDATE} nativo sobre
 * el primer ítem y exige que la respuesta lo siga mostrando primero. La prueba
 * es determinista: el orden físico cambia de verdad, no depende de la suerte del
 * planner. {@code OrderItemNotesHttpTests} dependía de ese orden sin saberlo y
 * por eso fallaba de forma intermitente en la suite completa.</p>
 */
@SpringBootTest(properties = {
		"spring.jpa.properties.hibernate.session_factory.statement_inspector="
				+ "com.carrito.saas.OrderItemsOrderingTests$RecordingInspector" })
@Transactional
class OrderItemsOrderingTests {

	/** Guarda, en orden, cada sentencia que Hibernate manda al driver. */
	public static class RecordingInspector implements StatementInspector {

		static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());

		@Override
		public String inspect(String sql) {
			STATEMENTS.add(sql);
			return sql;
		}
	}

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_805_102L;

	private static final String SLUG = "order-items-ordering-test";

	private static final String OWNER = "order-items-ordering-owner";

	/** La nota identifica al primer ítem sin depender de ningún índice. */
	private static final String FIRST_ITEM_NOTE = "primero";

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

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private BusinessUserRepository businessUserRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Product product;

	@BeforeEach
	void setUpMockMvcAndFixtures() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, BUSINESS_ID);

		Business business = businessRepository.findById(BUSINESS_ID).orElseThrow();

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Hamburguesas");
		category.setOrden(1);
		category.setActive(true);
		categoryRepository.saveAndFlush(category);

		Product seeded = new Product();
		seeded.setCategory(category);
		seeded.setName("Clasica");
		seeded.setPrice(new BigDecimal("7500.00"));
		seeded.setCost(new BigDecimal("3000.00"));
		seeded.setStock(10);
		seeded.setActive(true);
		product = productRepository.saveAndFlush(seeded);
	}

	@BeforeEach
	void clearRecordedStatements() {
		RecordingInspector.STATEMENTS.clear();
	}

	/**
	 * La colección tiene que cargarse con un {@code ORDER BY} explícito. Sin él,
	 * el orden es el que devuelva PostgreSQL (heap), y un {@code UPDATE} de una
	 * fila alcanza para que el mismo pedido muestre sus ítems en otro orden.
	 */
	@Test
	void theItemsAreLoadedWithAnExplicitOrderBy() throws Exception {

		createOrderWithTwoItems();
		// Sin limpiar el contexto la colección ya está cargada y no hay SQL que
		// inspeccionar: se fuerza la recarga desde la base.
		entityManager.flush();
		entityManager.clear();
		loadItems();

		// Hibernate puede cargar la colección con un join del pedido o con un
		// select propio; las dos formas tienen que pedir el orden.
		Pattern itemSelect = Pattern.compile("(?:from|join) order_items (\\w+)");
		String fetch = RecordingInspector.STATEMENTS.stream()
				.map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
				.filter(sql -> sql.startsWith("select") && itemSelect.matcher(sql).find())
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"no se registró la carga de la colección de ítems; sentencias: " + RecordingInspector.STATEMENTS));

		Matcher alias = itemSelect.matcher(fetch);
		assertThat(alias.find()).isTrue();

		assertThat(fetch)
				.as("la carga de la colección necesita ORDER BY; sin él el orden es el del heap")
				.contains("order by " + alias.group(1) + ".id");
	}

	/**
	 * El efecto observable: se fuerza el movimiento físico del primer ítem y la
	 * lectura tiene que seguir devolviéndolo primero.
	 */
	@Test
	void theItemsComeBackInIdOrderEvenAfterTheHeapMovesThem() throws Exception {

		createOrderWithTwoItems();
		Long firstItemId = firstItemIdInInsertionOrder();

		// Un UPDATE reescribe la fila al final del heap. Sin ORDER BY explícito,
		// la siguiente lectura la devuelve última.
		entityManager.createNativeQuery("UPDATE order_items SET item_notes = item_notes WHERE id = :id")
				.setParameter("id", firstItemId)
				.executeUpdate();
		entityManager.flush();
		entityManager.clear();

		var items = loadItems();
		assertThat(items).hasSize(2);
		assertThat(items.get(0).getItemNotes()).as("el ítem cargado primero sigue primero").isEqualTo(FIRST_ITEM_NOTE);
		assertThat(items.get(1).getItemNotes()).isNull();
	}

	private List<com.carrito.saas.repository.entity.OrderItem> loadItems() {

		var order = orderRepository.findAll().stream()
				.filter(o -> o.getBusiness() != null && BUSINESS_ID.equals(o.getBusiness().getId()))
				.findFirst().orElseThrow();
		order.getItems().size();
		return order.getItems();
	}

	private void createOrderWithTwoItems() throws Exception {

		String body = """
				{
				  "customerName": "Cliente Orden",
				  "customerPhone": "5411555123",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [
				    { "productId": %d, "quantity": 1, "notes": "%s" },
				    { "productId": %d, "quantity": 2 }
				  ]
				}
				""".formatted(product.getId(), FIRST_ITEM_NOTE, product.getId());

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].notes").value(FIRST_ITEM_NOTE));
	}

	private Long firstItemIdInInsertionOrder() {

		return ((Number) entityManager
				.createNativeQuery("SELECT MIN(id) FROM order_items WHERE item_notes = :note")
				.setParameter("note", FIRST_ITEM_NOTE)
				.getSingleResult())
				.longValue();
	}
}
