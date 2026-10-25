package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.CancellationReason;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CancellationReasonRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Gate de pago por transferencia (S1/S4 de odd/tasks/transfer-payment-gate.md):
 * un pedido con paymentMethod TRANSFERENCIA arranca en PENDING_PAYMENT, NO
 * aparece en el feed activo del KDS y pasa a NEW cuando el operador confirma
 * el pago. Cancelarlo desde PENDING_PAYMENT devuelve el stock reservado, igual
 * que desde NEW.
 */
@SpringBootTest
@Transactional
@SuppressWarnings("java:S5738") // roles LAZY: el filter necesita sesión abierta al validar el JWT
class TransferPaymentGateHttpTests {

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
	private jakarta.persistence.EntityManager em;

	@Autowired
	private JwtUtil jwtUtil;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private CancellationReasonRepository cancellationReasonRepository;

	@Autowired
	private com.carrito.saas.repository.jpa.BusinessHoursRepository businessHoursRepository;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness auth;

	private static final String SLUG = "transfer-gate-business";

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		auth = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, 880002L, SLUG, "transfer-gate-owner");
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, 880002L);
	}

	private long seedProduct(int stock) {

		Business business = businessRepository.findById(880002L).orElseThrow();

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Transfer Gate");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName("Transfer Gate Product");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("30.00"));
		product.setStock(stock);
		product.setActive(true);
		return productRepository.saveAndFlush(product).getId();
	}

	private long createOrder(long productId, String paymentMethod) throws Exception {

		String body = """
				{
				  "customerName": "Cliente Transfer Gate",
				  "orderType": "RETIRO",
				  "paymentMethod": "%s",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(paymentMethod, productId);

		MvcResult created = mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn();

		Assertions.assertThat(created.getResponse().getStatus())
				.as("el pedido de prueba debe crearse (actual: %d)", created.getResponse().getStatus())
				.isEqualTo(200);

		JsonNode json = new ObjectMapper().readTree(created.getResponse().getContentAsString());
		Assertions.assertThat(json.get("status").asText())
				.as("el pedido %s debe nacer en %s", paymentMethod, "PENDING_PAYMENT".equals(paymentMethod) ? "PENDING_PAYMENT" : "NEW")
				.isEqualTo("TRANSFERENCIA".equals(paymentMethod) ? "PENDING_PAYMENT" : "NEW");

		return json.get("orderId").asLong();
	}

	private JsonNode activeKdsOrders() throws Exception {

		MvcResult result = mockMvc.perform(get("/api/business/orders/active")
						.header("Authorization", auth.authorizationHeader()))
				.andExpect(status().isOk())
				.andReturn();

		return new ObjectMapper().readTree(result.getResponse().getContentAsString());
	}

	/**
	 * Stock actual del producto: el bulk update de stock no refresca el caché
	 * de primer nivel de JPA, así que se limpia el persistence context antes
	 * de releer.
	 */
	private int currentStock(long productId) {

		em.flush();
		em.clear();
		return productRepository.findById(productId).orElseThrow().getStock();
	}

	@Test
	@DisplayName("TRANSFERENCIA nace en PENDING_PAYMENT; EFECTIVO nace en NEW")
	void transferenciaStartsPendingPaymentAndEfectivoStartsNew() throws Exception {

		long productId = seedProduct(10);

		createOrder(productId, "TRANSFERENCIA");
		createOrder(productId, "EFECTIVO");
	}

	@Test
	@DisplayName("El feed activo del KDS excluye PENDING_PAYMENT y lo incluye al confirmar")
	void kdsFeedExcludesPendingPaymentUntilConfirmed() throws Exception {

		long productId = seedProduct(10);

		long pendingId = createOrder(productId, "TRANSFERENCIA");
		long efectivoId = createOrder(productId, "EFECTIVO");

		// Aserción estructural (no por substring: un id contenido en otro
		// número, un campo o un total daría un falso positivo).
		Assertions.assertThat(orderIdsIn(activeKdsOrders()))
				.as("el KDS no ve el pedido con transferencia sin confirmar")
				.doesNotContain(pendingId)
				.as("el KDS ve el pedido en efectivo normalmente")
				.contains(efectivoId);

		// Confirmar pago: PENDING_PAYMENT -> NEW (transición válida).
		mockMvc.perform(patch("/api/orders/{id}/status", pendingId)
						.param("status", "NEW")
						.header("Authorization", auth.authorizationHeader()))
				.andExpect(status().isOk());

		Assertions.assertThat(orderIdsIn(activeKdsOrders()))
				.as("confirmado el pago, el pedido entra al KDS")
				.contains(pendingId);
	}

	/** Ids de los pedidos del array del feed KDS, parseados del JSON. */
	private java.util.List<Long> orderIdsIn(JsonNode orders) {

		java.util.List<Long> ids = new java.util.ArrayList<>();
		for (JsonNode order : orders) {
			ids.add(order.get("orderId").asLong());
		}
		return ids;
	}

	@Test
	@DisplayName("PENDING_PAYMENT -> READY es 409: hay que confirmar el pago primero")
	void pendingPaymentCannotSkipStraightToPreparing() throws Exception {

		long productId = seedProduct(10);
		long orderId = createOrder(productId, "TRANSFERENCIA");

		MvcResult result = mockMvc.perform(patch("/api/orders/{id}/status", orderId)
						.param("status", "READY")
						.header("Authorization", auth.authorizationHeader()))
				.andReturn();

		Assertions.assertThat(result.getResponse().getStatus())
				.as("saltar la confirmación de pago es un conflicto de estado: 409. Actual: %d",
						result.getResponse().getStatus())
				.isEqualTo(409);
	}

	@Test
	@DisplayName("Cancelar desde PENDING_PAYMENT devuelve el stock reservado")
	void cancellingPendingPaymentReturnsReservedStock() throws Exception {

		long productId = seedProduct(5);
		long orderId = createOrder(productId, "TRANSFERENCIA");

		Assertions.assertThat(currentStock(productId))
				.as("la creación reservó stock (5 - 1)")
				.isEqualTo(4);

		CancellationReason reason = new CancellationReason();
		reason.setCode("transfer-gate-motivo");
		reason.setDescription("El cliente nunca hizo la transferencia");
		reason = cancellationReasonRepository.saveAndFlush(reason);

		mockMvc.perform(patch("/api/orders/{id}/cancel", orderId)
						.header("Authorization", auth.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reasonId\": %d, \"note\": \"sin comprobante\"}".formatted(reason.getId())))
				.andExpect(status().isOk());

		Assertions.assertThat(currentStock(productId))
				.as("cancelar un pedido sin confirmar devuelve el stock como desde NEW")
				.isEqualTo(5);
	}
}
