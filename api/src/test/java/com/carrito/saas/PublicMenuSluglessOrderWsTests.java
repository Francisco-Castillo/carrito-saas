package com.carrito.saas;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The slugless public order must reach the kitchen through the same WebSocket
 * broadcast the slug endpoint uses: the KDS panel subscribes per business
 * ({@code /topic/orders/<slug>}), and a slugless POST resolves the business
 * server-side, so the topic must be derived from the RESOLVED business, not
 * from a URL path variable.
 *
 * <p>{@link SimpMessagingTemplate} is mocked; the assertion is the exact
 * destination and payload of {@code convertAndSend}. MockMvc carries the real
 * security {@link FilterChainProxy} (same setup as
 * {@link PublicMenuSluglessHttpTests}); {@code @Transactional} rolls every
 * fixture back, including the isolation deletes that model the single-business
 * deployment.</p>
 */
@SpringBootTest
@Transactional
class PublicMenuSluglessOrderWsTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_654_340L;

	private static final String SLUG = "slugless-ws-test-business";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@MockitoBean
	private SimpMessagingTemplate messagingTemplate;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		// Single-business deployment contract: the shared dev database may hold
		// seed rows, so the isolation helper deletes the OTHER businesses
		// (FK-safe, transactional) before the fixture seeds exactly one.
		SingleBusinessTestIsolation.keepOnlyBusiness(entityManager, BUSINESS_ID);

		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Slugless WS Test Business");
		business.setSlug(SLUG);
		business = businessRepository.saveAndFlush(business);

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Menu");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName("Slugless WS Test Product");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("30.00"));
		product.setStock(10);
		product.setActive(true);
		productRepository.saveAndFlush(product);
	}

	@Test
	void sluglessOrderBroadcastsToTheResolvedBusinessTopic() throws Exception {

		Product product = productRepository.findAll().stream()
				.filter(p -> p.getCategory().getBusiness().getId().equals(BUSINESS_ID))
				.findFirst()
				.orElseThrow();

		String body = """
				{
				  "customerName": "Cliente WS",
				  "customerPhone": "5411555000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "notes": "",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(product.getId());

		mockMvc.perform(post("/api/orders/menu")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());

		// The topic comes from the RESOLVED business slug (the URL has none).
		ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
		verify(messagingTemplate).convertAndSend(eq("/topic/orders/" + SLUG), payload.capture());
		assertThatOrderPayloadCarriesTheResolvedBusiness(payload.getValue());
	}

	private void assertThatOrderPayloadCarriesTheResolvedBusiness(Object value) {

		org.assertj.core.api.Assertions.assertThat(value).isInstanceOf(com.carrito.saas.dto.OrderDTO.class);
		com.carrito.saas.dto.OrderDTO order = (com.carrito.saas.dto.OrderDTO) value;
		org.assertj.core.api.Assertions.assertThat(order.getBusinessSlug()).isEqualTo(SLUG);
		org.assertj.core.api.Assertions.assertThat(order.getStatus()).isEqualTo("NEW");
	}
}
