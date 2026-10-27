package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

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

import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contexto de sucursal y scoping de pedidos (multi-sucursal,
 * odd/tasks/multi-sucursal.md, T3/fase 2).
 *
 * <p>Reglas bajo prueba:</p>
 * <ul>
 *   <li>El claim {@code branchId} del JWT fija la sucursal activa; el KDS y
 *       la consola del día scopean por ella — un pedido de la sucursal A no
 *       es visible desde la B (mismo negocio).</li>
 *   <li>Token sin claim: una sola sucursal activa se resuelve sola
 *       (compatibilidad con tokens viejos); varias → 400 pidiendo selección
 *       explícita, nunca una vista mezclada.</li>
 *   <li>{@code POST /api/business/branches/select/{id}} valida sucursal
 *       propia y activa (id ajeno → 404) y emite token nuevo con el claim.</li>
 *   <li>El pedido público acepta {@code branchId} opcional: ajeno o inactivo
 *       → 404 sin crear el pedido; ausente → primera sucursal activa.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class BranchOrdersScopingHttpTests {

	private static final Long BUSINESS_A_ID = 987_904_101L;
	private static final Long BUSINESS_B_ID = 987_904_102L;

	private static final String SLUG_A = "branch-orders-a";
	private static final String SLUG_B = "branch-orders-b";

	private static final String USERNAME_A = "branch-orders-owner-a";
	private static final String USERNAME_B = "branch-orders-owner-b";

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
	private BranchRepository branchRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@Autowired
	private com.carrito.saas.repository.jpa.BusinessHoursRepository businessHoursRepository;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness ownerA;
	private BusinessAuthSeed.AuthenticatedBusiness ownerB;

	private Long casaCentralId;
	private Long palermoId;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		ownerA = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, USERNAME_A);

		ownerB = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_B_ID, SLUG_B, USERNAME_B);

		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, BUSINESS_A_ID);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, BUSINESS_B_ID);

		// Negocio A: dos sucursales activas (el caso que obliga a elegir).
		casaCentralId = seedBranch(BUSINESS_A_ID, "Casa central");
		palermoId = seedBranch(BUSINESS_A_ID, "Palermo");

		// Negocio B: una sola (el caso que se resuelve solo).
		seedBranch(BUSINESS_B_ID, "B única");
	}

	// ------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------

	private Long seedBranch(long businessId, String name) {
		Business business = businessRepository.findById(businessId).orElseThrow();
		Branch branch = new Branch();
		branch.setBusiness(business);
		branch.setName(name);
		return branchRepository.saveAndFlush(branch).getId();
	}

	private long seedProduct(String slug, String name) {
		Business business = businessRepository.findBySlug(slug).orElseThrow();

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Cat " + slug + " " + name);
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName(name);
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("30.00"));
		product.setStock(50);
		product.setActive(true);
		return productRepository.saveAndFlush(product).getId();
	}

	private long createOrder(String slug, String customerName, long productId, Long branchId) throws Exception {
		String branchField = branchId == null ? "" : "\"branchId\": %d,".formatted(branchId);
		String body = """
				{
				  %s
				  "customerName": "%s",
				  "customerPhone": "1155550000",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(branchField, customerName, productId);

		var result = mockMvc.perform(post("/api/orders/menu/" + slug)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn();

		return objectMapper.readTree(result.getResponse().getContentAsString()).path("orderId").asLong(0);
	}

	private JsonNode getJson(String path, String bearerToken, String query, int expectedStatus) throws Exception {
		var result = mockMvc.perform(get(path + (query == null ? "" : query))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken))
				.andExpect(status().is(expectedStatus))
				.andReturn();
		String content = result.getResponse().getContentAsString();
		return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
	}

	private List<String> kdsCustomerNames(String bearerToken) throws Exception {
		JsonNode node = getJson("/api/business/orders/active", bearerToken, null, 200);
		List<String> names = new java.util.ArrayList<>();
		node.forEach(order -> names.add(order.path("customerName").asText()));
		return names;
	}

	// ------------------------------------------------------------------
	// Resolución de sucursal sin claim
	// ------------------------------------------------------------------

	@Test
	void negocioConUnaSucursalLaResuelveSolo() throws Exception {
		long productId = seedProduct(SLUG_B, "Producto B");
		createOrder(SLUG_B, "Pedido B", productId, null);

		List<String> names = kdsCustomerNames(ownerB.bearerToken());

		assertThat(names).containsExactly("Pedido B");
	}

	@Test
	void negocioConVariasSucursalesYSinSeleccionRechazaCon400() throws Exception {
		long productId = seedProduct(SLUG_A, "Producto A");
		createOrder(SLUG_A, "Pedido Sin Elegir", productId, null);

		mockMvc.perform(get("/api/business/orders/active")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Seleccioná una sucursal para continuar"));
	}

	// ------------------------------------------------------------------
	// Selección explícita de sucursal
	// ------------------------------------------------------------------

	@Test
	void selectDevuelveTokenConSucursalYElKdsScopeaPorElla() throws Exception {
		long productId = seedProduct(SLUG_A, "Producto A");
		createOrder(SLUG_A, "Pedido Palermo", productId, palermoId);
		createOrder(SLUG_A, "Pedido Casa Central", productId, casaCentralId);

		String response = mockMvc.perform(post("/api/business/branches/select/" + palermoId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andExpect(jsonPath("$.branchId").value(palermoId))
				.andExpect(jsonPath("$.branchName").value("Palermo"))
				.andReturn().getResponse().getContentAsString();

		String nuevoToken = objectMapper.readTree(response).path("token").asText();

		// Con Palermo seleccionada: sólo el pedido de Palermo.
		assertThat(kdsCustomerNames(nuevoToken)).containsExactly("Pedido Palermo");

		// Cambiando a Casa central: sólo el de Casa central.
		String responseCasa = mockMvc.perform(post("/api/business/branches/select/" + casaCentralId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String tokenCasa = objectMapper.readTree(responseCasa).path("token").asText();

		assertThat(kdsCustomerNames(tokenCasa)).containsExactly("Pedido Casa Central");
	}

	@Test
	void selectDeSucursalAjenaResuelve404() throws Exception {
		mockMvc.perform(post("/api/business/branches/select/" + palermoId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerB.bearerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Sucursal no encontrada"));
	}

	@Test
	void selectDeSucursalInactivaResuelve404() throws Exception {
		Branch inactiva = branchRepository.findById(casaCentralId).orElseThrow();
		inactiva.setActive(false);
		branchRepository.saveAndFlush(inactiva);

		mockMvc.perform(post("/api/business/branches/select/" + casaCentralId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------
	// Pedido público con branchId
	// ------------------------------------------------------------------

	@Test
	void pedidoConBranchAjenoResuelve404YNoCreaElPedido() throws Exception {
		long productId = seedProduct(SLUG_A, "Producto A");
		long pedidosAntes = orderRepository.count();

		mockMvc.perform(post("/api/orders/menu/" + SLUG_A)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "branchId": %d,
						  "customerName": "Pedido Ajeno",
						  "customerPhone": "1155550000",
						  "orderType": "RETIRO",
						  "paymentMethod": "EFECTIVO",
						  "items": [ { "productId": %d, "quantity": 1 } ]
						}
						""".formatted(seedBranch(BUSINESS_B_ID, "Sucursal ajena"), productId)))
				.andExpect(status().isNotFound());

		assertThat(orderRepository.count()).isEqualTo(pedidosAntes);
	}

	@Test
	void pedidoSinBranchCaeEnLaPrimeraSucursalActiva() throws Exception {
		long productId = seedProduct(SLUG_A, "Producto A");
		createOrder(SLUG_A, "Pedido Default", productId, null);

		// Sin selección explícita el panel no puede mostrarlo (400); con la
		// Casa central (primera activa) sí.
		mockMvc.perform(get("/api/business/orders/active")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isBadRequest());

		String response = mockMvc.perform(post("/api/business/branches/select/" + casaCentralId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String tokenCasa = objectMapper.readTree(response).path("token").asText();

		assertThat(kdsCustomerNames(tokenCasa)).containsExactly("Pedido Default");
	}

	// ------------------------------------------------------------------
	// Token con claim: aislamiento entre sucursales del mismo negocio
	// ------------------------------------------------------------------

	@Test
	void tokenConBranchNoVeLosPedidosDeLaOtraSucursal() throws Exception {
		long productId = seedProduct(SLUG_A, "Producto A");
		createOrder(SLUG_A, "Pedido Palermo", productId, palermoId);
		createOrder(SLUG_A, "Pedido Casa Central", productId, casaCentralId);

		String tokenPalermo = jwtUtil.generateToken(USERNAME_A, BUSINESS_A_ID, palermoId);

		assertThat(kdsCustomerNames(tokenPalermo)).containsExactly("Pedido Palermo");

		// La consola del día también scopea por la sucursal del claim.
		JsonNode dayView = getJson("/api/business/orders", tokenPalermo, null, 200);
		assertThat(dayView.size()).isEqualTo(1);
		assertThat(dayView.get(0).path("customerName").asText()).isEqualTo("Pedido Palermo");
	}

	@Test
	void unNegocioNuncaVePedidosDeOtroNegocioAunqueCompartanSucursalId() throws Exception {
		long productId = seedProduct(SLUG_A, "Producto A");
		createOrder(SLUG_A, "Pedido De A", productId, casaCentralId);

		// El dueño B con el id de la sucursal de A en el claim: el scoping de
		// negocio (businessId del JWT) manda — no ve pedidos de A.
		String tokenBConSucursalDeA = jwtUtil.generateToken(USERNAME_B, BUSINESS_B_ID, casaCentralId);

		List<String> names = kdsCustomerNames(tokenBConSucursalDeA);

		assertThat(names).doesNotContain("Pedido De A");
	}

}
