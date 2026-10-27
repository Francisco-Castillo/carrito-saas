package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * WhatsApp por sucursal (multi-sucursal, odd/tasks/multi-sucursal.md,
 * S3/T5).
 *
 * <p>Reglas bajo prueba:</p>
 * <ul>
 *   <li>{@code phone} de la sucursal es opcional y guarda dígitos E.164 sin
 *       '+' (mismo formato canónico que {@code businesses.phone}): presente
 *       se valida (400 con forma inválida), ausente/blank es legítimo y dos
 *       sucursales sin número conviven.</li>
 *   <li>El número identifica un LUGAR: la unicidad es global (constraint
 *       {@code uq_branches_phone}) — duplicarlo en otra sucursal del mismo
 *       negocio o de OTRO negocio resuelve 409, no el 500 del constraint.
 *       Editar una sucursal manteniendo su propio número es válido.</li>
 *   <li>El menú público expone el número como {@code business.whatsappNumber}
 *       (el que usa el fallback de {@code wa.me}): el de la sucursal default
 *       si tiene, si no el del negocio (fallback legacy); negocio sin
 *       sucursales mantiene exactamente el comportamiento actual.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class BranchPhoneHttpTests {

	private static final Long BUSINESS_A_ID = 987_905_101L;
	private static final Long BUSINESS_B_ID = 987_905_102L;

	private static final String SLUG_A = "branch-phone-a";
	private static final String SLUG_B = "branch-phone-b";

	private static final String USERNAME_A = "branch-phone-owner-a";
	private static final String USERNAME_B = "branch-phone-owner-b";

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
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private String body(String name, String phone) {
		String phoneField = phone == null ? "" : "\"phone\": \"%s\",".formatted(phone);
		return """
				{ %s "name": "%s", "address": "Av. Siempre Viva 742"}""".formatted(phoneField, name);
	}

	private Long crearSucursal(String token, String name, String phone) throws Exception {
		String response = mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(name, phone)))
				.andReturn().getResponse().getContentAsString();
		return response.isBlank() ? null : objectMapper.readTree(response).path("id").asLong(0);
	}

	private Long seedBranch(long businessId, String name, String phone) {
		Business business = businessRepository.findById(businessId).orElseThrow();
		Branch branch = new Branch();
		branch.setBusiness(business);
		branch.setName(name);
		branch.setPhone(phone);
		return branchRepository.saveAndFlush(branch).getId();
	}

	// ------------------------------------------------------------------
	// Alta con phone
	// ------------------------------------------------------------------

	@Test
	void altaConPhoneValidoLoPersisteYLoDevuelve() throws Exception {
		mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Casa central", "5491123456789")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("Casa central"))
				.andExpect(jsonPath("$.phone").value("5491123456789"));

		Branch fila = branchRepository
				.findById(crearSucursal(ownerA.bearerToken(), "Palermo", "5491123456000")).orElseThrow();
		assertThat(fila.getPhone()).as("el teléfono quedó en la base").isEqualTo("5491123456000");
	}

	@Test
	void altaConPhoneInvalidoSeRechazaCon400() throws Exception {
		mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Casa central", "15-4444")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("El teléfono debe ser código de país y número, sin 0 ni 15"));
	}

	@Test
	void altaConPhoneDuplicadoEnOtraSucursalDelMismoNegocioSeRechazaCon409() throws Exception {
		crearSucursal(ownerA.bearerToken(), "Casa central", "5491123456789");

		mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Palermo", "5491123456789")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe una sucursal con ese número de WhatsApp"));
	}

	@Test
	void altaConPhoneDuplicadoEnSucursalDeOtroNegocioSeRechazaCon409() throws Exception {
		crearSucursal(ownerA.bearerToken(), "Casa central", "5491123456789");

		mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerB.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Del otro dueño", "5491123456789")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe una sucursal con ese número de WhatsApp"));
	}

	@Test
	void phoneAusenteEsLegitimoYDosSucursalesSinPhoneConviven() throws Exception {
		mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Casa central", null)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.phone").doesNotExist());

		mockMvc.perform(post("/api/business/branches")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Palermo", null)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.phone").doesNotExist());

		assertThat(branchRepository.findByBusinessIdOrderByIdAsc(BUSINESS_A_ID)).hasSize(2);
	}

	// ------------------------------------------------------------------
	// Edición con phone
	// ------------------------------------------------------------------

	@Test
	void edicionMantieneElPropioPhoneYCambiaAOtro() throws Exception {
		Long id = crearSucursal(ownerA.bearerToken(), "Casa central", "5491123456789");
		crearSucursal(ownerA.bearerToken(), "Palermo", "5491100000000");

		// Mantener el propio número no es duplicado consigo misma.
		mockMvc.perform(put("/api/business/branches/" + id)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Casa central", "5491123456789")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.phone").value("5491123456789"));

		// Tomar el número de la otra sucursal es 409.
		mockMvc.perform(put("/api/business/branches/" + id)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerA.bearerToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("Casa central", "5491100000000")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Ya existe una sucursal con ese número de WhatsApp"));
	}

	// ------------------------------------------------------------------
	// Menú público: fallback del wa.me
	// ------------------------------------------------------------------

	@Test
	void menuPublicoViajaElPhoneDeLaSucursalDefaultCuandoTiene() throws Exception {
		Business business = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		business.setPhone("5491199999999");
		businessRepository.saveAndFlush(business);
		seedBranch(BUSINESS_A_ID, "Casa central", "5491123456789");

		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.whatsappNumber").value("5491123456789"));
	}

	@Test
	void menuPublicoCaeAlPhoneDelNegocioCuandoLaDefaultNoTiene() throws Exception {
		Business business = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		business.setPhone("5491199999999");
		businessRepository.saveAndFlush(business);
		seedBranch(BUSINESS_A_ID, "Casa central", null);

		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.whatsappNumber").value("5491199999999"));
	}

	@Test
	void menuPublicoSinSucursalesMantieneElPhoneDelNegocio() throws Exception {
		Business business = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		business.setPhone("5491199999999");
		businessRepository.saveAndFlush(business);

		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.whatsappNumber").value("5491199999999"));
	}

}
