package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * The dashboard consumes the product trends through the slug-scoped panel API
 * ({@code GET /api/restaurants/{slug}/trends/top-up} and {@code top-down}).
 * Those routes did not exist — the panel showed a 404 error panel even for a
 * legitimate OWNER — while the legacy global routes
 * ({@code /api/trends/top-up|down}) kept serving the same data.
 *
 * <p>The slug-scoped routes must answer 200 with the business's own rows
 * (UP rows ordered by trend percentage descending for top-up, DOWN rows
 * ascending for top-down), 404 for an unknown slug, and 401 without a token.
 * Rows of other businesses are never visible through a slug.</p>
 */
@SpringBootTest
@Transactional
class RestaurantTrendsEndpointTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_702_401L;

	private static final String SLUG = "restaurant-trends-slug";

	private static final Long FOREIGN_BUSINESS_ID = 987_702_402L;
	private static final String FOREIGN_SLUG = "restaurant-trends-foreign-slug";

	private static final String OWNER_USERNAME = "restaurant-trends-owner";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private BusinessHoursRepository businessHoursRepository;

	@Autowired
	private BusinessUserRepository businessUserRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Business business;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER_USERNAME);
		business = businessRepository.findById(BUSINESS_ID).orElseThrow();
	}

	private Category seedCategory(String name) {

		Category category = new Category();
		category.setBusiness(business);
		category.setName(name);
		return categoryRepository.saveAndFlush(category);
	}

	private Product seedProduct(Category category, String name) {

		Product product = new Product();
		product.setCategory(category);
		product.setName(name);
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("10.00"));
		product.setStock(10);
		product.setActive(true);
		return productRepository.saveAndFlush(product);
	}

	/**
	 * Direct row insert: the trend aggregation job is out of scope here; the
	 * route under test only reads {@code product_trends}.
	 */
	private void seedTrend(Long businessId, Long productId, double trendPercentage, String direction) {

		jdbcTemplate.update("""
				INSERT INTO product_trends
					(product_id, business_id, trend_percentage, current_week_sales,
					 previous_week_sales, trend_direction, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", productId, businessId, trendPercentage, 6, 4, direction, LocalDateTime.now());
	}

	@Test
	void topUpRequiresAuthentication() throws Exception {

		mockMvc.perform(get("/api/restaurants/" + SLUG + "/trends/top-up"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void topUpReturnsOwnRowsOrderedByTrendPercentageDescending() throws Exception {

		Category category = seedCategory("Trend category");
		Product rising = seedProduct(category, "Rising pizza");
		Product slowRise = seedProduct(category, "Slow rise burger");
		Product falling = seedProduct(category, "Falling empanada");

		seedTrend(BUSINESS_ID, rising.getId(), 50.0, "UP");
		seedTrend(BUSINESS_ID, slowRise.getId(), 10.0, "UP");
		seedTrend(BUSINESS_ID, falling.getId(), -20.0, "DOWN");

		mockMvc.perform(get("/api/restaurants/" + SLUG + "/trends/top-up")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].productName").value("Rising pizza"))
				.andExpect(jsonPath("$[0].trendPercentage").value(50.0))
				.andExpect(jsonPath("$[1].productName").value("Slow rise burger"))
				.andExpect(jsonPath("$[1].trendPercentage").value(10.0));
	}

	@Test
	void topDownReturnsDownRowsOrderedByTrendPercentageAscending() throws Exception {

		Category category = seedCategory("Trend category");
		Product falling = seedProduct(category, "Falling empanada");
		Product worstFall = seedProduct(category, "Worst fall lasagna");
		Product rising = seedProduct(category, "Rising pizza");

		seedTrend(BUSINESS_ID, falling.getId(), -20.0, "DOWN");
		seedTrend(BUSINESS_ID, worstFall.getId(), -80.0, "DOWN");
		seedTrend(BUSINESS_ID, rising.getId(), 50.0, "UP");

		mockMvc.perform(get("/api/restaurants/" + SLUG + "/trends/top-down")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].productName").value("Worst fall lasagna"))
				.andExpect(jsonPath("$[0].trendPercentage").value(-80.0))
				.andExpect(jsonPath("$[1].productName").value("Falling empanada"))
				.andExpect(jsonPath("$[1].trendPercentage").value(-20.0));
	}

	@Test
	void unknownSlugReturns404() throws Exception {

		mockMvc.perform(get("/api/restaurants/no-such-restaurant-slug/trends/top-up")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isNotFound());
	}

	/**
	 * The slug in the path is not a tenant selector by itself: an OWNER of one
	 * business must not read another business's trends through its slug (same
	 * 404 as an unknown slug — it must not reveal that the slug exists).
	 */
	@Test
	void ownerOfAnotherBusinessGets404OnAForeignSlug() throws Exception {

		BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, FOREIGN_BUSINESS_ID, FOREIGN_SLUG, "restaurant-trends-foreign-owner");

		Category category = seedCategory("Trend category");
		Product mine = seedProduct(category, "My product");
		seedTrend(BUSINESS_ID, mine.getId(), 50.0, "UP");

		BusinessAuthSeed.AuthenticatedBusiness foreignOwner = new BusinessAuthSeed.AuthenticatedBusiness(
				FOREIGN_BUSINESS_ID, FOREIGN_SLUG, "restaurant-trends-foreign-owner",
				jwtUtil.generateToken("restaurant-trends-foreign-owner", FOREIGN_BUSINESS_ID));

		mockMvc.perform(get("/api/restaurants/" + SLUG + "/trends/top-up")
						.header(HttpHeaders.AUTHORIZATION, foreignOwner.authorizationHeader()))
				.andExpect(status().isNotFound());

		mockMvc.perform(get("/api/restaurants/" + SLUG + "/trends/top-down")
						.header(HttpHeaders.AUTHORIZATION, foreignOwner.authorizationHeader()))
				.andExpect(status().isNotFound());
	}

	@Test
	void rowsOfOtherBusinessesAreNotVisibleThroughASlug() throws Exception {

		Category category = seedCategory("Trend category");
		Product mine = seedProduct(category, "My product");

		Business foreign = BusinessAuthSeed.seedBusiness(businessRepository, FOREIGN_BUSINESS_ID, FOREIGN_SLUG);
		Category foreignCategory = new Category();
		foreignCategory.setBusiness(foreign);
		foreignCategory.setName("Foreign category");
		foreignCategory = categoryRepository.saveAndFlush(foreignCategory);
		Product foreignProduct = new Product();
		foreignProduct.setCategory(foreignCategory);
		foreignProduct.setName("Foreign product");
		foreignProduct.setPrice(new BigDecimal("100.00"));
		foreignProduct.setCost(new BigDecimal("10.00"));
		foreignProduct.setStock(10);
		foreignProduct.setActive(true);
		foreignProduct = productRepository.saveAndFlush(foreignProduct);

		seedTrend(BUSINESS_ID, mine.getId(), 50.0, "UP");
		seedTrend(FOREIGN_BUSINESS_ID, foreignProduct.getId(), 99.0, "UP");

		String body = mockMvc.perform(get("/api/restaurants/" + SLUG + "/trends/top-up")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).contains("My product").doesNotContain("Foreign product");
	}

}
