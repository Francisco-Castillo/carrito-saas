package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.security.JwtUtil;

import tools.jackson.databind.json.JsonMapper;

/**
 * CRUD de sucursales del panel del dueño (multi-sucursal,
 * odd/tasks/multi-sucursal.md, T2). El negocio sale del JWT — no hay id ni
 * slug de negocio en la ruta — así que un negocio nunca ve ni toca las
 * sucursales de otro; un id ajeno resuelve 404. {@code DELETE} es baja
 * lógica: la sucursal sigue existiendo con {@code active=false} y aparece en
 * el listado del panel; el borrado físico queda prohibido.
 */
@SpringBootTest
@Transactional
class BranchEndpointTests {

	private static final Long BUSINESS_A_ID = 987_903_101L;
	private static final Long BUSINESS_B_ID = 987_903_102L;

	private static final String SLUG_A = "branches-a";
	private static final String SLUG_B = "branches-b";

	private static final String OWNER_USERNAME = "branches-owner-a";
	private static final String OWNER_B_USERNAME = "branches-owner-b";

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
	private JwtUtil jwtUtil;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness ownerA;
	private BusinessAuthSeed.AuthenticatedBusiness ownerB;

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		ownerA = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		ownerB = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_B_ID, SLUG_B, OWNER_B_USERNAME);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private String body(String name, String address) {
		return """
				{"name": "%s", "address": "%s"}""".formatted(name, address);
	}

	private Long crearSucursal(String token, String name) throws Exception {
		String response = mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(name, "Av. Siempre Viva 742")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		return objectMapper.readValue(response, com.carrito.saas.dto.BranchDTO.class).getId();
	}

	// ------------------------------------------------------------------
	// Listado
	// ------------------------------------------------------------------

	@Test
	void listarSinSucursalesDevuelveListaVacia() throws Exception {
		mockMvc.perform(get("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void listarMuestraLasDelNegocioDelJwtYNuncaLasAjenas() throws Exception {
		crearSucursal(ownerA.bearerToken(), "Casa central");
		crearSucursal(ownerA.bearerToken(), "Sucursal Palermo");
		crearSucursal(ownerB.bearerToken(), "Del otro dueño");

		mockMvc.perform(get("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("Casa central"))
				.andExpect(jsonPath("$[1].name").value("Sucursal Palermo"));

		mockMvc.perform(get("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerB.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Del otro dueño"));
	}

	// ------------------------------------------------------------------
	// Alta
	// ------------------------------------------------------------------

	@Test
	void altaDevuelveLaSucursalPersistida() throws Exception {
		mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Casa central", "Av. Siempre Viva 742")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Casa central"))
				.andExpect(jsonPath("$.address").value("Av. Siempre Viva 742"))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.businessId").value(BUSINESS_A_ID));
	}

	@Test
	void altaSinNombreSeRechazaCon400() throws Exception {
		mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("   ", "")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El nombre de la sucursal es obligatorio"));
	}

	@Test
	void altaConNombreDuplicadoSeRechazaCon409() throws Exception {
		crearSucursal(ownerA.bearerToken(), "Casa central");

		mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("casa CENTRAL", "")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe una sucursal con ese nombre para este negocio"));
	}

	@Test
	void elMismoNombreEnOtroNegocioEsLegitimo() throws Exception {
		crearSucursal(ownerA.bearerToken(), "Casa central");

		mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerB.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Casa central", "")))
				.andExpect(status().isOk());
	}

	// ------------------------------------------------------------------
	// Edición
	// ------------------------------------------------------------------

	@Test
	void edicionCambiaNombreYDireccion() throws Exception {
		Long id = crearSucursal(ownerA.bearerToken(), "Casa central");

		mockMvc.perform(put("/api/business/branches/" + id)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Sucursal Renovada", "Calle Nueva 123")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.name").value("Sucursal Renovada"))
				.andExpect(jsonPath("$.address").value("Calle Nueva 123"));
	}

	@Test
	void edicionDeIdAjenoResuelve404YNoTocaLaFila() throws Exception {
		Long idAjeno = crearSucursal(ownerB.bearerToken(), "Sucursal del vecino");

		mockMvc.perform(put("/api/business/branches/" + idAjeno)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Hackeada", "")))
				.andExpect(status().isNotFound());

		Branch fila = branchRepository.findById(idAjeno).orElseThrow();
		assertThat(fila.getName()).isEqualTo("Sucursal del vecino");
	}

	// ------------------------------------------------------------------
	// Baja lógica
	// ------------------------------------------------------------------

	@Test
	void bajaEsLogicaYReversibleLaFilaSigueExisteConActiveFalse() throws Exception {
		Long id = crearSucursal(ownerA.bearerToken(), "Sucursal a cerrar");
		// Compañera activa: el guard de última activa (S3) no corresponde en
		// este test, que fija la baja lógica en sí.
		crearSucursal(ownerA.bearerToken(), "Compañera activa");

		mockMvc.perform(delete("/api/business/branches/" + id)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		// La fila sigue existiendo y aparece en el panel con active=false.
		assertThat(branchRepository.findById(id)).isPresent();
		mockMvc.perform(get("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].active").value(false))
				.andExpect(jsonPath("$[1].active").value(true));

		// Sin borrado físico: la fila sigue en SU negocio (count global no:
		// la DB de dev tiene sucursales reales de otros negocios).
		assertThat(branchRepository.countByBusinessId(BUSINESS_A_ID)).isEqualTo(2);
	}

	@Test
	void bajaDeIdAjenoResuelve404() throws Exception {
		Long idAjeno = crearSucursal(ownerB.bearerToken(), "Sucursal del vecino");

		mockMvc.perform(delete("/api/business/branches/" + idAjeno)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isNotFound());

		assertThat(branchRepository.findById(idAjeno).orElseThrow().getActive()).isTrue();
	}

	@Test
	void endpointsSinTokenSeRechazanCon401() throws Exception {
		mockMvc.perform(get("/api/business/branches"))
				.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------
	// Reactivación (S3/L9: la baja es lógica y reversible)
	// ------------------------------------------------------------------

	@Test
	void reactivarUnaSucursalPropiaInactivaLaDejaActiva() throws Exception {
		Long id = crearSucursal(ownerA.bearerToken(), "Sucursal reabierta");
		// Compañera activa: la baja de prueba no choca con el guard de última
		// activa (S3).
		crearSucursal(ownerA.bearerToken(), "Compañera activa");

		mockMvc.perform(delete("/api/business/branches/" + id)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		mockMvc.perform(post("/api/business/branches/" + id + "/reactivar")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.active").value(true));

		// La fila queda activa (baja reversible, nunca borrado físico).
		assertThat(branchRepository.findById(id).orElseThrow().getActive()).isTrue();
	}

	@Test
	void reactivarUnaYaActivaEsIdempotente() throws Exception {
		Long id = crearSucursal(ownerA.bearerToken(), "Casa central");

		mockMvc.perform(post("/api/business/branches/" + id + "/reactivar")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.active").value(true));

		assertThat(branchRepository.findById(id).orElseThrow().getActive()).isTrue();
	}

	@Test
	void reactivarDeIdAjenoResuelve404YNoTocaLaFila() throws Exception {
		Long idAjeno = crearSucursal(ownerB.bearerToken(), "Sucursal del vecino");

		mockMvc.perform(post("/api/business/branches/" + idAjeno + "/reactivar")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Sucursal no encontrada"));

		assertThat(branchRepository.findById(idAjeno).orElseThrow().getActive()).isTrue();
	}

	// ------------------------------------------------------------------
	// Guard de última activa (S3: sin activas no hay destino de pedidos)
	// ------------------------------------------------------------------

	@Test
	void bajaDeLaUltimaActivaSeRechazaCon409YLaFilaSigueActiva() throws Exception {
		Long id = crearSucursal(ownerA.bearerToken(), "Única activa");

		mockMvc.perform(delete("/api/business/branches/" + id)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("No podés dar de baja la última sucursal activa"));

		// La baja fue rechazada: la fila SIGUE activa.
		assertThat(branchRepository.findById(id).orElseThrow().getActive()).isTrue();
	}

	@Test
	void bajaDeUnaActivaEntreVariasSigueFuncionando() throws Exception {
		crearSucursal(ownerA.bearerToken(), "Casa central");
		Long id = crearSucursal(ownerA.bearerToken(), "Sucursal Norte");

		mockMvc.perform(delete("/api/business/branches/" + id)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		// La otra activa queda en pie: el negocio nunca queda sin destino.
		assertThat(branchRepository.findByBusinessIdAndActiveTrueOrderByIdAsc(BUSINESS_A_ID)).hasSize(1);
	}

	@Test
	void bajaDeUnaSucursalYaInactivaSigueFuncionando() throws Exception {
		Long id = crearSucursal(ownerA.bearerToken(), "Casa central");
		Long otra = crearSucursal(ownerA.bearerToken(), "Sucursal Norte");

		// Primero se da de baja la "otra": quedan dos activas, la baja es legal.
		mockMvc.perform(delete("/api/business/branches/" + otra)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk());

		// Dar de baja de nuevo una inactiva sigue siendo 200 (no hay guard: la
		// activa en pie es la otra).
		mockMvc.perform(delete("/api/business/branches/" + otra)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		assertThat(branchRepository.findById(id).orElseThrow().getActive()).isTrue();
	}

}
