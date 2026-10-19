package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.dto.BusinessHoursDTO;
import com.carrito.saas.dto.BusinessProfileDTO;
import com.carrito.saas.dto.BusinessShiftDTO;
import com.carrito.saas.dto.DateExceptionDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessDateException;
import com.carrito.saas.repository.entity.BusinessHours;
import com.carrito.saas.repository.jpa.BusinessDateExceptionRepository;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import tools.jackson.databind.json.JsonMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Storefront profile contract: the public menu carries the storefront fields
 * plus the structured opening hours (no second call), and the owner reads and
 * writes the same profile through {@code /api/business/profile}, always scoped
 * to the JWT's business.
 *
 * <p>Hours are shifts per weekday following the ISO convention
 * (1 = Monday … 7 = Sunday). The response always carries all seven days,
 * ordered, each with its shifts list — empty when the day is closed. The PUT
 * replaces the list wholesale: a day missing from the payload, or present
 * with an empty shifts list, is a closed day and stores no rows. The server
 * sorts each day's shifts by opening time, so the client may send them in
 * any order.</p>
 *
 * <p>MockMvc is assembled manually with
 * {@link MockMvcBuilders#webAppContextSetup} plus the real security
 * {@link FilterChainProxy} (same setup as {@code AdminProductAndComboEndpointTests});
 * {@code @Transactional} rolls back every fixture at test end.</p>
 */
@SpringBootTest
@Transactional
class BusinessProfileAndStorefrontTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_801_101L;
	private static final Long BUSINESS_B_ID = 987_801_102L;

	private static final String SLUG_A = "storefront-a";
	private static final String SLUG_B = "storefront-b";

	private static final String OWNER_USERNAME = "storefront-owner";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private BusinessHoursRepository businessHoursRepository;

	@Autowired
	private BusinessDateExceptionRepository businessDateExceptionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private BusinessUserRepository businessUserRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@PersistenceContext
	private EntityManager entityManager;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_B_ID, SLUG_B);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Business businessA() {
		return businessRepository.findById(BUSINESS_A_ID).orElseThrow();
	}

	private Business businessB() {
		return businessRepository.findById(BUSINESS_B_ID).orElseThrow();
	}

	private void seedStorefront(Long businessId, String logo, String banner, String address, String instagram) {
		Business business = businessRepository.findById(businessId).orElseThrow();
		business.setLogoUrl(logo);
		business.setBannerUrl(banner);
		business.setAddress(address);
		business.setInstagramUrl(instagram);
		businessRepository.saveAndFlush(business);
	}

	private BusinessHours hour(Business business, int dayOfWeek, int position, String opens, String closes) {
		BusinessHours h = new BusinessHours();
		h.setBusiness(business);
		h.setDayOfWeek(dayOfWeek);
		h.setPosition(position);
		h.setOpensAt(opens == null ? null : LocalTime.parse(opens));
		h.setClosesAt(closes == null ? null : LocalTime.parse(closes));
		return businessHoursRepository.saveAndFlush(h);
	}

	private BusinessShiftDTO shiftDTO(String opens, String closes) {
		BusinessShiftDTO shift = new BusinessShiftDTO();
		shift.setOpensAt(opens == null ? null : LocalTime.parse(opens));
		shift.setClosesAt(closes == null ? null : LocalTime.parse(closes));
		return shift;
	}

	private BusinessHoursDTO hoursDTO(int dayOfWeek, BusinessShiftDTO... shifts) {
		BusinessHoursDTO dto = new BusinessHoursDTO();
		dto.setDayOfWeek(dayOfWeek);
		dto.setShifts(new ArrayList<>(List.of(shifts)));
		return dto;
	}

	private String body(BusinessProfileDTO profile) throws Exception {
		return objectMapper.writeValueAsString(profile);
	}

	private BusinessProfileDTO profileWithHours(String logo, String banner, String address, String instagram,
			List<BusinessHoursDTO> hours) {
		BusinessProfileDTO dto = new BusinessProfileDTO();
		dto.setName("Negocio Storefront");
		dto.setLogoUrl(logo);
		dto.setBannerUrl(banner);
		dto.setAddress(address);
		dto.setInstagramUrl(instagram);
		dto.setHours(hours);
		// La lista vacía es el "sin fechas especiales" explícito; ausente significa
		// "no tocar" en la actualización parcial.
		dto.setDateExceptions(List.of());
		return dto;
	}

	// ------------------------------------------------------------------
	// Public menu payload
	// ------------------------------------------------------------------

	/**
	 * The menu payload must carry the storefront so the menu needs no second
	 * call, and the hours must come out as the seven ISO days in order —
	 * each with its shifts sorted by opening time — even though the rows were
	 * seeded out of order (5, 6, 1, 3).
	 */
	@Test
	void publicMenuCarriesStorefrontAndHoursOrderedMondayToSunday() throws Exception {

		seedStorefront(BUSINESS_A_ID,
				"https://cdn.ejemplo.com/logo.png",
				"https://cdn.ejemplo.com/banner.jpg",
				"Av. Siempre Viva 742",
				"https://instagram.com/_negocio");

		Business a = businessA();
		hour(a, 6, 0, "10:00:00", "23:00:00");
		hour(a, 1, 0, "09:00:00", "13:00:00");
		hour(a, 3, 0, "09:30:00", "22:00:00");
		// Viernes con dos turnos: mediodía y noche (la noche cruza la medianoche).
		hour(a, 5, 0, "11:00:00", "15:00:00");
		hour(a, 5, 1, "19:00:00", "00:30:00");

		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.logoUrl").value("https://cdn.ejemplo.com/logo.png"))
				.andExpect(jsonPath("$.business.bannerUrl").value("https://cdn.ejemplo.com/banner.jpg"))
				.andExpect(jsonPath("$.business.address").value("Av. Siempre Viva 742"))
				.andExpect(jsonPath("$.business.instagramUrl").value("https://instagram.com/_negocio"))
				.andExpect(jsonPath("$.business.hours", hasSize(7)))
				.andExpect(jsonPath("$.business.hours[0].dayOfWeek").value(1))
				.andExpect(jsonPath("$.business.hours[0].shifts", hasSize(1)))
				.andExpect(jsonPath("$.business.hours[0].shifts[0].opensAt").value("09:00:00"))
				.andExpect(jsonPath("$.business.hours[0].shifts[0].closesAt").value("13:00:00"))
				.andExpect(jsonPath("$.business.hours[1].dayOfWeek").value(2))
				.andExpect(jsonPath("$.business.hours[1].shifts", hasSize(0)))
				.andExpect(jsonPath("$.business.hours[2].dayOfWeek").value(3))
				.andExpect(jsonPath("$.business.hours[2].shifts[0].opensAt").value("09:30:00"))
				.andExpect(jsonPath("$.business.hours[4].dayOfWeek").value(5))
				.andExpect(jsonPath("$.business.hours[4].shifts", hasSize(2)))
				.andExpect(jsonPath("$.business.hours[4].shifts[0].opensAt").value("11:00:00"))
				.andExpect(jsonPath("$.business.hours[4].shifts[0].closesAt").value("15:00:00"))
				.andExpect(jsonPath("$.business.hours[4].shifts[1].opensAt").value("19:00:00"))
				.andExpect(jsonPath("$.business.hours[4].shifts[1].closesAt").value("00:30:00"))
				.andExpect(jsonPath("$.business.hours[5].dayOfWeek").value(6))
				.andExpect(jsonPath("$.business.hours[5].shifts[0].closesAt").value("23:00:00"))
				.andExpect(jsonPath("$.business.hours[6].dayOfWeek").value(7))
				.andExpect(jsonPath("$.business.hours[6].shifts", hasSize(0)));
	}

	/**
	 * A business without a storefront keeps working: nulls and a COMPLETE
	 * seven-day hours list, all days closed (empty shifts). No client ever
	 * has to infer "missing day means closed".
	 */
	@Test
	void publicMenuWithoutStorefrontHasNullFieldsAndEmptyHours() throws Exception {

		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.logoUrl").value(equalTo(null)))
				.andExpect(jsonPath("$.business.bannerUrl").value(equalTo(null)))
				.andExpect(jsonPath("$.business.address").value(equalTo(null)))
				.andExpect(jsonPath("$.business.instagramUrl").value(equalTo(null)))
				.andExpect(jsonPath("$.business.hours", hasSize(7)))
				.andExpect(jsonPath("$.business.hours[0].dayOfWeek").value(1))
				.andExpect(jsonPath("$.business.hours[0].shifts", hasSize(0)))
				.andExpect(jsonPath("$.business.hours[3].shifts", hasSize(0)))
				.andExpect(jsonPath("$.business.hours[6].dayOfWeek").value(7))
				.andExpect(jsonPath("$.business.hours[6].shifts", hasSize(0)));
	}

	// ------------------------------------------------------------------
	// Owner profile: GET
	// ------------------------------------------------------------------

	/** GET returns the caller's own profile, never another business's. */
	@Test
	void getProfileReturnsCallersProfile() throws Exception {

		seedStorefront(BUSINESS_A_ID,
				"https://cdn.ejemplo.com/logo-a.png",
				"https://cdn.ejemplo.com/banner-a.jpg",
				"Av. del Propietario 111",
				"https://instagram.com/negocio.a");
		seedStorefront(BUSINESS_B_ID,
				"https://cdn.ejemplo.com/logo-b.png",
				"https://cdn.ejemplo.com/banner-b.jpg",
				"Av. del Vecino 222",
				"https://instagram.com/negocio.b");

		hour(businessA(), 2, 0, "11:00:00", "20:00:00");
		hour(businessB(), 5, 0, "12:00:00", "21:00:00");

		mockMvc.perform(get("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Business " + SLUG_A))
				.andExpect(jsonPath("$.logoUrl").value("https://cdn.ejemplo.com/logo-a.png"))
				.andExpect(jsonPath("$.bannerUrl").value("https://cdn.ejemplo.com/banner-a.jpg"))
				.andExpect(jsonPath("$.address").value("Av. del Propietario 111"))
				.andExpect(jsonPath("$.instagramUrl").value("https://instagram.com/negocio.a"))
				.andExpect(jsonPath("$.hours", hasSize(7)))
				.andExpect(jsonPath("$.hours[1].dayOfWeek").value(2))
				.andExpect(jsonPath("$.hours[1].shifts", hasSize(1)))
				.andExpect(jsonPath("$.hours[1].shifts[0].opensAt").value("11:00:00"))
				.andExpect(jsonPath("$.hours[1].shifts[0].closesAt").value("20:00:00"))
				.andExpect(jsonPath("$.hours[4].shifts", hasSize(0)));
	}

	// ------------------------------------------------------------------
	// Owner profile: PUT
	// ------------------------------------------------------------------

	/** PUT saves the fields and the hours; a day with an empty list is closed; name is editable. */
	@Test
	void putProfileSavesFieldsAndHours() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/nuevo-logo.png",
				"https://cdn.ejemplo.com/nuevo-banner.jpg",
				"Calle Nueva 456",
				"https://instagram.com/negocio.actual",
				List.of(
						hoursDTO(1, shiftDTO("09:00:00", "13:00:00")),
						hoursDTO(7)));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Negocio Storefront"))
				.andExpect(jsonPath("$.logoUrl").value("https://cdn.ejemplo.com/nuevo-logo.png"))
				.andExpect(jsonPath("$.address").value("Calle Nueva 456"))
				.andExpect(jsonPath("$.instagramUrl").value("https://instagram.com/negocio.actual"))
				.andExpect(jsonPath("$.hours", hasSize(7)))
				.andExpect(jsonPath("$.hours[0].dayOfWeek").value(1))
				.andExpect(jsonPath("$.hours[0].shifts", hasSize(1)))
				.andExpect(jsonPath("$.hours[0].shifts[0].opensAt").value("09:00:00"))
				.andExpect(jsonPath("$.hours[6].dayOfWeek").value(7))
				.andExpect(jsonPath("$.hours[6].shifts", hasSize(0)))
				// El viejo campo closed ya no existe en el contrato.
				.andExpect(jsonPath("$.hours[0].closed").doesNotExist());

		entityManager.flush();
		entityManager.clear();

		Business saved = businessA();
		assertThat(saved.getName()).as("el nombre es editable").isEqualTo("Negocio Storefront");
		assertThat(saved.getLogoUrl()).isEqualTo("https://cdn.ejemplo.com/nuevo-logo.png");
		assertThat(saved.getBannerUrl()).isEqualTo("https://cdn.ejemplo.com/nuevo-banner.jpg");
		assertThat(saved.getAddress()).isEqualTo("Calle Nueva 456");
		assertThat(saved.getInstagramUrl()).isEqualTo("https://instagram.com/negocio.actual");

		List<BusinessHours> savedHours =
				businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID);
		assertThat(savedHours).as("un día con la lista vacía no guarda filas").hasSize(1);
		assertThat(savedHours.get(0).getDayOfWeek()).isEqualTo(1);
		assertThat(savedHours.get(0).getPosition()).isEqualTo(0);
		assertThat(savedHours.get(0).getOpensAt()).isEqualTo(LocalTime.parse("09:00:00"));
		assertThat(savedHours.get(0).getClosesAt()).isEqualTo(LocalTime.parse("13:00:00"));
	}

	/** The hours list is replaced wholesale: a day missing from the payload is deleted. */
	@Test
	void secondPutOmittingDayDeletesIt() throws Exception {

		BusinessProfileDTO first = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección 1",
				"https://instagram.com/negocio",
				List.of(
						hoursDTO(1, shiftDTO("09:00:00", "13:00:00")),
						hoursDTO(2, shiftDTO("09:00:00", "13:00:00")),
						hoursDTO(3, shiftDTO("09:00:00", "13:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(first)))
				.andExpect(status().isOk());

		entityManager.flush();
		entityManager.clear();

		BusinessProfileDTO second = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección 1",
				"https://instagram.com/negocio",
				List.of(
						hoursDTO(1, shiftDTO("08:00:00", "12:00:00")),
						hoursDTO(2, shiftDTO("09:00:00", "13:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(second)))
				.andExpect(status().isOk());

		entityManager.flush();
		entityManager.clear();

		List<BusinessHours> savedHours =
				businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID);

		List<Integer> days = new ArrayList<>();
		savedHours.forEach(h -> days.add(h.getDayOfWeek()));

		assertThat(days)
				.as("el día ausente del segundo PUT debe haberse borrado")
				.containsExactly(1, 2);
		assertThat(savedHours.get(0).getOpensAt())
				.as("el día que sigue presente toma los nuevos horarios")
				.isEqualTo(LocalTime.parse("08:00:00"));
	}

	/** A day sent with an EMPTY shifts list is closed: same as omitting the day entirely. */
	@Test
	void putDayWithEmptyShiftsListIsClosedLikeOmittedDay() throws Exception {

		BusinessProfileDTO first = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección 1",
				"https://instagram.com/negocio",
				List.of(
						hoursDTO(1, shiftDTO("09:00:00", "13:00:00")),
						hoursDTO(3, shiftDTO("09:00:00", "13:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(first)))
				.andExpect(status().isOk());

		entityManager.flush();
		entityManager.clear();

		BusinessProfileDTO second = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección 1",
				"https://instagram.com/negocio",
				List.of(
						hoursDTO(1, shiftDTO("09:00:00", "13:00:00")),
						hoursDTO(3)));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(second)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hours", hasSize(7)))
				.andExpect(jsonPath("$.hours[2].dayOfWeek").value(3))
				.andExpect(jsonPath("$.hours[2].shifts", hasSize(0)));

		entityManager.flush();
		entityManager.clear();

		List<BusinessHours> savedHours =
				businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID);
		assertThat(savedHours).as("un día con la lista vacía no guarda filas").hasSize(1);
		assertThat(savedHours.get(0).getDayOfWeek()).isEqualTo(1);
	}

	/**
	 * The server sorts each day's shifts by opening time: the client may send
	 * them in any order and the round trip returns them in order, with
	 * {@code position} as the index after sorting.
	 */
	@Test
	void putRoundTripsTwoShiftsOfOneDayInOrder() throws Exception {

		// Enviados al revés: primero la noche, después el mediodía.
		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección 1",
				"https://instagram.com/negocio",
				List.of(hoursDTO(5,
						shiftDTO("19:00:00", "00:30:00"),
						shiftDTO("11:00:00", "15:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hours[4].dayOfWeek").value(5))
				.andExpect(jsonPath("$.hours[4].shifts", hasSize(2)))
				.andExpect(jsonPath("$.hours[4].shifts[0].opensAt").value("11:00:00"))
				.andExpect(jsonPath("$.hours[4].shifts[0].closesAt").value("15:00:00"))
				.andExpect(jsonPath("$.hours[4].shifts[1].opensAt").value("19:00:00"))
				.andExpect(jsonPath("$.hours[4].shifts[1].closesAt").value("00:30:00"));

		entityManager.flush();
		entityManager.clear();

		List<BusinessHours> savedHours =
				businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID);
		assertThat(savedHours).hasSize(2);
		assertThat(savedHours.get(0).getDayOfWeek()).isEqualTo(5);
		assertThat(savedHours.get(0).getPosition()).isEqualTo(0);
		assertThat(savedHours.get(0).getOpensAt()).isEqualTo(LocalTime.parse("11:00:00"));
		assertThat(savedHours.get(1).getDayOfWeek()).isEqualTo(5);
		assertThat(savedHours.get(1).getPosition()).isEqualTo(1);
		assertThat(savedHours.get(1).getOpensAt()).isEqualTo(LocalTime.parse("19:00:00"));
		assertThat(savedHours.get(1).getClosesAt())
				.as("el turno nocturno se guarda tal cual: cierra en la madrugada del día siguiente")
				.isEqualTo(LocalTime.parse("00:30:00"));
	}

	// ------------------------------------------------------------------
	// Actualización parcial: campo ausente conserva lo guardado
	// ------------------------------------------------------------------

	/**
	 * PUT parcial: sólo {@code address}. El resto — nombre, URLs, turnos y
	 * fechas especiales— queda exactamente como estaba, en la respuesta y en
	 * las filas de la base.
	 */
	@Test
	void putWithOnlyAddressKeepsEverythingElseIntact() throws Exception {

		seedStorefront(BUSINESS_A_ID,
				"https://cdn.ejemplo.com/logo.png",
				"https://cdn.ejemplo.com/banner.jpg",
				"Av. Siempre Viva 742",
				"https://instagram.com/_negocio");
		hour(businessA(), 5, 0, "11:00:00", "15:00:00");
		hour(businessA(), 5, 1, "19:00:00", "00:30:00");

		BusinessDateException marker = new BusinessDateException();
		marker.setBusiness(businessA());
		marker.setDate(LocalDate.of(2026, 12, 25));
		marker.setPosition(0);
		businessDateExceptionRepository.saveAndFlush(marker);

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"address\":\"Calle Parcial 789\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Business " + SLUG_A))
				.andExpect(jsonPath("$.logoUrl").value("https://cdn.ejemplo.com/logo.png"))
				.andExpect(jsonPath("$.bannerUrl").value("https://cdn.ejemplo.com/banner.jpg"))
				.andExpect(jsonPath("$.address").value("Calle Parcial 789"))
				.andExpect(jsonPath("$.instagramUrl").value("https://instagram.com/_negocio"))
				.andExpect(jsonPath("$.hours[4].shifts", hasSize(2)))
				.andExpect(jsonPath("$.dateExceptions", hasSize(1)));

		entityManager.flush();
		entityManager.clear();

		Business saved = businessA();
		assertThat(saved.getName()).isEqualTo("Business " + SLUG_A);
		assertThat(saved.getLogoUrl()).isEqualTo("https://cdn.ejemplo.com/logo.png");
		assertThat(saved.getBannerUrl()).isEqualTo("https://cdn.ejemplo.com/banner.jpg");
		assertThat(saved.getAddress()).isEqualTo("Calle Parcial 789");
		assertThat(saved.getInstagramUrl()).isEqualTo("https://instagram.com/_negocio");

		assertThat(businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID))
				.as("los turnos guardados quedan intactos").hasSize(2);
		assertThat(businessDateExceptionRepository.findByBusiness_IdOrderByDateAscPositionAsc(BUSINESS_A_ID))
				.as("las fechas especiales guardadas quedan intactas").hasSize(1);
	}

	/** {@code logoUrl: ""} limpia el campo (null en la base) sin tocar nada más. */
	@Test
	void putWithBlankLogoUrlClearsItAndKeepsEverythingElseIntact() throws Exception {

		seedStorefront(BUSINESS_A_ID,
				"https://cdn.ejemplo.com/logo.png",
				"https://cdn.ejemplo.com/banner.jpg",
				"Av. Siempre Viva 742",
				"https://instagram.com/_negocio");
		hour(businessA(), 5, 0, "11:00:00", "15:00:00");

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"logoUrl\":\"   \"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.logoUrl").value(equalTo(null)))
				.andExpect(jsonPath("$.name").value("Business " + SLUG_A))
				.andExpect(jsonPath("$.bannerUrl").value("https://cdn.ejemplo.com/banner.jpg"))
				.andExpect(jsonPath("$.address").value("Av. Siempre Viva 742"))
				.andExpect(jsonPath("$.instagramUrl").value("https://instagram.com/_negocio"))
				.andExpect(jsonPath("$.hours[4].shifts", hasSize(1)));

		entityManager.flush();
		entityManager.clear();

		assertThat(businessA().getLogoUrl()).as("el logo quedó en null en la base").isNull();
		assertThat(businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID))
				.hasSize(1);
	}

	/**
	 * {@code name: ""} es 400 (un negocio necesita nombre) y no toca lo
	 * guardado; {@code name} ausente conserva el nombre almacenado.
	 */
	@Test
	void putBlankNameIsRejectedAndAbsentNameKeepsStoredName() throws Exception {

		seedStorefront(BUSINESS_A_ID,
				"https://cdn.ejemplo.com/logo.png",
				"https://cdn.ejemplo.com/banner.jpg",
				"Av. Siempre Viva 742",
				"https://instagram.com/_negocio");
		hour(businessA(), 5, 0, "11:00:00", "15:00:00");

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("nombre")));

		entityManager.flush();
		entityManager.clear();

		assertThat(businessA().getName()).isEqualTo("Business " + SLUG_A);

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Nombre Parcial\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Nombre Parcial"))
				.andExpect(jsonPath("$.logoUrl").value("https://cdn.ejemplo.com/logo.png"))
				.andExpect(jsonPath("$.hours[4].shifts", hasSize(1)));

		entityManager.flush();
		entityManager.clear();

		assertThat(businessA().getName()).isEqualTo("Nombre Parcial");
		assertThat(businessA().getLogoUrl()).isEqualTo("https://cdn.ejemplo.com/logo.png");
		assertThat(businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID))
				.as("name solo no borra los turnos").hasSize(1);
	}

	/** {@code hours} ausente conserva los turnos guardados, fila por fila. */
	@Test
	void putWithHoursAbsentKeepsStoredShiftRows() throws Exception {

		hour(businessA(), 5, 0, "11:00:00", "15:00:00");
		hour(businessA(), 5, 1, "19:00:00", "00:30:00");
		hour(businessA(), 6, 0, "10:00:00", "23:00:00");

		BusinessProfileDTO payload = new BusinessProfileDTO();
		payload.setName("Negocio Storefront");
		payload.setLogoUrl("https://cdn.ejemplo.com/l.png");
		payload.setBannerUrl("https://cdn.ejemplo.com/b.jpg");
		payload.setAddress("Dirección");
		payload.setInstagramUrl("https://instagram.com/negocio");
		// dateExceptions presente y vacío: el "sin fechas" explícito.
		payload.setDateExceptions(List.of());

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hours[4].shifts", hasSize(2)))
				.andExpect(jsonPath("$.hours[4].shifts[1].closesAt").value("00:30:00"))
				.andExpect(jsonPath("$.hours[5].shifts", hasSize(1)));

		entityManager.flush();
		entityManager.clear();

		List<BusinessHours> savedHours =
				businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID);
		assertThat(savedHours).as("hours ausente no borra los turnos").hasSize(3);
		assertThat(savedHours).extracting(BusinessHours::getOpensAt)
				.containsExactly(LocalTime.parse("11:00:00"), LocalTime.parse("19:00:00"),
						LocalTime.parse("10:00:00"));
	}

	/** {@code hours: []} es el "ninguno" explícito: borra todos los turnos. */
	@Test
	void putWithEmptyHoursListClosesEveryDay() throws Exception {

		hour(businessA(), 5, 0, "11:00:00", "15:00:00");

		BusinessProfileDTO payload = new BusinessProfileDTO();
		payload.setName("Negocio Storefront");
		payload.setHours(List.of());
		payload.setDateExceptions(List.of());

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hours", hasSize(7)))
				.andExpect(jsonPath("$.hours[4].shifts", hasSize(0)));

		entityManager.flush();
		entityManager.clear();

		assertThat(businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID))
				.as("hours [] borra todas las filas de turnos").isEmpty();
	}

	/**
	 * Regresión del panel: el payload completo (todos los campos presentes)
	 * sigue comportándose como siempre — reemplazo en bloque de horas y fechas.
	 */
	@Test
	void putFullPayloadReplacesEverythingAsBefore() throws Exception {

		// Turno viejo que tiene que desaparecer con el reemplazo en bloque.
		hour(businessA(), 5, 0, "11:00:00", "15:00:00");

		BusinessProfileDTO payload = new BusinessProfileDTO();
		payload.setName("Negocio Completo");
		payload.setLogoUrl("https://cdn.ejemplo.com/nuevo.png");
		payload.setBannerUrl("https://cdn.ejemplo.com/nuevo.jpg");
		payload.setAddress("Dirección Nueva 1");
		payload.setInstagramUrl("https://instagram.com/nuevo");
		payload.setHours(List.of(hoursDTO(1, shiftDTO("09:00:00", "13:00:00"))));

		DateExceptionDTO closed = new DateExceptionDTO();
		closed.setDate(LocalDate.of(2026, 12, 25));
		closed.setShifts(new ArrayList<>());
		payload.setDateExceptions(new ArrayList<>(List.of(closed)));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Negocio Completo"))
				.andExpect(jsonPath("$.logoUrl").value("https://cdn.ejemplo.com/nuevo.png"))
				.andExpect(jsonPath("$.hours[0].shifts", hasSize(1)))
				.andExpect(jsonPath("$.hours[4].shifts", hasSize(0)))
				.andExpect(jsonPath("$.dateExceptions", hasSize(1)))
				.andExpect(jsonPath("$.dateExceptions[0].date").value("2026-12-25"))
				.andExpect(jsonPath("$.dateExceptions[0].shifts", hasSize(0)));

		entityManager.flush();
		entityManager.clear();

		Business saved = businessA();
		assertThat(saved.getAddress()).isEqualTo("Dirección Nueva 1");
		assertThat(businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID))
				.as("el reemplazo en bloque borra el turno viejo").hasSize(1);
		assertThat(businessDateExceptionRepository.findByBusiness_IdOrderByDateAscPositionAsc(BUSINESS_A_ID))
				.hasSize(1);
	}

	// ------------------------------------------------------------------
	// Data layer: several ordered shifts per weekday (multi-shift change)
	// ------------------------------------------------------------------

	/**
	 * Data-layer capability behind the multi-shift change: a weekday can hold
	 * several shifts ordered by {@code position}. The repository returns them
	 * in (dayOfWeek, position) order, and a duplicate
	 * (business_id, day_of_week, position) is rejected by
	 * {@code uq_business_hours_business_day_position}.
	 */
	@Test
	void repositoryStoresTwoShiftsPerDayOrderedByPositionAndRejectsDuplicates() {

		Business a = businessA();

		BusinessHours night = new BusinessHours();
		night.setBusiness(a);
		night.setDayOfWeek(5);
		night.setPosition(1);
		night.setOpensAt(LocalTime.parse("19:00:00"));
		night.setClosesAt(LocalTime.parse("00:30:00"));
		businessHoursRepository.saveAndFlush(night);

		BusinessHours midday = new BusinessHours();
		midday.setBusiness(a);
		midday.setDayOfWeek(5);
		midday.setPosition(0);
		midday.setOpensAt(LocalTime.parse("11:00:00"));
		midday.setClosesAt(LocalTime.parse("15:00:00"));
		businessHoursRepository.saveAndFlush(midday);

		// Persisted out of order (1 first, then 0): the query must sort them.
		List<BusinessHours> shifts =
				businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID);
		assertThat(shifts).hasSize(2);
		assertThat(shifts.get(0).getDayOfWeek()).isEqualTo(5);
		assertThat(shifts.get(0).getPosition()).isEqualTo(0);
		assertThat(shifts.get(0).getOpensAt()).isEqualTo(LocalTime.parse("11:00:00"));
		assertThat(shifts.get(1).getDayOfWeek()).isEqualTo(5);
		assertThat(shifts.get(1).getPosition()).isEqualTo(1);
		assertThat(shifts.get(1).getOpensAt()).isEqualTo(LocalTime.parse("19:00:00"));

		BusinessHours duplicate = new BusinessHours();
		duplicate.setBusiness(a);
		duplicate.setDayOfWeek(5);
		duplicate.setPosition(0);
		duplicate.setOpensAt(LocalTime.parse("16:00:00"));
		duplicate.setClosesAt(LocalTime.parse("18:00:00"));

		assertThatThrownBy(() -> businessHoursRepository.saveAndFlush(duplicate))
				.as("(business_id, day_of_week, position) es único")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ------------------------------------------------------------------
	// Validation (400 with Spanish, readable messages)
	// ------------------------------------------------------------------

	@Test
	void putRejectsInvalidUrl() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"www.ejemplo.com/logo-sin-esquema.png",
				"https://cdn.ejemplo.com/banner.jpg", "Dirección", "https://instagram.com/negocio",
				List.of());

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("logoUrl")))
				.andExpect(jsonPath("$.message", containsString("http")));
	}

	@Test
	void putRejectsDuplicateDay() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio",
				List.of(
						hoursDTO(4, shiftDTO("09:00:00", "13:00:00")),
						hoursDTO(4, shiftDTO("15:00:00", "20:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("4")))
				.andExpect(jsonPath("$.message", containsString("duplicado")));
	}

	/**
	 * Un local que cierra pasada la medianoche (viernes 11:00 → 00:30) es el
	 * caso más común en gastronomía: {@code closesAt < opensAt} significa
	 * "cierra en la madrugada del día siguiente" y debe guardarse tal cual.
	 */
	@Test
	void putAcceptsClosingAfterMidnightAndEchoesTimes() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio",
				List.of(hoursDTO(5, shiftDTO("11:00:00", "00:30:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hours", hasSize(7)))
				.andExpect(jsonPath("$.hours[4].dayOfWeek").value(5))
				.andExpect(jsonPath("$.hours[4].shifts", hasSize(1)))
				.andExpect(jsonPath("$.hours[4].shifts[0].opensAt").value("11:00:00"))
				.andExpect(jsonPath("$.hours[4].shifts[0].closesAt").value("00:30:00"));

		entityManager.flush();
		entityManager.clear();

		List<BusinessHours> savedHours =
				businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID);
		assertThat(savedHours).hasSize(1);
		assertThat(savedHours.get(0).getOpensAt())
				.as("la apertura del viernes se guarda tal cual")
				.isEqualTo(LocalTime.parse("11:00:00"));
		assertThat(savedHours.get(0).getClosesAt())
				.as("el cierre menor que la apertura significa madrugada del día siguiente y se guarda sin normalizar")
				.isEqualTo(LocalTime.parse("00:30:00"));
	}

	/**
	 * {@code opensAt == closesAt} es ambiguo (¿cero horas o 24?) y se rechaza
	 * con un mensaje propio; el viejo caso "apertura después del cierre" ya no
	 * es error: es el cierre que cruza la medianoche.
	 */
	@Test
	void putRejectsOpensAtEqualToClosesAt() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio",
				List.of(hoursDTO(1, shiftDTO("09:00:00", "09:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("opensAt")))
				.andExpect(jsonPath("$.message", containsString("closesAt")))
				.andExpect(jsonPath("$.message", containsString("iguales")));
	}

	@Test
	void putRejectsMissingTimesWhenOpen() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio",
				List.of(hoursDTO(1, shiftDTO(null, null))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("opensAt")))
				.andExpect(jsonPath("$.message", containsString("obligatorio")));
	}

	/** More than four shifts in one day is rejected (rule 6). */
	@Test
	void putRejectsMoreThanFourShiftsInOneDay() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio",
				List.of(hoursDTO(3,
						shiftDTO("06:00:00", "08:00:00"),
						shiftDTO("08:00:00", "10:00:00"),
						shiftDTO("10:00:00", "12:00:00"),
						shiftDTO("12:00:00", "14:00:00"),
						shiftDTO("14:00:00", "16:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("más de 4 turnos")));
	}

	/** Overlapping shifts inside one day are rejected (rule 7). */
	@Test
	void putRejectsOverlappingShiftsInOneDay() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio",
				List.of(hoursDTO(5,
						shiftDTO("11:00:00", "16:00:00"),
						shiftDTO("15:00:00", "20:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("superponerse")));
	}

	/**
	 * Sólo el último turno del día puede pasar la medianoche (regla 8). El
	 * turno nocturno 22:00 → 02:00 termina "pasado el día", así que con otro
	 * turno después es imposible que no se superponga: el mensaje específico
	 * de medianoche es el que le habla claro al dueño.
	 */
	@Test
	void putRejectsMidnightCrossingShiftThatIsNotLast() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio",
				List.of(hoursDTO(5,
						shiftDTO("22:00:00", "02:00:00"),
						shiftDTO("23:00:00", "23:30:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("medianoche")))
				.andExpect(jsonPath("$.message", containsString("último")));
	}

	/**
	 * El derrame del último turno de un día no puede pisar la apertura del
	 * día siguiente (regla 9): viernes 19:00 → 00:30 derrama 30 minutos, así
	 * que un sábado que abre 00:15 se rechaza; uno que abre 00:30 exacto
	 * pasa. Se chequea de forma cíclica: el derrame del domingo se compara
	 * contra el lunes.
	 */
	@Test
	void putRejectsSpillCollidingWithNextDayOpening() throws Exception {

		BusinessProfileDTO colliding = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio",
				List.of(
						hoursDTO(5, shiftDTO("19:00:00", "00:30:00")),
						hoursDTO(6, shiftDTO("00:15:00", "08:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(colliding)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("medianoche")))
				.andExpect(jsonPath("$.message", containsString("día siguiente")));

		// Borde exacto: abrir cuando el derrame recién termina es válido.
		BusinessProfileDTO touching = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio",
				List.of(
						hoursDTO(5, shiftDTO("19:00:00", "00:30:00")),
						hoursDTO(6, shiftDTO("00:30:00", "08:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(touching)))
				.andExpect(status().isOk());

		// Cíclico: domingo → lunes.
		BusinessProfileDTO sundayToMonday = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio",
				List.of(
						hoursDTO(7, shiftDTO("20:00:00", "01:00:00")),
						hoursDTO(1, shiftDTO("00:30:00", "08:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(sundayToMonday)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("día siguiente")));
	}

	/**
	 * Un payload con la forma vieja del contrato (turnos al nivel del día y el
	 * campo closed, ya inexistente) se deserializa con {@code shifts == null}:
	 * se rechaza con 400 y los turnos ya guardados quedan intactos. Ese es el
	 * punto del guard: un error de campo no borra el horario del negocio.
	 */
	@Test
	void putRejectsOldContractDayWithoutShiftsAndLeavesStoredHoursIntact() throws Exception {

		hour(businessA(), 5, 0, "11:00:00", "15:00:00");
		hour(businessA(), 5, 1, "19:00:00", "00:30:00");

		String oldShapePayload = """
				{"name":"Negocio Storefront",
				 "logoUrl":"https://cdn.ejemplo.com/l.png",
				 "bannerUrl":"https://cdn.ejemplo.com/b.jpg",
				 "address":"Dirección",
				 "instagramUrl":"https://instagram.com/negocio",
				 "hours":[{"dayOfWeek":5,"opensAt":"11:00","closesAt":"15:00","closed":false}]}
				""";

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(oldShapePayload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("shifts es obligatorio en cada día (una lista vacía significa cerrado)"));

		entityManager.flush();
		entityManager.clear();

		List<BusinessHours> savedHours =
				businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID);
		assertThat(savedHours).as("el rechazo del guard no borra los turnos ya guardados").hasSize(2);
		assertThat(savedHours).extracting(BusinessHours::getOpensAt)
				.containsExactly(LocalTime.parse("11:00:00"), LocalTime.parse("19:00:00"));
	}

	/** Igual que la forma vieja, pero con el día nombrado sin la clave shifts. */
	@Test
	void putRejectsDayNamedWithoutShiftsKeyAndLeavesStoredHoursIntact() throws Exception {

		hour(businessA(), 1, 0, "09:00:00", "13:00:00");

		String noShiftsKeyPayload = """
				{"name":"Negocio Storefront",
				 "logoUrl":"https://cdn.ejemplo.com/l.png",
				 "bannerUrl":"https://cdn.ejemplo.com/b.jpg",
				 "address":"Dirección",
				 "instagramUrl":"https://instagram.com/negocio",
				 "hours":[{"dayOfWeek":5}]}
				""";

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(noShiftsKeyPayload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("shifts es obligatorio en cada día (una lista vacía significa cerrado)"));

		entityManager.flush();
		entityManager.clear();

		List<BusinessHours> savedHours =
				businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_A_ID);
		assertThat(savedHours).as("el rechazo del guard no borra los turnos ya guardados").hasSize(1);
		assertThat(savedHours.get(0).getDayOfWeek()).isEqualTo(1);
		assertThat(savedHours.get(0).getOpensAt()).isEqualTo(LocalTime.parse("09:00:00"));
	}

	// ------------------------------------------------------------------
	// Security
	// ------------------------------------------------------------------

	/** No permitAll matcher covers /api/business/profile: anonymous is 401. */
	@Test
	void anonymousCallerIsRejectedOnProfileRoutes() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio", List.of());

		mockMvc.perform(get("/api/business/profile")).andExpect(status().isUnauthorized());

		mockMvc.perform(put("/api/business/profile")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * Tenant isolation: the endpoints take no id or slug, so the only way to
	 * touch another business is if the implementation looked at the wrong row.
	 * After A's PUT, B's fields and hours must be exactly as seeded.
	 */
	@Test
	void putIsTenantIsolatedForeignBusinessUntouched() throws Exception {

		seedStorefront(BUSINESS_B_ID,
				"https://cdn.ejemplo.com/logo-b.png",
				"https://cdn.ejemplo.com/banner-b.jpg",
				"Av. del Vecino 222",
				"https://instagram.com/negocio.b");
		hour(businessB(), 2, 0, "11:00:00", "20:00:00");

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/atacante.png",
				"https://cdn.ejemplo.com/atacante.jpg",
				"Intento de escribir en el vecino",
				"https://instagram.com/atacante",
				List.of(hoursDTO(6, shiftDTO("10:00:00", "23:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk());

		entityManager.flush();
		entityManager.clear();

		Business foreign = businessB();
		assertThat(foreign.getLogoUrl()).as("el PUT del vecino no debe tocar el negocio ajeno")
				.isEqualTo("https://cdn.ejemplo.com/logo-b.png");
		assertThat(foreign.getAddress()).isEqualTo("Av. del Vecino 222");
		assertThat(foreign.getName()).isEqualTo("Business " + SLUG_B);

		List<BusinessHours> foreignHours =
				businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(BUSINESS_B_ID);
		assertThat(foreignHours).hasSize(1);
		assertThat(foreignHours.get(0).getDayOfWeek()).isEqualTo(2);
	}

}
