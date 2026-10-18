package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contrato HTTP de las transiciones de estado del pedido: una transición
 * inválida (por ejemplo, DELIVERED -> READY, típico de un board desactualizado
 * o un doble click) es un CONFLICTO de estado — 409 — y NO un error del
 * servidor. Antes, {@code OrderStatus.validateTransition} lanzaba un
 * {@code IllegalStateException} crudo que caía en el catch-all del
 * {@code GlobalExceptionHandler}: el mismo pedido avanzado dos veces (o
 * cancelado después de entregado) respondía 500 con "Ocurrió un error
 * inesperado".
 */
@SpringBootTest
@Transactional
@SuppressWarnings("java:S5738") // roles LAZY: el filter necesita sesión abierta al validar el JWT
class OrderStatusTransitionHttpTests {

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

	private BusinessAuthSeed.AuthenticatedBusiness auth;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		auth = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, 880001L, "transition-probe-business", "transition-probe-owner");
	}

	/** Crea un producto y un pedido real, y lo avanza por los estados dados. */
	private long seedOrderAdvancedThrough(String... statuses) throws Exception {

		Business business = businessRepository.findById(880001L).orElseThrow();

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Menu");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName("Transition Probe Product");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("30.00"));
		product.setStock(10);
		product.setActive(true);
		product = productRepository.saveAndFlush(product);

		String body = """
				{
				  "customerName": "Cliente Transicion",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(product.getId());

		MvcResult created = mockMvc.perform(post("/api/orders/menu/transition-probe-business")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn();

		Assertions.assertThat(created.getResponse().getStatus())
				.as("el pedido de prueba debe crearse (actual: %d)", created.getResponse().getStatus())
				.isEqualTo(200);

		JsonNode json = new ObjectMapper().readTree(created.getResponse().getContentAsString());
		long orderId = json.get("orderId").asLong();

		for (String status : statuses) {
			MvcResult advanced = mockMvc.perform(patch("/api/orders/{id}/status", orderId)
							.param("status", status)
							.header("Authorization", auth.authorizationHeader()))
					.andReturn();

			Assertions.assertThat(advanced.getResponse().getStatus())
					.as("avance a %s debe ser válido (actual: %d)", status, advanced.getResponse().getStatus())
					.isEqualTo(200);
		}

		return orderId;
	}

	private MvcResult patchStatus(long orderId, String status) throws Exception {

		return mockMvc.perform(patch("/api/orders/{id}/status", orderId)
						.param("status", status)
						.header("Authorization", auth.authorizationHeader()))
				.andReturn();
	}

	@Test
	@DisplayName("DELIVERED -> READY responde 409 con el detalle de la transición, no 500")
	void invalidTransitionAfterDeliveredAnswersConflict() throws Exception {

		long orderId = seedOrderAdvancedThrough("PREPARING", "READY", "DELIVERED");

		MvcResult result = patchStatus(orderId, "READY");

		int status = result.getResponse().getStatus();
		Assertions.assertThat(status)
				.as("una transición inválida es un conflicto de estado: 409, no el 500 del catch-all. Actual: %d", status)
				.isEqualTo(409);
		Assertions.assertThat(result.getResponse().getContentAsString())
				.as("el cuerpo debe explicar la transición rechazada")
				.contains("DELIVERED")
				.contains("READY");
	}

	@Test
	@DisplayName("DELIVERED -> CANCELLED responde 409, no 500")
	void cancellingADeliveredOrderAnswersConflict() throws Exception {

		long orderId = seedOrderAdvancedThrough("PREPARING", "READY", "DELIVERED");

		MvcResult result = mockMvc.perform(patch("/api/orders/{id}/cancel", orderId)
						.header("Authorization", auth.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reasonId\": 1, \"note\": \"prueba\"}"))
				.andReturn();

		int status = result.getResponse().getStatus();
		Assertions.assertThat(status)
				.as("cancelar un pedido entregado es un conflicto de estado: 409, no el 500 del catch-all. Actual: %d", status)
				.isEqualTo(409);
	}

	@Test
	@DisplayName("NEW -> READY (salteo el estado intermedio) responde 409, no 500")
	void skippingThePreparingStageAnswersConflict() throws Exception {

		long orderId = seedOrderAdvancedThrough();

		MvcResult result = patchStatus(orderId, "READY");

		int status = result.getResponse().getStatus();
		Assertions.assertThat(status)
				.as("NEW -> READY salta la preparación: 409, no el 500 del catch-all. Actual: %d", status)
				.isEqualTo(409);
	}
}
