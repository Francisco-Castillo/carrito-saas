package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
 * Fecha especial (feriado u horario especial) sobre el perfil: una excepción
 * tiene la misma forma que un día de la semana — turnos ordenados, con la
 * lista vacía significando "cerrado" — y viaja en el mismo payload del perfil
 * ({@code dateExceptions}), con reemplazo en bloque en el PUT igual que las
 * horas.
 *
 * <p>Reglas propias: fecha obligatoria y real, sin duplicados, hasta 100
 * fechas, y cada fecha con las mismas reglas (y los mismos mensajes) que un
 * día: máximo 4 turnos, sin solapes, sólo el último cruza la medianoche. El
 * derrame del último turno se compara contra los turnos <em>efectivos</em> del
 * día siguiente: su propia excepción si la tiene, si no el día de la semana.
 * El campo {@code dateExceptions} es obligatorio en el PUT: un cliente que
 * olvide el campo no puede borrar los feriados del dueño en silencio.</p>
 */
@SpringBootTest
@Transactional
class BusinessDateExceptionTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_902_101L;
	private static final Long BUSINESS_B_ID = 987_902_102L;

	private static final String SLUG_A = "date-exceptions-a";
	private static final String SLUG_B = "date-exceptions-b";

	private static final String OWNER_USERNAME = "date-exceptions-owner";

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

	private BusinessShiftDTO shiftDTO(String opens, String closes) {
		BusinessShiftDTO shift = new BusinessShiftDTO();
		shift.setOpensAt(opens == null ? null : LocalTime.parse(opens));
		shift.setClosesAt(closes == null ? null : LocalTime.parse(closes));
		return shift;
	}

	private DateExceptionDTO exceptionDTO(LocalDate date, BusinessShiftDTO... shifts) {
		DateExceptionDTO dto = new DateExceptionDTO();
		dto.setDate(date);
		dto.setShifts(new ArrayList<>(List.of(shifts)));
		return dto;
	}

	private BusinessHoursDTO hoursDTO(int dayOfWeek, BusinessShiftDTO... shifts) {
		BusinessHoursDTO dto = new BusinessHoursDTO();
		dto.setDayOfWeek(dayOfWeek);
		dto.setShifts(new ArrayList<>(List.of(shifts)));
		return dto;
	}

	private BusinessProfileDTO profileWithExceptions(List<BusinessHoursDTO> hours,
			List<DateExceptionDTO> dateExceptions) {
		BusinessProfileDTO dto = new BusinessProfileDTO();
		dto.setName("Negocio Fechas");
		dto.setHours(hours);
		dto.setDateExceptions(dateExceptions);
		return dto;
	}

	private String body(BusinessProfileDTO profile) throws Exception {
		return objectMapper.writeValueAsString(profile);
	}

	private List<BusinessDateException> savedRows() {
		return businessDateExceptionRepository
				.findByBusiness_IdOrderByDateAscPositionAsc(BUSINESS_A_ID);
	}

	// ------------------------------------------------------------------
	// Round trip y reemplazo en bloque
	// ------------------------------------------------------------------

	/** Un feriado y un día especial sobreviven al PUT→GET con sus turnos ordenados. */
	@Test
	void putDateExceptionSurvivesRoundTrip() throws Exception {

		LocalDate christmas = LocalDate.of(2026, 12, 25);
		LocalDate christmasEve = LocalDate.of(2026, 12, 24);

		BusinessProfileDTO payload = profileWithExceptions(List.of(), List.of(
				exceptionDTO(christmasEve, shiftDTO("19:00:00", "00:30:00"), shiftDTO("11:00:00", "15:00:00")),
				exceptionDTO(christmas)));

		// Enviadas 24 después de 25 en otra prueba; acá 24 antes de 25 y el
		// servidor las devuelve ordenadas por fecha. Los turnos van a propósito
		// desordenados: el servidor ordena por apertura.
		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dateExceptions", hasSize(2)))
				.andExpect(jsonPath("$.dateExceptions[0].date").value("2026-12-24"))
				.andExpect(jsonPath("$.dateExceptions[0].shifts", hasSize(2)))
				.andExpect(jsonPath("$.dateExceptions[0].shifts[0].opensAt").value("11:00:00"))
				.andExpect(jsonPath("$.dateExceptions[0].shifts[0].closesAt").value("15:00:00"))
				.andExpect(jsonPath("$.dateExceptions[0].shifts[1].opensAt").value("19:00:00"))
				.andExpect(jsonPath("$.dateExceptions[0].shifts[1].closesAt").value("00:30:00"))
				.andExpect(jsonPath("$.dateExceptions[1].date").value("2026-12-25"))
				.andExpect(jsonPath("$.dateExceptions[1].shifts", hasSize(0)));

		entityManager.flush();
		entityManager.clear();

		// El GET del perfil devuelve lo mismo.
		mockMvc.perform(get("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dateExceptions", hasSize(2)))
				.andExpect(jsonPath("$.dateExceptions[0].date").value("2026-12-24"))
				.andExpect(jsonPath("$.dateExceptions[0].shifts[0].opensAt").value("11:00:00"))
				.andExpect(jsonPath("$.dateExceptions[0].shifts[1].closesAt").value("00:30:00"))
				.andExpect(jsonPath("$.dateExceptions[1].date").value("2026-12-25"))
				.andExpect(jsonPath("$.dateExceptions[1].shifts", hasSize(0)));

		// En la base: 3 filas (dos turnos del 24 + el marcador de cerrado del
		// 25), ordenadas por (date, position).
		List<BusinessDateException> rows = savedRows();
		assertThat(rows).hasSize(3);
		assertThat(rows.get(0).getDate()).isEqualTo(christmasEve);
		assertThat(rows.get(0).getPosition()).isEqualTo(0);
		assertThat(rows.get(0).getOpensAt()).isEqualTo(LocalTime.parse("11:00:00"));
		assertThat(rows.get(1).getDate()).isEqualTo(christmasEve);
		assertThat(rows.get(1).getPosition()).isEqualTo(1);
		assertThat(rows.get(1).getOpensAt()).isEqualTo(LocalTime.parse("19:00:00"));
		assertThat(rows.get(2).getDate()).isEqualTo(christmas);
		assertThat(rows.get(2).getPosition()).isEqualTo(0);
		assertThat(rows.get(2).getOpensAt()).as("el feriado cerrado es una fila marcadora sin horarios").isNull();
		assertThat(rows.get(2).getClosesAt()).isNull();
	}

	/** Una fecha con la lista de turnos vacía es cerrado: feriado guardado tal cual. */
	@Test
	void putDateWithEmptyShiftsListClosesTheDate() throws Exception {

		LocalDate holiday = LocalDate.of(2026, 12, 25);

		BusinessProfileDTO payload = profileWithExceptions(List.of(), List.of(exceptionDTO(holiday)));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dateExceptions", hasSize(1)))
				.andExpect(jsonPath("$.dateExceptions[0].date").value("2026-12-25"))
				.andExpect(jsonPath("$.dateExceptions[0].shifts", hasSize(0)));

		entityManager.flush();
		entityManager.clear();

		List<BusinessDateException> rows = savedRows();
		assertThat(rows).as("la fecha cerrada se guarda (marcador), no desaparece").hasSize(1);
		assertThat(rows.get(0).getDate()).isEqualTo(holiday);
		assertThat(rows.get(0).getPosition()).isEqualTo(0);
		assertThat(rows.get(0).getOpensAt()).isNull();
	}

	/** Reemplazo en bloque: una fecha ausente del segundo PUT vuelve a ser un día normal. */
	@Test
	void secondPutOmittingDateMakesItNormalAgain() throws Exception {

		LocalDate d1 = LocalDate.of(2026, 12, 24);
		LocalDate d2 = LocalDate.of(2026, 12, 25);

		BusinessProfileDTO first = profileWithExceptions(List.of(), List.of(
				exceptionDTO(d1, shiftDTO("11:00:00", "15:00:00")),
				exceptionDTO(d2)));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(first)))
				.andExpect(status().isOk());

		entityManager.flush();
		entityManager.clear();

		BusinessProfileDTO second = profileWithExceptions(List.of(),
				List.of(exceptionDTO(d1, shiftDTO("09:00:00", "13:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(second)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dateExceptions", hasSize(1)))
				.andExpect(jsonPath("$.dateExceptions[0].date").value("2026-12-24"));

		entityManager.flush();
		entityManager.clear();

		List<BusinessDateException> rows = savedRows();
		assertThat(rows).as("la fecha ausente del segundo PUT debe haberse borrado").hasSize(1);
		assertThat(rows.get(0).getDate()).isEqualTo(d1);
		assertThat(rows.get(0).getOpensAt()).isEqualTo(LocalTime.parse("09:00:00"));
	}

	// ------------------------------------------------------------------
	// Validación: reglas propias de la lista de fechas
	// ------------------------------------------------------------------

	@Test
	void putRejectsDuplicateDates() throws Exception {

		LocalDate d = LocalDate.of(2026, 12, 25);

		BusinessProfileDTO payload = profileWithExceptions(List.of(), List.of(
				exceptionDTO(d, shiftDTO("11:00:00", "15:00:00")),
				exceptionDTO(d, shiftDTO("16:00:00", "20:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Ya hay una excepción para esa fecha"));
	}

	/** Una fecha inexistente (2026-02-30) se rechaza: no hay 30 de febrero. */
	@Test
	void putRejectsImpossibleDate() throws Exception {

		String payload = """
				{"name":"Negocio Fechas",
				 "hours":[],
				 "dateExceptions":[{"date":"2026-02-30","shifts":[]}]}
				""";

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", containsString("2026-02-30")));
	}

	@Test
	void putRejectsMoreThanOneHundredDates() throws Exception {

		LocalDate base = LocalDate.of(2027, 1, 1);
		List<DateExceptionDTO> tooMany = new ArrayList<>();
		for (int i = 0; i < 101; i++) {
			tooMany.add(exceptionDTO(base.plusDays(i)));
		}

		BusinessProfileDTO payload = profileWithExceptions(List.of(), tooMany);

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("No se pueden cargar más de 100 fechas especiales"));

		entityManager.flush();
		entityManager.clear();
		assertThat(savedRows()).as("el rechazo no toca lo ya guardado").isEmpty();
	}

	/**
	 * El campo {@code dateExceptions} es obligatorio en el PUT: como el guard
	 * de {@code shifts} en un día, impide que un cliente que manda la forma
	 * vieja del contrato borre los feriados del dueño en silencio. Los 400 no
	 * tocan lo ya guardado.
	 */
	@Test
	void putRejectsMissingDateExceptionsFieldAndLeavesStoredExceptionsIntact() throws Exception {

		LocalDate holiday = LocalDate.of(2026, 12, 25);
		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(profileWithExceptions(List.of(), List.of(exceptionDTO(holiday)))))
				)
				.andExpect(status().isOk());

		entityManager.flush();
		entityManager.clear();

		String noFieldPayload = """
				{"name":"Negocio Fechas",
				 "hours":[]}
				""";

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(noFieldPayload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("dateExceptions es obligatorio (una lista vacía significa que no hay fechas especiales)"));

		entityManager.flush();
		entityManager.clear();

		assertThat(savedRows()).as("el rechazo del guard no borra las fechas ya guardadas").hasSize(1);
	}

	/** Igual que el guard de shifts en un día: una fecha nombrada sin la clave shifts. */
	@Test
	void putRejectsDateNamedWithoutShiftsKey() throws Exception {

		String payload = """
				{"name":"Negocio Fechas",
				 "hours":[],
				 "dateExceptions":[{"date":"2026-12-25"}]}
				""";

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("shifts es obligatorio en cada fecha (una lista vacía significa cerrado)"));
	}

	// ------------------------------------------------------------------
	// Validación: las mismas reglas (y mensajes) que un día
	// ------------------------------------------------------------------

	@Test
	void putRejectsOverlappingShiftsInOneDate() throws Exception {

		LocalDate d = LocalDate.of(2026, 12, 24);

		BusinessProfileDTO payload = profileWithExceptions(List.of(), List.of(
				exceptionDTO(d,
						shiftDTO("11:00:00", "16:00:00"),
						shiftDTO("15:00:00", "20:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Los turnos de un día no pueden superponerse"));
	}

	@Test
	void putRejectsMidnightCrossingShiftThatIsNotLastInOneDate() throws Exception {

		LocalDate d = LocalDate.of(2026, 12, 24);

		BusinessProfileDTO payload = profileWithExceptions(List.of(), List.of(
				exceptionDTO(d,
						shiftDTO("22:00:00", "02:00:00"),
						shiftDTO("23:00:00", "23:30:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("Un turno que pasa la medianoche tiene que ser el último del día"));
	}

	/**
	 * El derrame del último turno de una fecha se compara contra los turnos
	 * <em>efectivos</em> del día siguiente: su propia excepción si la tiene,
	 * si no el día de la semana. Así una excepción no puede abrir un agujero
	 * en el horario normal, y un feriado siguiente (cerrado) no falso-rechaza.
	 */
	@Test
	void putRejectsExceptionSpillCollidingWithEffectiveNextDay() throws Exception {

		// 2027-03-05 es viernes; su excepción derrama 30 minutos (00:30).
		LocalDate friday = LocalDate.of(2027, 3, 5);
		LocalDate saturday = friday.plusDays(1);
		assertThat(friday.getDayOfWeek().getValue()).isEqualTo(5);

		// Sábado con excepción que abre antes del derrame: se rechaza.
		BusinessProfileDTO colliding = profileWithExceptions(List.of(), List.of(
				exceptionDTO(friday, shiftDTO("19:00:00", "00:30:00")),
				exceptionDTO(saturday, shiftDTO("00:15:00", "08:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(colliding)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("El turno que pasa la medianoche se superpone con la apertura del día siguiente"));

		// Sábado feriado (cerrado) aunque el día de la semana abriría 00:15:
		// el "día siguiente" efectivo es la excepción, cerrada, así que pasa.
		BusinessProfileDTO holidayNext = profileWithExceptions(
				List.of(hoursDTO(saturday.getDayOfWeek().getValue(), shiftDTO("00:15:00", "08:00:00"))),
				List.of(
						exceptionDTO(friday, shiftDTO("19:00:00", "00:30:00")),
						exceptionDTO(saturday)));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(holidayNext)))
				.andExpect(status().isOk());

		// Sin excepción el sábado: el derrame se compara contra el día de la
		// semana, que abre 00:15 — se rechaza.
		BusinessProfileDTO weekdayNext = profileWithExceptions(
				List.of(hoursDTO(saturday.getDayOfWeek().getValue(), shiftDTO("00:15:00", "08:00:00"))),
				List.of(exceptionDTO(friday, shiftDTO("19:00:00", "00:30:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(weekdayNext)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("El turno que pasa la medianoche se superpone con la apertura del día siguiente"));

		// Borde exacto: abrir cuando el derrame recién termina es válido.
		BusinessProfileDTO touching = profileWithExceptions(List.of(), List.of(
				exceptionDTO(friday, shiftDTO("19:00:00", "00:30:00")),
				exceptionDTO(saturday, shiftDTO("00:30:00", "08:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(touching)))
				.andExpect(status().isOk());
	}

	// ------------------------------------------------------------------
	 // Menú público
	// ------------------------------------------------------------------

	/** El menú público lleva las fechas especiales, ordenadas por fecha. */
	@Test
	void publicMenuCarriesDateExceptionsOrderedByDate() throws Exception {

		Business a = businessRepository.findById(BUSINESS_A_ID).orElseThrow();

		// Sembradas desordenadas (25 antes que 24, posiciones al revés) para
		// probar el orden de la consulta.
		BusinessDateException later = new BusinessDateException();
		later.setBusiness(a);
		later.setDate(LocalDate.of(2026, 12, 25));
		later.setPosition(0);
		businessDateExceptionRepository.saveAndFlush(later);

		BusinessDateException eveNight = new BusinessDateException();
		eveNight.setBusiness(a);
		eveNight.setDate(LocalDate.of(2026, 12, 24));
		eveNight.setPosition(1);
		eveNight.setOpensAt(LocalTime.parse("19:00:00"));
		eveNight.setClosesAt(LocalTime.parse("00:30:00"));
		businessDateExceptionRepository.saveAndFlush(eveNight);

		BusinessDateException eveMidday = new BusinessDateException();
		eveMidday.setBusiness(a);
		eveMidday.setDate(LocalDate.of(2026, 12, 24));
		eveMidday.setPosition(0);
		eveMidday.setOpensAt(LocalTime.parse("11:00:00"));
		eveMidday.setClosesAt(LocalTime.parse("15:00:00"));
		businessDateExceptionRepository.saveAndFlush(eveMidday);

		// Otro negocio: su fecha no tiene que aparecer en el menú de A.
		BusinessDateException foreign = new BusinessDateException();
		foreign.setBusiness(businessRepository.findById(BUSINESS_B_ID).orElseThrow());
		foreign.setDate(LocalDate.of(2026, 12, 24));
		foreign.setPosition(0);
		foreign.setOpensAt(LocalTime.parse("10:00:00"));
		foreign.setClosesAt(LocalTime.parse("12:00:00"));
		businessDateExceptionRepository.saveAndFlush(foreign);

		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.dateExceptions", hasSize(2)))
				.andExpect(jsonPath("$.business.dateExceptions[0].date").value("2026-12-24"))
				.andExpect(jsonPath("$.business.dateExceptions[0].shifts", hasSize(2)))
				.andExpect(jsonPath("$.business.dateExceptions[0].shifts[0].opensAt").value("11:00:00"))
				.andExpect(jsonPath("$.business.dateExceptions[0].shifts[1].closesAt").value("00:30:00"))
				.andExpect(jsonPath("$.business.dateExceptions[1].date").value("2026-12-25"))
				.andExpect(jsonPath("$.business.dateExceptions[1].shifts", hasSize(0)));
	}

	/** Un negocio sin fechas especiales: el menú lleva la lista vacía, no null. */
	@Test
	public void publicMenuWithoutDateExceptionsCarriesEmptyList() throws Exception {

		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.dateExceptions", hasSize(0)));
	}

	// ------------------------------------------------------------------
	 // Tenant isolation
	// ------------------------------------------------------------------

	/** El PUT de A no toca las fechas de B. */
	@Test
	void putIsTenantIsolatedForeignDatesUntouched() throws Exception {

		Business b = businessRepository.findById(BUSINESS_B_ID).orElseThrow();
		BusinessDateException foreign = new BusinessDateException();
		foreign.setBusiness(b);
		foreign.setDate(LocalDate.of(2026, 12, 25));
		foreign.setPosition(0);
		businessDateExceptionRepository.saveAndFlush(foreign);

		LocalDate d = LocalDate.of(2027, 1, 1);
		BusinessProfileDTO payload = profileWithExceptions(List.of(),
				List.of(exceptionDTO(d, shiftDTO("10:00:00", "20:00:00"))));

		mockMvc.perform(put("/api/business/profile")
						.header(HttpHeaders.AUTHORIZATION, owner.authorizationHeader())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(payload)))
				.andExpect(status().isOk());

		entityManager.flush();
		entityManager.clear();

		List<BusinessDateException> foreignRows = businessDateExceptionRepository
				.findByBusiness_IdOrderByDateAscPositionAsc(BUSINESS_B_ID);
		assertThat(foreignRows).as("el PUT del vecino no debe tocar las fechas ajenas").hasSize(1);
		assertThat(foreignRows.get(0).getDate()).isEqualTo(LocalDate.of(2026, 12, 25));
	}

}
