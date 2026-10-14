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
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessHours;
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
 * <p>Hours follow the ISO convention (1 = Monday … 7 = Sunday) and are always
 * returned ordered Monday→Sunday, whatever order the rows live in. The PUT
 * replaces the list wholesale: a day missing from the payload is deleted, and
 * {@code closed: true} stores no times.</p>
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

	private BusinessHours hour(Business business, int dayOfWeek, String opens, String closes) {
		BusinessHours h = new BusinessHours();
		h.setBusiness(business);
		h.setDayOfWeek(dayOfWeek);
		h.setPosition(0);
		h.setOpensAt(opens == null ? null : LocalTime.parse(opens));
		h.setClosesAt(closes == null ? null : LocalTime.parse(closes));
		h.setClosed(opens == null);
		return businessHoursRepository.saveAndFlush(h);
	}

	private BusinessHoursDTO hoursDTO(int dayOfWeek, String opens, String closes, boolean closed) {
		BusinessHoursDTO dto = new BusinessHoursDTO();
		dto.setDayOfWeek(dayOfWeek);
		dto.setOpensAt(opens == null ? null : LocalTime.parse(opens));
		dto.setClosesAt(closes == null ? null : LocalTime.parse(closes));
		dto.setClosed(closed);
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
		return dto;
	}

	// ------------------------------------------------------------------
	// Public menu payload
	// ------------------------------------------------------------------

	/**
	 * The menu payload must carry the storefront so the menu needs no second
	 * call, and the hours must come out ordered Monday→Sunday even though the
	 * rows were seeded out of order (6, 1, 3).
	 */
	@Test
	void publicMenuCarriesStorefrontAndHoursOrderedMondayToSunday() throws Exception {

		seedStorefront(BUSINESS_A_ID,
				"https://cdn.ejemplo.com/logo.png",
				"https://cdn.ejemplo.com/banner.jpg",
				"Av. Siempre Viva 742",
				"https://instagram.com/_negocio");

		Business a = businessA();
		hour(a, 6, "10:00:00", "23:00:00");
		hour(a, 1, "09:00:00", "13:00:00");
		hour(a, 3, "09:30:00", "22:00:00");

		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.logoUrl").value("https://cdn.ejemplo.com/logo.png"))
				.andExpect(jsonPath("$.business.bannerUrl").value("https://cdn.ejemplo.com/banner.jpg"))
				.andExpect(jsonPath("$.business.address").value("Av. Siempre Viva 742"))
				.andExpect(jsonPath("$.business.instagramUrl").value("https://instagram.com/_negocio"))
				.andExpect(jsonPath("$.business.hours", hasSize(3)))
				.andExpect(jsonPath("$.business.hours[0].dayOfWeek").value(1))
				.andExpect(jsonPath("$.business.hours[0].opensAt").value("09:00:00"))
				.andExpect(jsonPath("$.business.hours[0].closesAt").value("13:00:00"))
				.andExpect(jsonPath("$.business.hours[0].closed").value(false))
				.andExpect(jsonPath("$.business.hours[1].dayOfWeek").value(3))
				.andExpect(jsonPath("$.business.hours[1].opensAt").value("09:30:00"))
				.andExpect(jsonPath("$.business.hours[2].dayOfWeek").value(6))
				.andExpect(jsonPath("$.business.hours[2].closesAt").value("23:00:00"));
	}

	/** A business without a storefront keeps working: nulls and an EMPTY hours list. */
	@Test
	void publicMenuWithoutStorefrontHasNullFieldsAndEmptyHours() throws Exception {

		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.logoUrl").value(equalTo(null)))
				.andExpect(jsonPath("$.business.bannerUrl").value(equalTo(null)))
				.andExpect(jsonPath("$.business.address").value(equalTo(null)))
				.andExpect(jsonPath("$.business.instagramUrl").value(equalTo(null)))
				.andExpect(jsonPath("$.business.hours", hasSize(0)));
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

		hour(businessA(), 2, "11:00:00", "20:00:00");
		hour(businessB(), 5, "12:00:00", "21:00:00");

		mockMvc.perform(get("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Business " + SLUG_A))
				.andExpect(jsonPath("$.logoUrl").value("https://cdn.ejemplo.com/logo-a.png"))
				.andExpect(jsonPath("$.bannerUrl").value("https://cdn.ejemplo.com/banner-a.jpg"))
				.andExpect(jsonPath("$.address").value("Av. del Propietario 111"))
				.andExpect(jsonPath("$.instagramUrl").value("https://instagram.com/negocio.a"))
				.andExpect(jsonPath("$.hours", hasSize(1)))
				.andExpect(jsonPath("$.hours[0].dayOfWeek").value(2))
				.andExpect(jsonPath("$.hours[0].opensAt").value("11:00:00"))
				.andExpect(jsonPath("$.hours[0].closesAt").value("20:00:00"))
				.andExpect(jsonPath("$.hours[0].closed").value(false));
	}

	// ------------------------------------------------------------------
	// Owner profile: PUT
	// ------------------------------------------------------------------

	/** PUT saves the fields and the hours; a closed day stores no times; name is editable. */
	@Test
	void putProfileSavesFieldsAndHours() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/nuevo-logo.png",
				"https://cdn.ejemplo.com/nuevo-banner.jpg",
				"Calle Nueva 456",
				"https://instagram.com/negocio.actual",
				List.of(
						hoursDTO(1, "09:00:00", "13:00:00", false),
						hoursDTO(7, null, null, true)));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Negocio Storefront"))
				.andExpect(jsonPath("$.logoUrl").value("https://cdn.ejemplo.com/nuevo-logo.png"))
				.andExpect(jsonPath("$.address").value("Calle Nueva 456"))
				.andExpect(jsonPath("$.instagramUrl").value("https://instagram.com/negocio.actual"))
				.andExpect(jsonPath("$.hours", hasSize(2)))
				.andExpect(jsonPath("$.hours[0].dayOfWeek").value(1))
				.andExpect(jsonPath("$.hours[1].dayOfWeek").value(7))
				.andExpect(jsonPath("$.hours[1].closed").value(true))
				.andExpect(jsonPath("$.hours[1].opensAt").value(equalTo(null)));

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
		assertThat(savedHours).hasSize(2);
		assertThat(savedHours.get(0).getDayOfWeek()).isEqualTo(1);
		assertThat(savedHours.get(0).getOpensAt()).isEqualTo(LocalTime.parse("09:00:00"));
		assertThat(savedHours.get(0).isClosed()).isFalse();
		assertThat(savedHours.get(1).getDayOfWeek()).isEqualTo(7);
		assertThat(savedHours.get(1).isClosed()).isTrue();
		assertThat(savedHours.get(1).getOpensAt()).as("un día cerrado no guarda horarios").isNull();
		assertThat(savedHours.get(1).getClosesAt()).isNull();
	}

	/** The hours list is replaced wholesale: a day missing from the payload is deleted. */
	@Test
	void secondPutOmittingDayDeletesIt() throws Exception {

		BusinessProfileDTO first = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección 1",
				"https://instagram.com/negocio",
				List.of(
						hoursDTO(1, "09:00:00", "13:00:00", false),
						hoursDTO(2, "09:00:00", "13:00:00", false),
						hoursDTO(3, "09:00:00", "13:00:00", false)));

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
						hoursDTO(1, "08:00:00", "12:00:00", false),
						hoursDTO(2, "09:00:00", "13:00:00", false)));

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
		night.setClosed(false);
		businessHoursRepository.saveAndFlush(night);

		BusinessHours midday = new BusinessHours();
		midday.setBusiness(a);
		midday.setDayOfWeek(5);
		midday.setPosition(0);
		midday.setOpensAt(LocalTime.parse("11:00:00"));
		midday.setClosesAt(LocalTime.parse("15:00:00"));
		midday.setClosed(false);
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
		duplicate.setClosed(false);

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
						hoursDTO(4, "09:00:00", "13:00:00", false),
						hoursDTO(4, "15:00:00", "20:00:00", false)));

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
				List.of(hoursDTO(5, "11:00:00", "00:30:00", false)));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hours", hasSize(1)))
				.andExpect(jsonPath("$.hours[0].dayOfWeek").value(5))
				.andExpect(jsonPath("$.hours[0].opensAt").value("11:00:00"))
				.andExpect(jsonPath("$.hours[0].closesAt").value("00:30:00"))
				.andExpect(jsonPath("$.hours[0].closed").value(false));

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
				List.of(hoursDTO(1, "09:00:00", "09:00:00", false)));

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
				List.of(hoursDTO(1, null, null, false)));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("opensAt")))
				.andExpect(jsonPath("$.message", containsString("obligatorio")));
	}

	// ------------------------------------------------------------------
	// Security
	// ------------------------------------------------------------------

	/** No permitAll matcher covers /api/business/profile: anonymous is 403. */
	@Test
	void anonymousCallerIsRejectedOnProfileRoutes() throws Exception {

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/l.png", "https://cdn.ejemplo.com/b.jpg", "Dirección",
				"https://instagram.com/negocio", List.of());

		mockMvc.perform(get("/api/business/profile")).andExpect(status().isForbidden());

		mockMvc.perform(put("/api/business/profile")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isForbidden());
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
		hour(businessB(), 2, "11:00:00", "20:00:00");

		BusinessProfileDTO payload = profileWithHours(
				"https://cdn.ejemplo.com/atacante.png",
				"https://cdn.ejemplo.com/atacante.jpg",
				"Intento de escribir en el vecino",
				"https://instagram.com/atacante",
				List.of(hoursDTO(6, "10:00:00", "23:00:00", false)));

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
