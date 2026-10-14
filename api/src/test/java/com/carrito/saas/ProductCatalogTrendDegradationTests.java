package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Degradation contract for {@code GET /api/restaurants/slug/{slug}/products}
 * when the analytics schema is missing.
 *
 * <p>The trend enrichment queries the JDBC table {@code product_trends},
 * which has no JPA entity and no migration, so a database created from the
 * entity model (the normal state of a freshly created test database, and the
 * documented state of any deployment without the analytics schema) makes the
 * lookup fail. The product catalog is core functionality and trends are
 * optional enrichment, so the route must degrade: 200 with the products and
 * all their real fields, without trend data — never a 500.</p>
 */
@SpringBootTest
@Transactional
class ProductCatalogTrendDegradationTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_804_101L;

	private static final String SLUG_A = "catalog-trend-degrade-a";

	private static final String OWNER_USERNAME = "catalog-trend-degrade-owner";

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

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		Category categoryA = new Category();
		categoryA.setBusiness(businessA);
		categoryA.setName("Catalog Degrade Cat A");
		categoryA = categoryRepository.saveAndFlush(categoryA);

		Product first = new Product();
		first.setCategory(categoryA);
		first.setName("Catalog Degrade Empanada");
		first.setDescription("empanada de carne");
		first.setPrice(new BigDecimal("120.00"));
		first.setCost(new BigDecimal("40.00"));
		first.setStock(10);
		first.setActive(true);
		productRepository.saveAndFlush(first);

		Product second = new Product();
		second.setCategory(categoryA);
		second.setName("Catalog Degrade Pizza");
		second.setDescription("pizza muzzarella");
		second.setPrice(new BigDecimal("300.00"));
		second.setCost(new BigDecimal("90.00"));
		second.setActive(true);
		productRepository.saveAndFlush(second);
	}

	/**
	 * With the analytics table absent the public slug catalog must answer 200
	 * with the products and the rest of their fields intact, degrading the
	 * trend enrichment instead of failing the whole route.
	 */
	@Test
	void slugCatalogReturnsProductsWithoutTrendDataWhenAnalyticsSchemaIsMissing() throws Exception {

		mockMvc.perform(get("/api/restaurants/slug/" + SLUG_A + "/products"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.name == 'Catalog Degrade Empanada')].price")
						.value(org.hamcrest.Matchers.contains(120.00)))
				.andExpect(jsonPath("$.content[?(@.name == 'Catalog Degrade Empanada')].description")
						.value(org.hamcrest.Matchers.contains("empanada de carne")))
				.andExpect(jsonPath("$.content[?(@.name == 'Catalog Degrade Pizza')].price")
						.value(org.hamcrest.Matchers.contains(300.00)))
				.andExpect(jsonPath("$.totalElements").value(2));
	}

	/**
	 * The degraded response fills the trend fields with the documented
	 * neutral defaults and carries the rest of the DTO shape.
	 */
	@Test
	void degradedResponseCarriesNeutralTrendDefaultsAndTheRestOfTheFields() throws Exception {

		String body = mockMvc.perform(get("/api/restaurants/slug/" + SLUG_A + "/products"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		ObjectMapper mapper = new ObjectMapper();
		var root = mapper.readTree(body);

		assertThat(root.path("content").isArray()).isTrue();
		assertThat(root.path("content").size())
				.as("the degraded catalog must still list every active product")
				.isEqualTo(2);

		for (var node : root.path("content")) {
			assertThat(node.path("id").asLong()).isPositive();
			assertThat(node.path("name").asText()).isNotBlank();
			assertThat(node.path("price").asDouble()).isPositive();
			assertThat(node.path("trendPercentage").asDouble())
					.as("without analytics the trend percentage degrades to the neutral 0.0")
					.isEqualTo(0.0);
			assertThat(node.path("trendDirection").asText())
					.as("without analytics the trend direction degrades to STABLE")
					.isEqualTo("STABLE");
		}
	}

}
