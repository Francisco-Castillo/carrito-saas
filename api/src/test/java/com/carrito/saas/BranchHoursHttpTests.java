package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessHours;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/**
 * Horarios por sucursal (multi-sucursal, odd/tasks/multi-sucursal.md,
 * T4/fase 2, S3).
 *
 * <p>Reglas bajo prueba:</p>
 * <ul>
 *   <li>El PUT del perfil con sucursal seleccionada (claim del JWT) sólo
 *       cambia las horas de ESA sucursal; la otra conserva las suyas (o el
 *       baseline compartido que hereda).</li>
 *   <li>createOrder respeta el horario efectivo de la sucursal del
 *       {@code request.branchId}: cerrada para la sucursal 2 aunque el
 *       negocio esté abierto para la 1.</li>
 *   <li>Token sin claim con UNA sola sucursal: el PUT del perfil sigue
 *       funcionando (fallback a la única activa) y las filas quedan
 *       atribuidas a esa sucursal.</li>
 *   <li>Token sin claim con VARIAS sucursales: PUT del perfil → 400 pidiendo
 *       selección explícita, nunca una escritura mezclada.</li>
 * </ul>
 *
 * <p>El reloj está FIJO (lunes 2026-10-19 14:00, zona del servidor) vía
 * {@code @TestConfiguration}, mismo patrón que
 * {@code OrderClosedHoursValidationTests}: los casos de horario son
 * deterministas sin importar cuándo corre CI.</p>
 *
 * <p>Fixture y constraint vieja: la DB puede conservar todavía
 * {@code uq_business_hours_business_day_position} (3 columnas, sin branch)
 * hasta que corra docs/migrations/2026-10-28-branch-hours.sql, así que el
 * baseline compartido del negocio A evita el lunes (día que escriben las
 * horas por sucursal) y la "sucursal cerrada" se escribe como excepción de
 * fecha vía el perfil — sin colisiones en ningún orden de ejecución.</p>
 */
@SpringBootTest
@Transactional
class BranchHoursHttpTests {

	private static final Long BUSINESS_A_ID = 987_905_101L;
	private static final Long BUSINESS_B_ID = 987_905_102L;

	private static final String SLUG_A = "branch-hours-a";
	private static final String SLUG_B = "branch-hours-b";

	private static final String USERNAME_A = "branch-hours-owner-a";
	private static final String USERNAME_B = "branch-hours-owner-b";

	/** Lunes del reloj fijo: la fecha que cierra la sucursal 2. */
	private static final String FIXED_DATE = "2026-10-19";

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
	private BusinessHoursRepository businessHoursRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JwtUtil jwtUtil;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness ownerA;
	private BusinessAuthSeed.AuthenticatedBusiness ownerB;

	private Long casaCentralId;
	private Long palermoId;
	private Long unicaId;

	private final ObjectMapper objectMapper = new ObjectMapper();

	/** Reloj fijo: lunes 2026-10-19 14:00, zona del servidor (ClockConfig). */
	@TestConfiguration
	static class FixedClockConfig {

		@Bean
		@org.springframework.context.annotation.Primary
		public Clock fixedBranchHoursClock() {
			ZoneId zone = ZoneId.systemDefault();
			return Clock.fixed(LocalDateTime.of(2026, 10, 19, 14, 0).atZone(zone).toInstant(), zone);
		}
	}

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		ownerA = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, USERNAME_A);

		ownerB = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_B_ID, SLUG_B, USERNAME_B);

		// Negocio A: dos sucursales activas (el caso que obliga a elegir).
		casaCentralId = seedBranch(BUSINESS_A_ID, "Casa central");
		palermoId = seedBranch(BUSINESS_A_ID, "Palermo");

		// Negocio B: una sola (el caso que se resuelve solo).
		unicaId = seedBranch(BUSINESS_B_ID, "B única");
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

	/**
	 * Baseline compartido a nivel negocio (branch NULL, como las filas
	 * legacy): dos turnos por día, 00:00-12:00 y 12:00-00:00 (con cruce de
	 * medianoche, así cada minuto queda cubierto). Sólo los días 2..7
	 * (martes..domingo): el lunes queda libre para las horas por sucursal
	 * sin chocar con la constraint vieja de 3 columnas.
	 */
	private void seedSharedBaselineExceptMonday(long businessId) {
		Business business = businessRepository.findById(businessId).orElseThrow();
		for (int day = 2; day <= 7; day++) {
			seedSharedShift(business, day, 0, "00:00:00", "12:00:00");
			seedSharedShift(business, day, 1, "12:00:00", "00:00:00");
		}
	}

	private void seedSharedShift(Business business, int dayOfWeek, int position, String opens, String closes) {
		BusinessHours shift = new BusinessHours();
		shift.setBusiness(business);
		shift.setDayOfWeek(dayOfWeek);
		shift.setPosition(position);
		shift.setOpensAt(java.time.LocalTime.parse(opens));
		shift.setClosesAt(java.time.LocalTime.parse(closes));
		businessHoursRepository.saveAndFlush(shift);
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

	/** Selecciona la sucursal y devuelve el token nuevo con el claim. */
	private String selectBranch(String bearerTokenWithoutPrefix, long branchId) throws Exception {
		String response = mockMvc.perform(post("/api/business/branches/select/" + branchId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerTokenWithoutPrefix))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).path("token").asText();
	}

	/** PUT /api/business/profile con las horas del payload (sólo horas, nada más). */
	private ResultActions putHours(String bearerTokenWithoutPrefix, String hoursJson) throws Exception {
		String body = """
				{ "hours": %s }
				""".formatted(hoursJson);
		return mockMvc.perform(put("/api/business/profile")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerTokenWithoutPrefix)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private JsonNode getProfile(String bearerTokenWithoutPrefix) throws Exception {
		var result = mockMvc.perform(get("/api/business/profile")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerTokenWithoutPrefix))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode putProfileAndGetBody(String bearerTokenWithoutPrefix, String body) throws Exception {
		var result = mockMvc.perform(put("/api/business/profile")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerTokenWithoutPrefix)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	// ------------------------------------------------------------------
	// (a) PUT de horas scopeado a la sucursal seleccionada
	// ------------------------------------------------------------------

	@Test
	void putHorasDeUnaSucursalNoTocaLaOtra() throws Exception {
		seedSharedBaselineExceptMonday(BUSINESS_A_ID);

		String tokenCasa = selectBranch(ownerA.bearerToken(), casaCentralId);

		putHours(tokenCasa, """
				[ { "dayOfWeek": 1, "shifts": [ { "opensAt": "09:00:00", "closesAt": "17:00:00" } ] } ]
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hours[0].dayOfWeek").value(1))
				.andExpect(jsonPath("$.hours[0].shifts[0].opensAt").value("09:00:00"));

		// La Casa central cambió su lunes; conserva el baseline compartido
		// del martes (herencia de las filas legacy a nivel negocio).
		JsonNode casa = getProfile(tokenCasa);
		assertThat(casa.path("hours").get(0).path("shifts").get(0).path("opensAt").asText())
				.isEqualTo("09:00:00");
		assertThat(casa.path("hours").get(1).path("shifts")).hasSize(2);

		// Palermo conserva LAS SUYAS: su lunes sigue cerrado y su martes
		// sigue siendo el baseline compartido — el PUT de la Casa central no
		// se derramó (si el borrado fuera a nivel negocio, Palermo quedaría
		// con el lunes cambiado y el martes vacío).
		String tokenPalermo = selectBranch(ownerA.bearerToken(), palermoId);
		JsonNode palermo = getProfile(tokenPalermo);
		assertThat(palermo.path("hours").get(0).path("shifts")).isEmpty();
		assertThat(palermo.path("hours").get(1).path("shifts")).hasSize(2);
		assertThat(palermo.path("hours").get(1).path("shifts").get(0).path("opensAt").asText())
				.isEqualTo("00:00:00");
	}

	// ------------------------------------------------------------------
	// (b) createOrder respeta el horario de la sucursal del request
	// ------------------------------------------------------------------

	@Test
	void createOrderRespetaElHorarioDeLaSucursalDelRequest() throws Exception {
		// Negocio siempre abierto (baseline compartido todos los días).
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, BUSINESS_A_ID);
		long productId = seedProduct(SLUG_A, "Producto A");

		// Sucursal 2 (Palermo): excepción de fecha CERRADA para el lunes del
		// reloj fijo, escrita vía el perfil con Palermo seleccionada.
		String tokenPalermo = selectBranch(ownerA.bearerToken(), palermoId);
		putProfileAndGetBody(tokenPalermo, """
				{ "dateExceptions": [ { "date": "%s", "yearly": false, "shifts": [] } ] }
				""".formatted(FIXED_DATE))
				.path("dateExceptions");

		// Con el branchId de la sucursal cerrada: 400.
		mockMvc.perform(post("/api/orders/menu/" + SLUG_A)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "branchId": %d,
						  "customerName": "Pedido Palermo",
						  "customerPhone": "1155550000",
						  "orderType": "RETIRO",
						  "paymentMethod": "EFECTIVO",
						  "items": [ { "productId": %d, "quantity": 1 } ]
						}
						""".formatted(palermoId, productId)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El local está cerrado"));

		// Con el branchId de la Casa central (abierta): el pedido entra.
		String body = mockMvc.perform(post("/api/orders/menu/" + SLUG_A)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "branchId": %d,
						  "customerName": "Pedido Casa Central",
						  "customerPhone": "1155550000",
						  "orderType": "RETIRO",
						  "paymentMethod": "EFECTIVO",
						  "items": [ { "productId": %d, "quantity": 1 } ]
						}
						""".formatted(casaCentralId, productId)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(objectMapper.readTree(body).path("orderId").asLong(0)).isPositive();
	}

	// ------------------------------------------------------------------
	// (c) Token sin claim con UNA sola sucursal: fallback
	// ------------------------------------------------------------------

	@Test
	void putPerfilSinClaimConUnaSucursalSigueFuncionando() throws Exception {
		// Token de ownerB SIN claim de sucursal (seedOwner no lo pone): con
		// una única sucursal activa se resuelve sola.
		putHours(ownerB.bearerToken(), """
				[ { "dayOfWeek": 1, "shifts": [ { "opensAt": "10:00:00", "closesAt": "20:00:00" } ] } ]
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hours[0].shifts[0].opensAt").value("10:00:00"));

		// GET round trip con el mismo token sin claim.
		JsonNode perfil = getProfile(ownerB.bearerToken());
		assertThat(perfil.path("hours").get(0).path("shifts").get(0).path("opensAt").asText())
				.isEqualTo("10:00:00");

		// Las filas quedaron atribuidas a la única sucursal del negocio, no
		// sueltas a nivel negocio (la escritura fue scopeada por el fallback).
		// JPQL puro: la atribución se lee de la query, sin acoplarse a getters
		// que todavía no existen (el RED de este caso es runtime, no compile).
		entityManager.flush();
		entityManager.clear();
		Long filasEnLaSucursal = entityManager
				.createQuery("SELECT COUNT(h) FROM BusinessHours h "
						+ "WHERE h.business.id = :businessId AND h.branch.id = :branchId", Long.class)
				.setParameter("businessId", BUSINESS_B_ID)
				.setParameter("branchId", unicaId)
				.getSingleResult();
		assertThat(filasEnLaSucursal).isPositive();

		Long filasSinSucursal = entityManager
				.createQuery("SELECT COUNT(h) FROM BusinessHours h "
						+ "WHERE h.business.id = :businessId AND h.branch IS NULL", Long.class)
				.setParameter("businessId", BUSINESS_B_ID)
				.getSingleResult();
		assertThat(filasSinSucursal).isEqualTo(0L);
	}

	// ------------------------------------------------------------------
	// (d) Token sin claim con VARIAS sucursales: 400
	// ------------------------------------------------------------------

	@Test
	void putPerfilSinClaimConVariasSucursalesResuelve400() throws Exception {
		mockMvc.perform(put("/api/business/profile")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{ "hours": [ { "dayOfWeek": 1, "shifts": [ { "opensAt": "09:00:00", "closesAt": "17:00:00" } ] } ] }
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Seleccioná una sucursal para continuar"));
	}

}
