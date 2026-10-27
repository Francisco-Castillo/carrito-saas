package com.carrito.saas.service.impl;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Pattern;
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
import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.entity.BusinessDateException;
import com.carrito.saas.repository.entity.BusinessHours;
import com.carrito.saas.repository.jpa.BranchRepository;
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
 * negocio ({@code No se pueden cargar más de 100 fechas especiales}). Con la
 * bandera {@code yearly} la excepción se repite todos los años en ese día y
 * mes (Navidad es 25/12 todos los años): el año de la fecha guardada es sólo
 * la referencia para la validación de derrame. Dos anuales con el mismo día y
 * mes son un duplicado aunque los años difieran ({@code Ya hay una excepción
 * anual para ese día y mes}); una anual y una one-off que compartan día/mes
 * están permitidas y la exacta gana su año (precedencia).
 * Actualización parcial: {@code dateExceptions} ausente del payload conserva
 * las fechas ya guardadas (un campo que no viaja no borra datos); la lista
 * vacía es el "ninguna" explícito y reemplaza en bloque. El derrame de una
 * fecha se valida contra las horas efectivas, resueltas una sola vez: las del
 * payload si {@code hours} viaja, las almacenadas si no. Un feriado
 * cerrado se guarda como una fila marcadora sin horarios, porque acá la
 * ausencia de la fecha significa "día normal" y el cerrado tiene que estar
 * representado.</p>
 *
 * <p>La lista de horas, si viaja, se reemplaza en bloque: un día ausente del
 * payload — o presente con la lista de turnos vacía— queda cerrado
 * (delete-then-insert dentro de la misma transacción); si no viaja, los
 * turnos almacenados quedan intactos. El servidor ordena los turnos de cada día
 * por hora de apertura, así que el cliente puede mandarlos en cualquier
 * orden; {@code position} es el índice después de ordenar y no se expone.
 * La respuesta devuelve siempre los siete días, con la lista de turnos vacía
 * en los cerrados. Los campos de texto siguen la actualización parcial: un
 * campo ausente conserva lo guardado; vacío (blancos) lo limpia (null en la
 * base); presente se valida como siempre. {@code name} es la excepción por
 * derecha: vacío se rechaza con 400 (un negocio necesita nombre) y ausente
 * conserva el guardado.</p>
 *
 * <p>Multi-sucursal (odd/tasks/multi-sucursal.md, S3/T4): las horas y las
 * fechas especiales se leen/escriben scopeadas a la sucursal activa del JWT
 * ({@code getCurrentBranchId}: claim, fallback a la única activa, null si el
 * negocio no tiene y 400 si hay varias sin elegir). El reemplazo en bloque
 * borra SÓLO las filas de esa sucursal; con sucursal null (negocio sin
 * sucursales) el comportamiento es el legacy a nivel negocio.</p>
 */
@Service
public class BusinessProfileServiceImpl implements IBusinessProfileService {

	private static final int MAX_URL_LENGTH = 500;
	/** Color de marca: exactamente #RRGGBB (6 dígitos hexadecimales). */
	private static final java.util.regex.Pattern HEX_COLOR = java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");
	/**
	 * Lista blanca de fuentes curadas para la carta (spec S2 de
	 * odd/tasks/personalizacion-carta.md): slugs de Google Fonts elegidos para
	 * menú — sin serif por defecto, serif elegante, slab y redondeada. Cualquier
	 * slug fuera de esta lista es 400: el dueño no puede inyectar una fuente
	 * arbitraria. Mantener en sync con MENU_FONT_OPTIONS del frontend
	 * (frontend/src/pages/admin/menuFonts.ts).
	 */
	private static final java.util.Set<String> MENU_FONTS = java.util.Set.of(
			"inter", "poppins", "montserrat", "playfair-display",
			"lora", "roboto-slab", "dm-serif-display", "nunito");
	private static final int MAX_ADDRESS_LENGTH = 300;
	/** Máximo de turnos por día: holgado para cualquier local real. */
	private static final int MAX_SHIFTS_PER_DAY = 4;
	/** Máximo de fechas especiales por negocio: el perfil viaja entero en cada request. */
	private static final int MAX_DATE_EXCEPTIONS = 100;
	private static final int MINUTES_PER_DAY = 24 * 60;

	private final BusinessRepository businessRepository;
	private final BranchRepository branchRepository;
	private final BusinessHoursRepository businessHoursRepository;
	private final BusinessDateExceptionRepository businessDateExceptionRepository;
	private final ISecurityService securityService;
	private final ImageStorageService imageStorageService;

	public BusinessProfileServiceImpl(BusinessRepository businessRepository, BranchRepository branchRepository,
			BusinessHoursRepository businessHoursRepository,
			BusinessDateExceptionRepository businessDateExceptionRepository, ISecurityService securityService,
			ImageStorageService imageStorageService) {
		this.businessRepository = businessRepository;
		this.branchRepository = branchRepository;
		this.businessHoursRepository = businessHoursRepository;
		this.businessDateExceptionRepository = businessDateExceptionRepository;
		this.securityService = securityService;
		this.imageStorageService = imageStorageService;
	}

	@Override
	@Transactional
	public BusinessProfileDTO updateLogoImage(byte[] data, String contentType) {
		return replaceBusinessImage(data, contentType, true);
	}

	@Override
	@Transactional
	public BusinessProfileDTO clearLogoImage() {
		return clearBusinessImage(true);
	}

	@Override
	@Transactional
	public BusinessProfileDTO updateBannerImage(byte[] data, String contentType) {
		return replaceBusinessImage(data, contentType, false);
	}

	@Override
	@Transactional
	public BusinessProfileDTO clearBannerImage() {
		return clearBusinessImage(false);
	}

	/**
	 * Reemplazo de imagen propia del negocio (logo o banner), mismo orden que
	 * el de productos: guardar la nueva primero (un fallo en disco no toca la
	 * fila) y recién después borrar la vieja SI era del storage propio — una
	 * URL externa pegada a mano nunca se borra del disco ajeno.
	 */
	private BusinessProfileDTO replaceBusinessImage(byte[] data, String contentType, boolean logo) {

		Business business = currentBusiness();

		String oldUrl = logo ? business.getLogoUrl() : business.getBannerUrl();
		String newUrl = imageStorageService.storeBusinessImage(data, contentType);
		setImage(business, logo, newUrl);

		if (oldUrl != null && !oldUrl.equals(newUrl)) {
			imageStorageService.deleteByUrl(oldUrl);
		}

		return toProfileDTO(businessRepository.save(business));
	}

	/** Quita la imagen propia (logo o banner); si no había, no toca nada. */
	private BusinessProfileDTO clearBusinessImage(boolean logo) {

		Business business = currentBusiness();

		String url = logo ? business.getLogoUrl() : business.getBannerUrl();
		setImage(business, logo, null);

		if (url != null) {
			imageStorageService.deleteByUrl(url);
		}

		return toProfileDTO(businessRepository.save(business));
	}

	private void setImage(Business business, boolean logo, String url) {
		if (logo) {
			business.setLogoUrl(url);
		} else {
			business.setBannerUrl(url);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public BusinessProfileDTO getProfile() {

		Business business = currentBusiness();

		return toProfileDTO(business);
	}

	/** Mapeo entidad → DTO del perfil: el único lugar que conoce los campos del storefront. */
	private BusinessProfileDTO toProfileDTO(Business business) {
		// Horas y fechas especiales scopeadas a la sucursal activa del JWT
		// (multi-sucursal, S3/T4): con sucursal seleccionada, la vista de esa
		// sucursal; negocio sin sucursales, la vista a nivel negocio
		// (compatibilidad). Varias sin seleccionar: la LECTURA cae a la default
		// (getCurrentBranchIdOrDefault, fix panel roto — un dueño con varias
		// sucursales y token sin claim no puede quedarse sin panel); la
		// ESCRITURA sigue exigiendo selección explícita (updateProfile usa
		// getCurrentBranchId, que resuelve el 400).
		Long branchId = securityService.getCurrentBranchIdOrDefault();
		BusinessProfileDTO dto = new BusinessProfileDTO();
		dto.setName(business.getName());
		dto.setPhone(business.getPhone());
		dto.setLogoUrl(business.getLogoUrl());
		dto.setBannerUrl(business.getBannerUrl());
		dto.setAddress(business.getAddress());
		dto.setInstagramUrl(business.getInstagramUrl());
		dto.setTiktokUrl(business.getTiktokUrl());
		dto.setPrimaryColor(business.getPrimaryColor());
		dto.setMenuFont(business.getMenuFont());
		dto.setComandaPrintingEnabled(Boolean.TRUE.equals(business.getComandaPrintingEnabled()));
		dto.setComandaAutoPrint(Boolean.TRUE.equals(business.getComandaAutoPrint()));
		dto.setHours(toHoursDTOs(business.getId(), branchId));
		dto.setDateExceptions(toDateExceptionDTOs(business.getId(), branchId));

		return dto;
	}

	@Override
	@Transactional
	public BusinessProfileDTO updateProfile(BusinessProfileDTO dto) {

		if (dto == null) {
			throw new BusinessException("El perfil no puede estar vacío", ErrorType.VALIDATION);
		}

		Business business = currentBusiness();

		// Horas/fechas por sucursal (multi-sucursal, S3/T4): la escritura va
		// scopeada a la sucursal activa del JWT — claim, fallback a la única
		// activa, null si no hay sucursales y 400 si hay varias sin elegir.
		Long branchId = securityService.getCurrentBranchId();
		Branch branch = resolveProfileBranch(business, branchId);

		validateName(dto.getName());
		validatePhone(dto.getPhone());
		validateUrl("logoUrl", dto.getLogoUrl(), MAX_URL_LENGTH);
		validateUrl("bannerUrl", dto.getBannerUrl(), MAX_URL_LENGTH);
		validateUrl("instagramUrl", dto.getInstagramUrl(), MAX_URL_LENGTH);
		validateUrl("tiktokUrl", dto.getTiktokUrl(), MAX_URL_LENGTH);
		validateAddress(dto.getAddress());
		validatePrimaryColor(dto.getPrimaryColor());
		validateMenuFont(dto.getMenuFont());

		// Horas efectivas, resueltas UNA sola vez: las del payload cuando
		// viaja, las almacenadas cuando no. La validación de derrame de las
		// fechas especiales se hace contra estas, nunca contra una lista vacía
		// — si no, un PUT parcial de sólo fechas saltaría el chequeo o
		// falso-rechazaría un feriado que en realidad está bien.
		List<BusinessHoursDTO> hoursToStore = null;
		List<BusinessHoursDTO> effectiveHours;
		if (dto.getHours() != null) {
			hoursToStore = validateHours(dto.getHours());
			effectiveHours = hoursToStore;
		} else {
			effectiveHours = toHoursDTOs(business.getId(), branchId);
		}

		// null significa "no tocar": la lista vacía sigue siendo el "ninguna"
		// explícito que reemplaza en bloque.
		List<DateExceptionDTO> exceptionsToStore = dto.getDateExceptions() == null ? null
				: validateDateExceptions(dto.getDateExceptions(), toOpenDaysMap(effectiveHours));

		// Actualización parcial de los campos de texto: ausente conserva lo
		// guardado, "" (blancos) limpia (normalize → null), presente se guarda
		// ya validado. El nombre blank es 400 (validateName): un negocio
		// necesita nombre.
		if (dto.getName() != null) {
			business.setName(dto.getName().trim());
		}
		if (dto.getPhone() != null) {
			// Teléfono/WhatsApp: mismo contrato parcial que los demás textos —
			// ausente conserva lo guardado, vacío limpia (null en la base) y
			// presente ya vino validado (validatePhone).
			business.setPhone(normalize(dto.getPhone()));
		}
		if (dto.getLogoUrl() != null) {
			business.setLogoUrl(normalize(dto.getLogoUrl()));
		}
		if (dto.getBannerUrl() != null) {
			business.setBannerUrl(normalize(dto.getBannerUrl()));
		}
		if (dto.getAddress() != null) {
			business.setAddress(normalize(dto.getAddress()));
		}
		if (dto.getInstagramUrl() != null) {
			business.setInstagramUrl(normalize(dto.getInstagramUrl()));
		}
		if (dto.getTiktokUrl() != null) {
			business.setTiktokUrl(normalize(dto.getTiktokUrl()));
		}
		if (dto.getPrimaryColor() != null) {
			// Mismo contrato parcial que los demás textos: ausente conserva lo
			// guardado, vacío limpia (null en la base), presente ya vino validado.
			business.setPrimaryColor(normalize(dto.getPrimaryColor()));
		}
		if (dto.getMenuFont() != null) {
			// Mismo contrato parcial: ausente conserva, vacío limpia (la carta
			// vuelve a Inter), presente ya vino validado contra la whitelist.
			// normalize("" ) devuelve null — el caso "limpiar" NO pasa por
			// toLowerCase (NPE: el blank-clear llegaba al cliente como 500).
			String normalized = normalize(dto.getMenuFont());
			business.setMenuFont(normalized == null ? null : normalized.toLowerCase());
		}
		if (dto.getComandaPrintingEnabled() != null) {
			// Flags booleanos de comandas: sin normalización — ausente conserva
			// lo guardado, true/false setea explícitamente (false es la única
			// forma de apagar; la columna es NOT NULL, no existe el "limpiar").
			business.setComandaPrintingEnabled(dto.getComandaPrintingEnabled());
		}
		if (dto.getComandaAutoPrint() != null) {
			business.setComandaAutoPrint(dto.getComandaAutoPrint());
		}
		businessRepository.save(business);

		if (hoursToStore != null) {
			// Reemplazo en bloque scopeado a la sucursal (multi-sucursal, S3/T4):
			// se borran SÓLO los turnos de esa sucursal — los legacy a nivel
			// negocio quedan, los conservan las demás sucursales — y se insertan
			// los del payload con la Branch seteada (branchId null = negocio sin
			// sucursales: se borra/inserta a nivel negocio, comportamiento
			// legacy). Un día ausente — o presente sin turnos— queda cerrado;
			// position es el índice dentro del día ya ordenado.
			businessHoursRepository.deleteByBusinessIdAndOptionalBranch(business.getId(), branchId);
			businessHoursRepository.flush();

		for (BusinessHoursDTO hoursDTO : hoursToStore) {
			List<BusinessShiftDTO> shifts = hoursDTO.getShifts();
			for (int position = 0; position < shifts.size(); position++) {
				BusinessHours entity = new BusinessHours();
				entity.setBusiness(business);
				entity.setBranch(branch);
				entity.setDayOfWeek(hoursDTO.getDayOfWeek());
				entity.setPosition(position);
				entity.setOpensAt(shifts.get(position).getOpensAt());
				entity.setClosesAt(shifts.get(position).getClosesAt());
				businessHoursRepository.save(entity);
			}
		}
		}

		if (exceptionsToStore != null) {
			// Reemplazo en bloque de las fechas especiales, igual que las horas
			// (scopeado a la sucursal del JWT, multi-sucursal S3/T4): la lista
			// vacía explícita vuelve a "sin fechas". Una fecha con la lista de
			// turnos vacía (feriado cerrado) se guarda como UNA fila marcadora
			// sin horarios: acá la ausencia de la fecha significa "día normal",
			// así que el cerrado tiene que estar representado.
			businessDateExceptionRepository.deleteByBusinessIdAndOptionalBranch(business.getId(), branchId);
			businessDateExceptionRepository.flush();

			for (DateExceptionDTO exceptionDTO : exceptionsToStore) {
			if (exceptionDTO.getShifts().isEmpty()) {
				BusinessDateException marker = new BusinessDateException();
				marker.setBusiness(business);
				marker.setBranch(branch);
				marker.setDate(exceptionDTO.getDate());
				marker.setYearly(exceptionDTO.isYearly());
				marker.setPosition(0);
				businessDateExceptionRepository.save(marker);
				continue;
			}
			for (int position = 0; position < exceptionDTO.getShifts().size(); position++) {
				BusinessDateException entity = new BusinessDateException();
				entity.setBusiness(business);
				entity.setBranch(branch);
				entity.setDate(exceptionDTO.getDate());
				entity.setYearly(exceptionDTO.isYearly());
				entity.setPosition(position);
				entity.setOpensAt(exceptionDTO.getShifts().get(position).getOpensAt());
				entity.setClosesAt(exceptionDTO.getShifts().get(position).getClosesAt());
				businessDateExceptionRepository.save(entity);
			}
		}
		}

		BusinessProfileDTO result = new BusinessProfileDTO();
		result.setName(business.getName());
		result.setPhone(business.getPhone());
		result.setLogoUrl(business.getLogoUrl());
		result.setBannerUrl(business.getBannerUrl());
		result.setAddress(business.getAddress());
		result.setInstagramUrl(business.getInstagramUrl());
		result.setTiktokUrl(business.getTiktokUrl());
		result.setPrimaryColor(business.getPrimaryColor());
		result.setMenuFont(business.getMenuFont());
		// Mismo eco que toProfileDTO: la respuesta del PUT siempre lleva los
		// flags ya guardados (el test del contrato los lee acá).
		result.setComandaPrintingEnabled(Boolean.TRUE.equals(business.getComandaPrintingEnabled()));
		result.setComandaAutoPrint(Boolean.TRUE.equals(business.getComandaAutoPrint()));
		result.setHours(toHoursDTOs(business.getId(), branchId));
		result.setDateExceptions(toDateExceptionDTOs(business.getId(), branchId));

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
	/**
	 * El color de marca es opcional: null (ausente) no valida nada — es "no
	 * tocar" en la actualización parcial; vacío limpia y se acepta; presente
	 * tiene que ser exactamente {@code #RRGGBB} (6 dígitos hexadecimales),
	 * cualquier otra cosa es 400 con mensaje en español y NADA se guarda
	 * (la validación corre antes de cualquier mutación).
	 */
	private void validatePrimaryColor(String value) {

		if (value == null || value.isBlank()) {
			return;
		}

		if (!HEX_COLOR.matcher(value.trim()).matches()) {
			throw new BusinessException("El color debe ser un código hexadecimal (#RRGGBB)",
					ErrorType.VALIDATION);
		}
	}

	/**
	 * La fuente de la carta es opcional: null (ausente) no valida nada — es
	 * "no tocar" en la actualización parcial; vacío limpia y se acepta; presente
	 * tiene que ser un slug de la lista blanca (normalizado a minúsculas antes
	 * de comparar, porque el picker del frontend puede mandar mayúsculas);
	 * cualquier otra cosa es 400 con mensaje en español y NADA se guarda (la
	 * validación corre antes de cualquier mutación).
	 */
	private void validateMenuFont(String value) {

		if (value == null || value.isBlank()) {
			return;
		}

		String slug = value.trim().toLowerCase();
		if (!MENU_FONTS.contains(slug)) {
			throw new BusinessException("La fuente elegida no está disponible", ErrorType.VALIDATION);
		}
	}

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

	/** Espeja el check businesses_phone_shape: dígitos, 8-14, sin 0 ni 15 inicial. */
	private static final Pattern PHONE_SHAPE = Pattern.compile("^[1-9][0-9]{7,13}$");

	/**
	 * Teléfono del local (el WhatsApp que usa el fallback de la carta). Mismo
	 * criterio que el alta de plataforma: sólo dígitos en forma E.164 sin el
	 * '+', sin 0 ni 15 de prefijo local. null/blank es válido acá (blank limpia
	 * el campo en la aplicación parcial).
	 */
	private void validatePhone(String phone) {
		String trimmed = phone == null ? null : phone.trim();
		if (trimmed != null && !trimmed.isBlank() && !PHONE_SHAPE.matcher(trimmed).matches()) {
			throw new BusinessException("El teléfono debe ser código de país y número, sin 0 ni 15",
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
	 * {@code openDays} son los días de la semana con turnos de las horas
	 * <em>efectivas</em> (payload si viaja, almacenadas si no): es el fallback
	 * del chequeo de derrame cuando el día siguiente no tiene excepción propia.
	 */
	private List<DateExceptionDTO> validateDateExceptions(List<DateExceptionDTO> exceptions,
			Map<Integer, List<BusinessShiftDTO>> openDays) {

		if (exceptions == null) {
			// Ausente significa "no tocar" y se resuelve en updateProfile antes
			// de llegar acá; el guard es defensivo.
			return List.of();
		}

		if (exceptions.size() > MAX_DATE_EXCEPTIONS) {
			throw new BusinessException("No se pueden cargar más de 100 fechas especiales", ErrorType.VALIDATION);
		}

		Set<LocalDate> seenDates = new HashSet<>();
		// Las anuales se deduplican por día y mes (MonthDay), no por fecha:
		// dos anuales con años distintos significan lo mismo.
		Set<MonthDay> seenYearlyMonthDays = new HashSet<>();
		// Fecha → excepción ya validada (turnos ordenados; lista vacía = fecha
		// cerrada). TreeMap deja la respuesta ordenada por fecha.
		Map<LocalDate, DateExceptionDTO> effectiveByDate = new TreeMap<>();
		// Día/mes → turnos de la excepción anual: fallback del chequeo de
		// derrame cuando el día siguiente no tiene excepción en fecha exacta
		// pero sí una anual, que rige todos los años.
		Map<MonthDay, List<BusinessShiftDTO>> yearlyByMonthDay = new HashMap<>();

		for (DateExceptionDTO exceptionDTO : exceptions) {

			if (exceptionDTO == null) {
				throw new BusinessException("Las fechas especiales no pueden incluir elementos vacíos",
						ErrorType.VALIDATION);
			}

			if (exceptionDTO.getDate() == null) {
				throw new BusinessException("date es obligatoria en cada fecha especial", ErrorType.VALIDATION);
			}

			// Duplicados: dos one-off con la misma fecha exacta chocan; dos
			// anuales con el mismo día y mes también, aunque los años difieran.
			// Una anual y una one-off que compartan día/mes están permitidas:
			// la exacta gana su año (precedencia) y la anual rige el resto.
			MonthDay monthDay = MonthDay.from(exceptionDTO.getDate());
			if (exceptionDTO.isYearly()) {
				if (!seenYearlyMonthDays.add(monthDay)) {
					throw new BusinessException("Ya hay una excepción anual para ese día y mes",
							ErrorType.VALIDATION);
				}
			} else if (!seenDates.add(exceptionDTO.getDate())) {
				throw new BusinessException("Ya hay una excepción para esa fecha", ErrorType.VALIDATION);
			}

			List<BusinessShiftDTO> shifts = exceptionDTO.getShifts();

			if (shifts == null) {
				// La fecha fue nombrada en el payload, así que no se permite el
				// default silencioso: es el mismo guard que en un día.
				throw new BusinessException("shifts es obligatorio en cada fecha (una lista vacía significa cerrado)",
						ErrorType.VALIDATION);
			}

			DateExceptionDTO normalized = new DateExceptionDTO();
			normalized.setDate(exceptionDTO.getDate());
			normalized.setYearly(exceptionDTO.isYearly());
			normalized.setShifts(shifts.isEmpty() ? List.of() : validateAndSortShifts(shifts));
			effectiveByDate.put(exceptionDTO.getDate(), normalized);
			if (exceptionDTO.isYearly()) {
				yearlyByMonthDay.put(monthDay, normalized.getShifts());
			}
		}

		// Sin solape con el día siguiente, resuelto contra los turnos
		// efectivos del día siguiente: su propia excepción si la tiene
		// (incluida la cerrada, que no abre nunca y no puede chocar), si no
		// la anual que caiga ese día y mes (rige todos los años; la fecha
		// exacta ya ganó el primer lookup), si no el día de la semana del
		// payload. Es la regla que evita que una excepción abra un agujero
		// en el horario normal. La fecha es siempre la concreta guardada —
		// también para una anual, cuyo año es sólo referencia: plusDays(1)
		// maneja solo los bordes (31/12 cae en el año siguiente; el día
		// siguiente a un 29/02 bisiesto es el 01/03, y en los años no
		// bisiestos la anual del 29/02 simplemente no ocurre, así que no hay
		// derrame que validar).
		for (Map.Entry<LocalDate, DateExceptionDTO> entry : effectiveByDate.entrySet()) {
			List<BusinessShiftDTO> dayShifts = entry.getValue().getShifts();
			if (dayShifts.isEmpty()) {
				continue;
			}
			int spill = endMinute(dayShifts.get(dayShifts.size() - 1)) - MINUTES_PER_DAY;
			if (spill <= 0) {
				continue;
			}
			LocalDate next = entry.getKey().plusDays(1);
			List<BusinessShiftDTO> nextShifts = effectiveByDate.containsKey(next)
					? effectiveByDate.get(next).getShifts()
					: yearlyByMonthDay.get(MonthDay.from(next));
			if (nextShifts == null) {
				nextShifts = openDays.get(next.getDayOfWeek().getValue());
			}
			if (nextShifts != null && !nextShifts.isEmpty() && startMinute(nextShifts.get(0)) < spill) {
				throw new BusinessException("El turno que pasa la medianoche se superpone con la apertura del día siguiente",
						ErrorType.VALIDATION);
			}
		}

		List<DateExceptionDTO> normalized = new ArrayList<>(effectiveByDate.size());
		for (Map.Entry<LocalDate, DateExceptionDTO> entry : effectiveByDate.entrySet()) {
			normalized.add(entry.getValue());
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
	 * Resuelve la Branch de la escritura del perfil (multi-sucursal, S3/T4):
	 * branchId null (negocio sin sucursales) → null, filas legacy a nivel
	 * negocio; con branchId la sucursal tiene que existir Y ser del negocio
	 * del JWT (el claim viene firmado, pero el id resuelto por fallback
	 * puede quedar obsoleto si la sucursal se dio de baja entre emisión y
	 * llamada — 404, mismo mensaje que el resto de la superficie de
	 * sucursales).
	 */
	private Branch resolveProfileBranch(Business business, Long branchId) {

		if (branchId == null) {
			return null;
		}
		return branchRepository.findByIdAndBusinessId(branchId, business.getId())
				.orElseThrow(() -> new BusinessException("Sucursal no encontrada", ErrorType.NOT_FOUND));
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

	private List<BusinessHoursDTO> toHoursDTOs(Long businessId, Long branchId) {

		return toSevenDayDTOs(businessHoursRepository
				.findByBusinessIdAndOptionalBranch(businessId, branchId));
	}

	/** Las fechas especiales con al menos una fila, ordenadas por fecha, con la lista vacía en las cerradas. */
	private List<DateExceptionDTO> toDateExceptionDTOs(Long businessId, Long branchId) {

		return toDateExceptionDTOList(businessDateExceptionRepository
				.findByBusinessIdAndOptionalBranch(businessId, branchId));
	}

	private static List<DateExceptionDTO> toDateExceptionDTOList(List<BusinessDateException> rows) {

		// Las filas vienen por (date, position): el LinkedHashMap conserva el
		// orden de fechas. La fila marcadora (opensAt NULL) deja la lista vacía.
		// Todas las filas de una fecha comparten la bandera yearly (se guardan
		// juntas desde el mismo DTO), así que la primera fija el valor.
		Map<LocalDate, DateExceptionDTO> byDate = new LinkedHashMap<>();
		for (BusinessDateException row : rows) {
			DateExceptionDTO dto = byDate.computeIfAbsent(row.getDate(), date -> {
				DateExceptionDTO created = new DateExceptionDTO();
				created.setDate(date);
				created.setYearly(row.isYearly());
				created.setShifts(new ArrayList<>());
				return created;
			});
			if (row.getOpensAt() == null) {
				continue;
			}
			BusinessShiftDTO shift = new BusinessShiftDTO();
			shift.setOpensAt(row.getOpensAt());
			shift.setClosesAt(row.getClosesAt());
			dto.getShifts().add(shift);
		}
		return new ArrayList<>(byDate.values());
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
