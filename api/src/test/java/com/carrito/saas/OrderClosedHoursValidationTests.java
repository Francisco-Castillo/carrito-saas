package com.carrito.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessDateException;
import com.carrito.saas.repository.entity.BusinessHours;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessDateExceptionRepository;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;

import java.time.Clock;

/**
 * The storefront badge ("Cerrado") is decorative: the public order endpoint
 * was accepting orders while the local was closed. This pins the backend
 * contract of the fix (S of the closed-hours order block): createOrder
 * resolves the business's EFFECTIVE open status with the same rules the
 * client's pure openStatus.ts applies — ordered shifts per day, a day with no
 * shifts is closed, closesAt < opensAt crosses midnight (only the day's last
 * shift may), exact-date exceptions override the weekly template (marker row
 * with null times = closed), a yearly exception matches month/day in any
 * year, the exact date wins over a yearly one, and yesterday's
 * midnight-crossing shift covers today's early morning.
 *
 * <p>The clock is FIXED (Monday 2026-10-19 02:00 in the business zone) via a
 * nested {@code @TestConfiguration}, so every case is deterministic no matter
 * when CI runs. MockMvc with the real security filter chain (pattern of
 * {@link OrderManagementDayEndpointTests}); {@code @Transactional} rolls
 * every fixture back at test end.</p>
 */
@SpringBootTest
@Transactional
class OrderClosedHoursValidationTests {

	private static final Long BUSINESS_ID = 987_714_101L;
	private static final String SLUG = "closed-hours-pizzeria";
	private static final LocalTime OPEN_ALL_MORNING = LocalTime.of(0, 0);
	private static final LocalTime CLOSE_NOON = LocalTime.of(12, 0);
	private static final LocalTime CLOSE_MIDNIGHT = LocalTime.of(0, 0);

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
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	private MockMvc mockMvc;

	@TestConfiguration
	static class FixedClockConfig {
		// Lunes 2026-10-19, 02:00 (-03): la madrugada del lunes, hora en que
		// sólo el derrame del turno de ayer (o una excepción) puede abrir.
		// @Primary: el Clock real de ClockConfig sigue existiendo en el contexto;
		// sin esto habría dos beans Clock y Spring no sabría cuál inyectar.
		@Bean
		@org.springframework.context.annotation.Primary
		Clock fixedTestClock() {
			return Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneId.of("-03:00"));
		}
	}

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_ID, SLUG);
	}

	// ------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------

	private long seedProduct() {
		Business business = businessRepository.findBySlug(SLUG).orElseThrow();

		Category category = new Category();
		category.setBusiness(business);
		category.setName("Cat " + SLUG);
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName("Producto Horario");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("30.00"));
		product.setStock(50);
		product.setActive(true);
		return productRepository.saveAndFlush(product).getId();
	}

	/** Semana completa abierta: 00:00–12:00 y 12:00–00:00 (cruza la medianoche). */
	private void seedAlwaysOpenWeek() {
		for (int day = 1; day <= 7; day++) {
			seedShift(day, 0, OPEN_ALL_MORNING, CLOSE_NOON);
			seedShift(day, 1, CLOSE_NOON, CLOSE_MIDNIGHT);
		}
	}

	private void seedShift(int dayOfWeek, int position, LocalTime opensAt, LocalTime closesAt) {
		Business business = businessRepository.findBySlug(SLUG).orElseThrow();

		BusinessHours shift = new BusinessHours();
		shift.setBusiness(business);
		shift.setDayOfWeek(dayOfWeek);
		shift.setPosition(position);
		shift.setOpensAt(opensAt);
		shift.setClosesAt(closesAt);
		businessHoursRepository.saveAndFlush(shift);
	}

	private void seedExactException(int year, int month, int day, boolean yearly, LocalTime opensAt, LocalTime closesAt) {
		Business business = businessRepository.findBySlug(SLUG).orElseThrow();

		BusinessDateException exception = new BusinessDateException();
		exception.setBusiness(business);
		exception.setDate(java.time.LocalDate.of(year, month, day));
		exception.setPosition(0);
		exception.setYearly(yearly);
		if (opensAt != null) {
			exception.setOpensAt(opensAt);
			exception.setClosesAt(closesAt);
		}
		businessDateExceptionRepository.saveAndFlush(exception);
	}

	private void postOrder() throws Exception {
		long productId = seedProduct();
		String body = """
				{
				  "customerName": "Cliente Horario",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [ { "productId": %d, "quantity": 1 } ]
				}
				""".formatted(productId);

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk());
	}

	// ------------------------------------------------------------------
	// El template semanal manda: día sin filas = cerrado
	// ------------------------------------------------------------------

	@Test
	void orderIsRejectedWhenTheWeeklyTemplateIsClosed() throws Exception {
		// Sin filas de horarios: el negocio nunca configuró horarios — cerrado
		// siempre, consistente con el badge de la portada. (RED: el endpoint
		// hoy acepta el pedido con 200.)
		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "customerName": "Cliente Horario",
								  "orderType": "RETIRO",
								  "paymentMethod": "EFECTIVO",
								  "items": [ { "productId": %d, "quantity": 1 } ]
								}
								""".formatted(seedProduct())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El local está cerrado"));
	}

	@Test
	void orderIsAcceptedWhenTheWeeklyTemplateIsOpen() throws Exception {
		// PRESERVE: la semana abierta cubre las 02:00 del lunes (ambos turnos
		// de cada día, el último cruza la medianoche), sin hueco de borde.
		seedAlwaysOpenWeek();
		postOrder();
	}

	// ------------------------------------------------------------------
	// Excepciones por fecha: la exacta y la anual
	// ------------------------------------------------------------------

	@Test
	void orderIsRejectedOnAnExactClosedDate() throws Exception {
		seedAlwaysOpenWeek();
		// Marcador de fecha cerrada: fila con times NULL (position 0).
		seedExactException(2026, 10, 19, false, null, null);

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "customerName": "Cliente Horario",
								  "orderType": "RETIRO",
								  "paymentMethod": "EFECTIVO",
								  "items": [ { "productId": %d, "quantity": 1 } ]
								}
								""".formatted(seedProduct())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El local está cerrado"));
	}

	@Test
	void orderIsRejectedOnAYearlyExceptionMatch() throws Exception {
		seedAlwaysOpenWeek();
		// Excepción anual del 19/10 sembrada para OTRO año: matchea por mes/día
		// en cualquier año (la Navidad es 25/12 todos los años).
		seedExactException(2025, 10, 19, true, null, null);

		mockMvc.perform(post("/api/orders/menu/" + SLUG)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "customerName": "Cliente Horario",
								  "orderType": "RETIRO",
								  "paymentMethod": "EFECTIVO",
								  "items": [ { "productId": %d, "quantity": 1 } ]
								}
								""".formatted(seedProduct())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("El local está cerrado"));
	}

	@Test
	void orderIsAcceptedOnAnExactExceptionWithShifts() throws Exception {
		// Lunes cerrado en el template (sin filas del día 1), pero la fecha
		// exacta de hoy abre con turno 00:00–12:00: la excepción manda.
		seedShift(2, 0, OPEN_ALL_MORNING, CLOSE_NOON);
		seedShift(2, 1, CLOSE_NOON, CLOSE_MIDNIGHT);
		seedExactException(2026, 10, 19, false, LocalTime.of(0, 0), LocalTime.of(12, 0));

		postOrder();
	}

	// ------------------------------------------------------------------
	// La madrugada de hoy la cubre el turno de AYER que cruzó la medianoche
	// ------------------------------------------------------------------

	@Test
	void orderIsAcceptedThroughYesterdayMidnightSpillover() throws Exception {
		// Domingo (día 7) con turno 19:00–03:00 que cruza la medianoche; el
		// lunes no tiene filas. A las 02:00 del lunes está abierto por el
		// derrame de ayer — exactamente el caso de openStatus.ts.
		seedShift(7, 0, LocalTime.of(19, 0), LocalTime.of(3, 0));

		postOrder();
	}
}
