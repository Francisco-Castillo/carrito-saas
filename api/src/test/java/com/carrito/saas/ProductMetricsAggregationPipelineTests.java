package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.service.usecase.IAggregateProductMetricsUseCase;
import com.carrito.saas.service.usecase.IProductTrendUseCase;

/**
 * End-to-end contract for the analytics pipeline: the schedulers must actually
 * be registered, the metrics aggregation must write real rows into
 * {@code product_daily_sales} (idempotently, honouring the window), and the
 * trend job must be able to consume that table.
 *
 * <p>WHY THIS EXISTS: the pipeline was stacked-dead — the {@code jobs} module
 * was not on the api classpath, there was no {@code @EnableScheduling}, and
 * {@code AggregateProductMetricsUseCaseImpl.execute()} was an empty TODO, so
 * {@code product_daily_sales} (and therefore {@code product_trends}) could
 * never be populated.</p>
 */
@SpringBootTest
@Transactional
class ProductMetricsAggregationPipelineTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_805_301L;

	private static final String SLUG = "metrics-pipeline-a";

	private static final String OWNER_USERNAME = "metrics-pipeline-owner";

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
	private OrderRepository orderRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private IAggregateProductMetricsUseCase aggregateUseCase;

	@Autowired
	private IProductTrendUseCase trendUseCase;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Product product;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);

		Business business = businessRepository.findById(BUSINESS_ID).orElseThrow();

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Metrics Pipeline Cat");
		category = categoryRepository.saveAndFlush(category);

		product = new Product();
		product.setCategory(category);
		product.setName("Metrics Pipeline Empanada");
		product.setDescription("empanada de carne");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("40.00"));
		product.setStock(1000);
		product.setActive(true);
		product = productRepository.saveAndFlush(product);
	}

	/**
	 * A fixed moment of today. Seeding today's orders relative to
	 * {@code now()} made the assertions depend on the hour the suite ran at:
	 * close after midnight those instants belong to yesterday, which is what CI
	 * hit at 00:04 UTC.
	 */
	private static LocalDateTime todayAtNoon() {
		return LocalDate.now().atTime(12, 0);
	}

	/**
	 * Seeds a real order + item via JPA and then pins {@code orders.created_at}
	 * with a direct JDBC update: {@code @CreationTimestamp} owns that column on
	 * insert, so the JPA value cannot be trusted for backdated orders.
	 */
	private void seedOrder(LocalDateTime createdAt, int quantity, String unitPrice) {

		Business business = businessRepository.findById(BUSINESS_ID).orElseThrow();

		Order order = new Order();
		order.setBusiness(business);
		order.setTotal(new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(quantity)));
		order = orderRepository.saveAndFlush(order);

		OrderItem item = new OrderItem();
		item.setOrder(order);
		item.setProductId(product.getId());
		item.setProductName(product.getName());
		item.setPrice(new BigDecimal(unitPrice));
		item.setQuantity(quantity);
		item.setSubtotal(new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(quantity)));
		order.setItems(new ArrayList<>(List.of(item)));
		orderRepository.saveAndFlush(order);

		jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
				java.sql.Timestamp.valueOf(createdAt), order.getId());
	}

	private Map<String, Object> dailySalesRow(LocalDate date) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT quantity_sold, revenue FROM product_daily_sales "
						+ "WHERE business_id = ? AND product_id = ? AND date = ?",
				BUSINESS_ID, product.getId(), java.sql.Date.valueOf(date));
		return rows.isEmpty() ? Map.of() : rows.get(0);
	}

	private long countDailySalesRows() {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM product_daily_sales WHERE business_id = ?",
				Long.class, BUSINESS_ID);
		return count == null ? 0 : count;
	}

	/**
	 * Scheduling must be ON: without {@code @EnableScheduling} Spring never
	 * registers the {@link ScheduledAnnotationBeanPostProcessor} and every
	 * {@code @Scheduled} method in the jobs module is dead code, no matter
	 * that the classes are on the classpath.
	 */
	@Test
	void schedulingIsEnabledAndRegistersTheAnalyticsSchedulers() {

		ScheduledAnnotationBeanPostProcessor postProcessor =
				context.getBean(ScheduledAnnotationBeanPostProcessor.class);

		List<String> scheduledTasks = postProcessor.getScheduledTasks().stream()
				.map(ScheduledTask::getTask)
				.map(Object::toString)
				.toList();

		assertThat(scheduledTasks)
				.as("the three analytics schedulers must have live @Scheduled tasks")
				.anySatisfy(task -> assertThat(task).contains("ProductMetricsScheduler"))
				.anySatisfy(task -> assertThat(task).contains("ProductTrendScheduler"))
				.anySatisfy(task -> assertThat(task).contains("PredictionScheduler"));
	}

	/**
	 * The aggregation must write real numbers: two orders for the same product
	 * today collapse into one (business_id, product_id, date) row with the
	 * summed quantity and revenue.
	 *
	 * <p>The orders are placed at a fixed hour of today, like the older
	 * day-offset cases below, because the wall clock made this test depend on
	 * WHEN it ran: seeded with {@code now().minusHours(2)} it failed whenever
	 * the run happened before 02:00 local time, because then the order belongs
	 * to yesterday. CI caught it at 00:04 UTC; the aggregation was right and
	 * the expectation was not.</p>
	 */
	@Test
	void aggregationWritesQuantityAndRevenueForOrdersInTheWindow() {

		seedOrder(todayAtNoon().minusHours(1), 3, "100.00");
		seedOrder(todayAtNoon().minusHours(2), 2, "100.00");

		aggregateUseCase.execute();

		Map<String, Object> row = dailySalesRow(LocalDate.now());
		assertThat(row).as("expected a product_daily_sales row for today").isNotEmpty();
		assertThat(((Number) row.get("quantity_sold")).intValue()).isEqualTo(5);
		assertThat(row.get("revenue")).isEqualTo(new BigDecimal("500.00"));
	}

	/**
	 * The upsert is the idempotency mechanism: running the aggregation twice
	 * must leave exactly one row per key with the same totals — no duplication,
	 * no inflation.
	 */
	@Test
	void aggregationIsIdempotentWhenRunTwice() {

		seedOrder(todayAtNoon(), 3, "100.00");

		aggregateUseCase.execute();
		Map<String, Object> firstRun = dailySalesRow(LocalDate.now());

		aggregateUseCase.execute();

		assertThat(countDailySalesRows())
				.as("re-running must not duplicate rows")
				.isEqualTo(1);
		assertThat(dailySalesRow(LocalDate.now()))
				.as("re-running must not inflate the totals")
				.isEqualTo(firstRun);
		assertThat(((Number) dailySalesRow(LocalDate.now()).get("quantity_sold")).intValue())
				.isEqualTo(3);
	}

	/**
	 * The configurable window (default 30 days ending today) excludes older
	 * orders: one inside the window is aggregated, one well outside is not.
	 */
	@Test
	void aggregationHonoursTheWindowBoundaries() {

		seedOrder(LocalDate.now().minusDays(2).atTime(12, 0), 4, "100.00");
		seedOrder(LocalDate.now().minusDays(45).atTime(12, 0), 7, "100.00");

		aggregateUseCase.execute();

		Map<String, Object> insideWindow = dailySalesRow(LocalDate.now().minusDays(2));
		assertThat(insideWindow).as("an order inside the window must be aggregated").isNotEmpty();
		assertThat(((Number) insideWindow.get("quantity_sold")).intValue()).isEqualTo(4);

		assertThat(dailySalesRow(LocalDate.now().minusDays(45)))
				.as("an order outside the window must not be aggregated")
				.isEmpty();
	}

	/**
	 * The chain the dashboard depends on: aggregation feeds
	 * {@code product_daily_sales}, the trend use case computes
	 * {@code product_trends} from it (current week 6 vs previous week 4 →
	 * UP 50%), and the authenticated top-up endpoint reflects it.
	 */
	@Test
	void trendJobConsumesTheAggregationAndTopUpEndpointReflectsIt() throws Exception {

		seedOrder(todayAtNoon(), 6, "100.00"); // current week
		seedOrder(LocalDate.now().minusDays(10).atTime(12, 0), 2, "100.00"); // previous week
		seedOrder(LocalDate.now().minusDays(12).atTime(12, 0), 2, "100.00"); // previous week

		aggregateUseCase.execute();
		trendUseCase.execute();

		List<Map<String, Object>> trends = jdbcTemplate.queryForList(
				"SELECT trend_percentage, current_week_sales, previous_week_sales, trend_direction "
						+ "FROM product_trends WHERE business_id = ? AND product_id = ?",
				BUSINESS_ID, product.getId());

		assertThat(trends).as("the trend job must write one product_trends row").hasSize(1);
		assertThat(((Number) trends.get(0).get("current_week_sales")).intValue()).isEqualTo(6);
		assertThat(((Number) trends.get(0).get("previous_week_sales")).intValue()).isEqualTo(4);
		assertThat(trends.get(0).get("trend_direction")).isEqualTo("UP");

		mockMvc.perform(get("/api/trends/top-up").header("Authorization", owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].productName").value("Metrics Pipeline Empanada"))
				.andExpect(jsonPath("$[0].trendPercentage").value(50.0))
				.andExpect(jsonPath("$[0].trendDirection").value("UP"));
	}

}
