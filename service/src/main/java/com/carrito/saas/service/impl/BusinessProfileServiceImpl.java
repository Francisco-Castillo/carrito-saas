package com.carrito.saas.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.BusinessHoursDTO;
import com.carrito.saas.dto.BusinessProfileDTO;
import com.carrito.saas.dto.BusinessShiftDTO;
import com.carrito.saas.dto.DateExceptionDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessDateException;
import com.carrito.saas.repository.entity.BusinessHours;
import com.carrito.saas.repository.jpa.BusinessDateExceptionRepository;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.security.ISecurityService;
import com.carrito.saas.service.interfaces.IBusinessProfileService;

/**
 * Storefront profile: lectura y edición acotadas al negocio del JWT.
 *
 * <p>Reglas de validación (todas con mensajes en español y
 * {@link ErrorType#VALIDATION}, 400): las URLs deben ser absolutas
 * {@code http(s)://} o rutas relativas que empiecen con {@code /};
 * {@code dayOfWeek} dentro de 1 (lunes) a 7 (domingo) sin duplicados; cada
 * día abierto (con turnos) exige en cada turno {@code opensAt} y
 * {@code closesAt} presentes y distintos, máximo 4 turnos por día, sin
 * solapes dentro del día y sin solape con el derrame del turno nocturno del
 * día anterior; sólo el último turno del día puede cruzar la medianoche
 * ({@code closesAt} menor que {@code opensAt} significa que el local cierra
 * en la madrugada del día siguiente: un viernes 11:00 → 00:30 se guarda tal
 * cual). La igualdad se rechaza porque es ambigua (¿cero horas o 24?).
 * Un día sin turnos es un día cerrado.</p>
 *
 * <p>Fechas especiales (feriados y horarios especiales): {@code dateExceptions}
 * viaja en el mismo perfil y se reemplaza en bloque como las horas — una fecha
 * ausente del payload vuelve a ser un día normal. Cada fecha tiene la misma
 * forma que un día (turnos ordenados, lista vacía = cerrado) y cumple las
 * mismas reglas: hasta 4 turnos, sin solapes, sólo el último cruza la
 * medianoche. El derrame del último turno se compara contra los turnos
 * <em>efectivos</em> del día siguiente: su propia excepción si la tiene, si no
 * el día de la semana, así una excepción no abre un agujero en el horario
 * normal. Reglas propias de la lista: fecha obligatoria, sin duplicados
 * ({@code Ya hay una excepción para esa fecha}) y hasta 100 fechas por
 * negocio ({@code No se pueden cargar más de 100 fechas especiales}).
 * {@code dateExceptions} es obligatorio en el PUT (400 cuando falta): un
 * cliente que olvide el campo no puede borrar los feriados ya guardados en
 * silencio, igual que el guard de {@code shifts} en un día. Un feriado
 * cerrado se guarda como una fila marcadora sin horarios, porque acá la
 * ausencia de la fecha significa "día normal" y el cerrado tiene que estar
 * representado.</p>
 *
 * <p>La lista de horas se reemplaza en bloque: un día ausente del payload —
 * o presente con la lista de turnos vacía— queda cerrado (delete-then-insert
 * dentro de la misma transacción). El servidor ordena los turnos de cada día
 * por hora de apertura, así que el cliente puede mandarlos en cualquier
 * orden; {@code position} es el índice después de ordenar y no se expone.
 * La respuesta devuelve siempre los siete días, con la lista de turnos vacía
 * en los cerrados. Los campos de texto nulos o vacíos limpian el campo; el
 * nombre, si viene, debe ser no vacío (es editable).</p>
 */
@Service
public class BusinessProfileServiceImpl implements IBusinessProfileService {

	private static final int MAX_URL_LENGTH = 500;
	private static final int MAX_ADDRESS_LENGTH = 300;
	/** Máximo de turnos por día: holgado para cualquier local real. */
	private static final int MAX_SHIFTS_PER_DAY = 4;
	/** Máximo de fechas especiales por negocio: el perfil viaja entero en cada request. */
	private static final int MAX_DATE_EXCEPTIONS = 100;
	private static final int MINUTES_PER_DAY = 24 * 60;

	private final BusinessRepository businessRepository;
	private final BusinessHoursRepository businessHoursRepository;
	private final BusinessDateExceptionRepository businessDateExceptionRepository;
	private final ISecurityService securityService;

	public BusinessProfileServiceImpl(BusinessRepository businessRepository,
			BusinessHoursRepository businessHoursRepository,
			BusinessDateExceptionRepository businessDateExceptionRepository, ISecurityService securityService) {
		this.businessRepository = businessRepository;
		this.businessHoursRepository = businessHoursRepository;
		this.businessDateExceptionRepository = businessDateExceptionRepository;
		this.securityService = securityService;
	}

	@Override
	@Transactional(readOnly = true)
	public BusinessProfileDTO getProfile() {

		Business business = currentBusiness();

		BusinessProfileDTO dto = new BusinessProfileDTO();
		dto.setName(business.getName());
		dto.setLogoUrl(business.getLogoUrl());
		dto.setBannerUrl(business.getBannerUrl());
		dto.setAddress(business.getAddress());
		dto.setInstagramUrl(business.getInstagramUrl());
		dto.setHours(toHoursDTOs(business.getId()));
		dto.setDateExceptions(toDateExceptionDTOs(business.getId()));

		return dto;
	}

	@Override
	@Transactional
	public BusinessProfileDTO updateProfile(BusinessProfileDTO dto) {

		if (dto == null) {
			throw new BusinessException("El perfil no puede estar vacío", ErrorType.VALIDATION);
		}

		validateName(dto.getName());
		validateUrl("logoUrl", dto.getLogoUrl(), MAX_URL_LENGTH);
		validateUrl("bannerUrl", dto.getBannerUrl(), MAX_URL_LENGTH);
		validateUrl("instagramUrl", dto.getInstagramUrl(), MAX_URL_LENGTH);
		validateAddress(dto.getAddress());
		List<BusinessHoursDTO> hours = validateHours(dto.getHours());
		List<DateExceptionDTO> dateExceptions = validateDateExceptions(dto.getDateExceptions(),
				toOpenDaysMap(hours));

		Business business = currentBusiness();

		if (dto.getName() != null) {
			business.setName(dto.getName().trim());
		}
		business.setLogoUrl(normalize(dto.getLogoUrl()));
		business.setBannerUrl(normalize(dto.getBannerUrl()));
		business.setAddress(normalize(dto.getAddress()));
		business.setInstagramUrl(normalize(dto.getInstagramUrl()));
		businessRepository.save(business);

		// Reemplazo en bloque: se borran los turnos actuales y se insertan
		// los del payload. Un día ausente — o presente sin turnos— queda
		// cerrado; position es el índice dentro del día ya ordenado.
		businessHoursRepository.deleteByBusiness_Id(business.getId());
		businessHoursRepository.flush();

		for (BusinessHoursDTO hoursDTO : hours) {
			List<BusinessShiftDTO> shifts = hoursDTO.getShifts();
			for (int position = 0; position < shifts.size(); position++) {
				BusinessHours entity = new BusinessHours();
				entity.setBusiness(business);
				entity.setDayOfWeek(hoursDTO.getDayOfWeek());
				entity.setPosition(position);
				entity.setOpensAt(shifts.get(position).getOpensAt());
				entity.setClosesAt(shifts.get(position).getClosesAt());
				businessHoursRepository.save(entity);
			}
		}

		// Reemplazo en bloque de las fechas especiales, igual que las horas:
		// una fecha ausente del payload vuelve a ser un día normal. Una fecha
		// con la lista de turnos vacía (feriado cerrado) se guarda como UNA fila
		// marcadora sin horarios: acá la ausencia de la fecha significa "día
		// normal", así que el cerrado tiene que estar representado.
		businessDateExceptionRepository.deleteByBusiness_Id(business.getId());
		businessDateExceptionRepository.flush();

		for (DateExceptionDTO exceptionDTO : dateExceptions) {
			if (exceptionDTO.getShifts().isEmpty()) {
				BusinessDateException marker = new BusinessDateException();
				marker.setBusiness(business);
				marker.setDate(exceptionDTO.getDate());
				marker.setPosition(0);
				businessDateExceptionRepository.save(marker);
				continue;
			}
			for (int position = 0; position < exceptionDTO.getShifts().size(); position++) {
				BusinessDateException entity = new BusinessDateException();
				entity.setBusiness(business);
				entity.setDate(exceptionDTO.getDate());
				entity.setPosition(position);
				entity.setOpensAt(exceptionDTO.getShifts().get(position).getOpensAt());
				entity.setClosesAt(exceptionDTO.getShifts().get(position).getClosesAt());
				businessDateExceptionRepository.save(entity);
			}
		}

		BusinessProfileDTO result = new BusinessProfileDTO();
		result.setName(business.getName());
		result.setLogoUrl(business.getLogoUrl());
		result.setBannerUrl(business.getBannerUrl());
		result.setAddress(business.getAddress());
		result.setInstagramUrl(business.getInstagramUrl());
		result.setHours(toHoursDTOs(business.getId()));
		result.setDateExceptions(toDateExceptionDTOs(business.getId()));

		return result;
	}

	// ------------------------------------------------------------------
	// Validación
	// ------------------------------------------------------------------

	private void validateName(String name) {
		if (name != null && name.isBlank()) {
			throw new BusinessException("El nombre no puede estar vacío", ErrorType.VALIDATION);
		}
	}

	/**
	 * URL absoluta {@code http(s)://} o ruta relativa que empieza con
	 * {@code /}; nulo o vacío significa "sin valor" y es válido.
	 */
	private void validateUrl(String field, String value, int maxLength) {

		if (value == null || value.isBlank()) {
			return;
		}

		String trimmed = value.trim();

		if (trimmed.length() > maxLength) {
			throw new BusinessException(field + " no puede superar los " + maxLength + " caracteres",
					ErrorType.VALIDATION);
		}

		boolean absoluteHttp = trimmed.toLowerCase().startsWith("http://")
				|| trimmed.toLowerCase().startsWith("https://");
		boolean relativePath = trimmed.startsWith("/");

		if (!absoluteHttp && !relativePath) {
			throw new BusinessException(
					field + " debe ser una URL absoluta http(s) o una ruta relativa que comience con /",
					ErrorType.VALIDATION);
		}
	}

	private void validateAddress(String address) {
		if (address != null && address.trim().length() > MAX_ADDRESS_LENGTH) {
			throw new BusinessException("address no puede superar los " + MAX_ADDRESS_LENGTH + " caracteres",
					ErrorType.VALIDATION);
		}
	}

	/**
	 * Valida los horarios y devuelve la lista normalizada (nunca null, con los
	 * turnos de cada día ordenados por apertura). {@code hours} nulo o vacío
	 * significa "todos los días cerrados" y es válido; un día con la lista de
	 * turnos vacía significa cerrado). Lo que sí se rechaza es un día nombrado
	 * en el payload sin la clave {@code shifts} (por ejemplo un cliente con la
	 * forma vieja del contrato): deserializaría a {@code null} y, tratado como
	 * lista vacía, borraría el horario en silencio.
	 */
	private List<BusinessHoursDTO> validateHours(List<BusinessHoursDTO> hours) {

		if (hours == null || hours.isEmpty()) {
			return List.of();
		}

		Set<Integer> seenDays = new HashSet<>();
		Map<Integer, List<BusinessShiftDTO>> openDays = new TreeMap<>();

		for (BusinessHoursDTO hoursDTO : hours) {

			if (hoursDTO == null) {
				throw new BusinessException("Las horas no pueden incluir elementos vacíos", ErrorType.VALIDATION);
			}

			Integer day = hoursDTO.getDayOfWeek();

			if (day == null || day < 1 || day > 7) {
				throw new BusinessException("dayOfWeek debe estar entre 1 (lunes) y 7 (domingo)",
						ErrorType.VALIDATION);
			}

			if (!seenDays.add(day)) {
				throw new BusinessException("dayOfWeek duplicado: " + day, ErrorType.VALIDATION);
			}

			List<BusinessShiftDTO> shifts = hoursDTO.getShifts();

			if (shifts == null) {
				// El día fue nombrado en el payload, así que no se permite el
				// default silencioso: una forma vieja o una clave olvidada no
				// pueden borrar el horario ya guardado.
				throw new BusinessException("shifts es obligatorio en cada día (una lista vacía significa cerrado)",
						ErrorType.VALIDATION);
			}

			if (shifts.isEmpty()) {
				continue; // día cerrado: no hay nada que validar
			}

			openDays.put(day, validateAndSortShifts(shifts));
		}

		// Sin solape con el derrame: si el último turno de un día termina pasado
		// el día y el día siguiente abre antes de que termine ese derrame, se
		// rechaza. Cíclico: el derrame del domingo se compara con el lunes.
		for (Map.Entry<Integer, List<BusinessShiftDTO>> entry : openDays.entrySet()) {
			List<BusinessShiftDTO> dayShifts = entry.getValue();
			int spill = endMinute(dayShifts.get(dayShifts.size() - 1)) - MINUTES_PER_DAY;
			if (spill <= 0) {
				continue;
			}
			List<BusinessShiftDTO> nextDayShifts = openDays.get(entry.getKey() % 7 + 1);
			if (nextDayShifts != null && startMinute(nextDayShifts.get(0)) < spill) {
				throw new BusinessException("El turno que pasa la medianoche se superpone con la apertura del día siguiente",
						ErrorType.VALIDATION);
			}
		}

		// Cada día con sus turnos ya ordenados; el guardado usa position = índice.
		List<BusinessHoursDTO> normalized = new ArrayList<>(hours.size());
		for (BusinessHoursDTO hoursDTO : hours) {
			BusinessHoursDTO dto = new BusinessHoursDTO();
			dto.setDayOfWeek(hoursDTO.getDayOfWeek());
			List<BusinessShiftDTO> sorted = openDays.get(hoursDTO.getDayOfWeek());
			dto.setShifts(sorted == null ? List.of() : sorted);
			normalized.add(dto);
		}
		return normalized;
	}

	/**
	 * Las reglas que comparten un día de la semana y una fecha especial, sobre
	 * la lista de turnos de un día no vacío: horarios presentes y distintos,
	 * máximo 4 turnos, sólo el último cruza la medianoche, sin solapes. Devuelve
	 * la lista ordenada por apertura (el cliente puede mandarla en cualquier
	 * orden); los mensajes son exactamente los de un día, porque es la misma
	 * validación — una fecha excepcional es, para todo lo demás, un día.
	 */
	private List<BusinessShiftDTO> validateAndSortShifts(List<BusinessShiftDTO> shifts) {

		for (BusinessShiftDTO shift : shifts) {
			if (shift == null || shift.getOpensAt() == null) {
				throw new BusinessException("opensAt es obligatorio en cada turno del día abierto",
						ErrorType.VALIDATION);
			}
			if (shift.getClosesAt() == null) {
				throw new BusinessException("closesAt es obligatorio en cada turno del día abierto",
						ErrorType.VALIDATION);
			}
		}

		// El cliente puede mandar los turnos en cualquier orden: se ordenan
		// por apertura antes de validar y de guardar.
		List<BusinessShiftDTO> sorted = new ArrayList<>(shifts);
		sorted.sort(Comparator.comparing(BusinessShiftDTO::getOpensAt));

		for (BusinessShiftDTO shift : sorted) {
			if (shift.getOpensAt().equals(shift.getClosesAt())) {
				throw new BusinessException("opensAt y closesAt no pueden ser iguales", ErrorType.VALIDATION);
			}
		}

		if (sorted.size() > MAX_SHIFTS_PER_DAY) {
			throw new BusinessException("Un día no puede tener más de 4 turnos", ErrorType.VALIDATION);
		}

		// Sólo el último turno del día puede pasar la medianoche. Se chequea
		// antes del solape genérico porque es el caso más específico: un turno
		// que cruza la medianoche termina "pasado el día", así que si hubiera
		// otro turno después siempre se superpondría con él.
		for (int i = 0; i < sorted.size() - 1; i++) {
			if (crossesMidnight(sorted.get(i))) {
				throw new BusinessException("Un turno que pasa la medianoche tiene que ser el último del día",
						ErrorType.VALIDATION);
			}
		}

		// Sin solapes dentro del día, comparando en minutos del día; un turno
		// que cruza la medianoche termina "pasado el día". Tocarse de punta
		// (15:00 cierra, 15:00 abre) no es solape.
		for (int i = 1; i < sorted.size(); i++) {
			if (endMinute(sorted.get(i - 1)) > startMinute(sorted.get(i))) {
				throw new BusinessException("Los turnos de un día no pueden superponerse", ErrorType.VALIDATION);
			}
		}

		return sorted;
	}

	/**
	 * Valida las fechas especiales y devuelve la lista normalizada, ordenada
	 * por fecha (TreeMap) y con los turnos de cada fecha ya ordenados.
	 * {@code openDays} son los días de la semana ya validados del mismo PUT:
	 * es el fallback del chequeo de derrame cuando el día siguiente no tiene
	 * excepción propia.
	 */
	private List<DateExceptionDTO> validateDateExceptions(List<DateExceptionDTO> exceptions,
			Map<Integer, List<BusinessShiftDTO>> openDays) {

		if (exceptions == null) {
			// El campo viaja en el perfil completo; que no viaje no puede
			// significar "sin fechas": borraría los feriados del dueño en
			// silencio, igual que un día nombrado sin la clave shifts.
			throw new BusinessException("dateExceptions es obligatorio (una lista vacía significa que no hay fechas especiales)",
					ErrorType.VALIDATION);
		}

		if (exceptions.size() > MAX_DATE_EXCEPTIONS) {
			throw new BusinessException("No se pueden cargar más de 100 fechas especiales", ErrorType.VALIDATION);
		}

		Set<LocalDate> seenDates = new HashSet<>();
		// Fecha → turnos ya validados (lista vacía = fecha cerrada). TreeMap
		// deja la respuesta ordenada por fecha.
		Map<LocalDate, List<BusinessShiftDTO>> effectiveByDate = new TreeMap<>();

		for (DateExceptionDTO exceptionDTO : exceptions) {

			if (exceptionDTO == null) {
				throw new BusinessException("Las fechas especiales no pueden incluir elementos vacíos",
						ErrorType.VALIDATION);
			}

			if (exceptionDTO.getDate() == null) {
				throw new BusinessException("date es obligatoria en cada fecha especial", ErrorType.VALIDATION);
			}

			if (!seenDates.add(exceptionDTO.getDate())) {
				throw new BusinessException("Ya hay una excepción para esa fecha", ErrorType.VALIDATION);
			}

			List<BusinessShiftDTO> shifts = exceptionDTO.getShifts();

			if (shifts == null) {
				// La fecha fue nombrada en el payload, así que no se permite el
				// default silencioso: es el mismo guard que en un día.
				throw new BusinessException("shifts es obligatorio en cada fecha (una lista vacía significa cerrado)",
						ErrorType.VALIDATION);
			}

			effectiveByDate.put(exceptionDTO.getDate(),
					shifts.isEmpty() ? List.of() : validateAndSortShifts(shifts));
		}

		// Sin solape con el día siguiente, resuelto contra los turnos
		// efectivos del día siguiente: su propia excepción si la tiene
		// (incluida la cerrada, que no abre nunca y no puede chocar), si no
		// el día de la semana del payload. Es la regla que evita que una
		// excepción abra un agujero en el horario normal.
		for (Map.Entry<LocalDate, List<BusinessShiftDTO>> entry : effectiveByDate.entrySet()) {
			List<BusinessShiftDTO> dayShifts = entry.getValue();
			if (dayShifts.isEmpty()) {
				continue;
			}
			int spill = endMinute(dayShifts.get(dayShifts.size() - 1)) - MINUTES_PER_DAY;
			if (spill <= 0) {
				continue;
			}
			LocalDate next = entry.getKey().plusDays(1);
			List<BusinessShiftDTO> nextShifts = effectiveByDate.containsKey(next)
					? effectiveByDate.get(next)
					: openDays.get(next.getDayOfWeek().getValue());
			if (nextShifts != null && !nextShifts.isEmpty() && startMinute(nextShifts.get(0)) < spill) {
				throw new BusinessException("El turno que pasa la medianoche se superpone con la apertura del día siguiente",
						ErrorType.VALIDATION);
			}
		}

		List<DateExceptionDTO> normalized = new ArrayList<>(effectiveByDate.size());
		for (Map.Entry<LocalDate, List<BusinessShiftDTO>> entry : effectiveByDate.entrySet()) {
			DateExceptionDTO dto = new DateExceptionDTO();
			dto.setDate(entry.getKey());
			dto.setShifts(entry.getValue());
			normalized.add(dto);
		}
		return normalized;
	}

	private static boolean crossesMidnight(BusinessShiftDTO shift) {
		return shift.getClosesAt().isBefore(shift.getOpensAt());
	}

	private static int startMinute(BusinessShiftDTO shift) {
		return shift.getOpensAt().getHour() * 60 + shift.getOpensAt().getMinute();
	}

	/** Minutos del día en que termina el turno; la medianoche cruzada suma un día. */
	private static int endMinute(BusinessShiftDTO shift) {
		int end = shift.getClosesAt().getHour() * 60 + shift.getClosesAt().getMinute();
		return crossesMidnight(shift) ? end + MINUTES_PER_DAY : end;
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Business currentBusiness() {

		Long businessId = securityService.getCurrentBusinessId();

		return businessRepository.findById(businessId)
				.orElseThrow(() -> new BusinessException("Negocio no encontrado: " + businessId, ErrorType.NOT_FOUND));
	}

	private String normalize(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	/**
	 * Los siete días (1..7, lunes→domingo), cada uno con su lista de turnos —
	 * vacía cuando el día está cerrado. Así ningún cliente tiene que inferir
	 * "el día que falta es un día cerrado" y una vuelta de lo que el cliente
	 * recibió conserva el mismo significado.
	 */
	/** Los días de la semana con turnos del payload ya validado (cerrados fuera), para el fallback del derrame. */
	private static Map<Integer, List<BusinessShiftDTO>> toOpenDaysMap(List<BusinessHoursDTO> normalizedHours) {

		Map<Integer, List<BusinessShiftDTO>> openDays = new HashMap<>();
		for (BusinessHoursDTO dto : normalizedHours) {
			if (!dto.getShifts().isEmpty()) {
				openDays.put(dto.getDayOfWeek(), dto.getShifts());
			}
		}
		return openDays;
	}

	private List<BusinessHoursDTO> toHoursDTOs(Long businessId) {

		return toSevenDayDTOs(businessHoursRepository
				.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(businessId));
	}

	/** Las fechas especiales con al menos una fila, ordenadas por fecha, con la lista vacía en las cerradas. */
	private List<DateExceptionDTO> toDateExceptionDTOs(Long businessId) {

		return toDateExceptionDTOList(businessDateExceptionRepository
				.findByBusiness_IdOrderByDateAscPositionAsc(businessId));
	}

	private static List<DateExceptionDTO> toDateExceptionDTOList(List<BusinessDateException> rows) {

		// Las filas vienen por (date, position): el LinkedHashMap conserva el
		// orden de fechas. La fila marcadora (opensAt NULL) deja la lista vacía.
		Map<LocalDate, List<BusinessShiftDTO>> shiftsByDate = new LinkedHashMap<>();
		for (BusinessDateException row : rows) {
			List<BusinessShiftDTO> shifts = shiftsByDate.computeIfAbsent(row.getDate(), date -> new ArrayList<>());
			if (row.getOpensAt() == null) {
				continue;
			}
			BusinessShiftDTO shift = new BusinessShiftDTO();
			shift.setOpensAt(row.getOpensAt());
			shift.setClosesAt(row.getClosesAt());
			shifts.add(shift);
		}

		List<DateExceptionDTO> dates = new ArrayList<>(shiftsByDate.size());
		for (Map.Entry<LocalDate, List<BusinessShiftDTO>> entry : shiftsByDate.entrySet()) {
			DateExceptionDTO dto = new DateExceptionDTO();
			dto.setDate(entry.getKey());
			dto.setShifts(entry.getValue());
			dates.add(dto);
		}
		return dates;
	}

	private static List<BusinessHoursDTO> toSevenDayDTOs(List<BusinessHours> rows) {

		Map<Integer, List<BusinessShiftDTO>> shiftsByDay = new HashMap<>();
		for (BusinessHours row : rows) {
			BusinessShiftDTO shift = new BusinessShiftDTO();
			shift.setOpensAt(row.getOpensAt());
			shift.setClosesAt(row.getClosesAt());
			shiftsByDay.computeIfAbsent(row.getDayOfWeek(), day -> new ArrayList<>()).add(shift);
		}

		List<BusinessHoursDTO> days = new ArrayList<>(7);
		for (int day = 1; day <= 7; day++) {
			BusinessHoursDTO dto = new BusinessHoursDTO();
			dto.setDayOfWeek(day);
			dto.setShifts(shiftsByDay.getOrDefault(day, List.of()));
			days.add(dto);
		}
		return days;
	}

}
