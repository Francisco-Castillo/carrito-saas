package com.carrito.saas.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.BusinessHoursDTO;
import com.carrito.saas.dto.BusinessProfileDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.BusinessHours;
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
 * {@code dayOfWeek} dentro de 1 (lunes) a 7 (domingo) sin duplicados; con
 * {@code closed} en falso {@code opensAt} y {@code closesAt} son obligatorios
 * y distintos. {@code closesAt} menor que {@code opensAt} significa que el
 * local cierra en la madrugada del día siguiente (cruza la medianoche: un
 * viernes 11:00 → 00:30 se guarda tal cual); {@code closesAt} mayor que
 * {@code opensAt} es el cierre del mismo día; la igualdad se rechaza porque
 * es ambigua (¿cero horas o 24?). Con {@code closed} en verdadero se limpian
 * los horarios.</p>
 *
 * <p>La lista de horas se reemplaza en bloque: un día ausente del payload se
 * borra (delete-then-insert dentro de la misma transacción), así el dueño
 * puede limpiar un día. Los campos de texto nulos o vacíos limpian el campo;
 * el nombre, si viene, debe ser no vacío (es editable).</p>
 */
@Service
public class BusinessProfileServiceImpl implements IBusinessProfileService {

	private static final int MAX_URL_LENGTH = 500;
	private static final int MAX_ADDRESS_LENGTH = 300;

	private final BusinessRepository businessRepository;
	private final BusinessHoursRepository businessHoursRepository;
	private final ISecurityService securityService;

	public BusinessProfileServiceImpl(BusinessRepository businessRepository,
			BusinessHoursRepository businessHoursRepository, ISecurityService securityService) {
		this.businessRepository = businessRepository;
		this.businessHoursRepository = businessHoursRepository;
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

		Business business = currentBusiness();

		if (dto.getName() != null) {
			business.setName(dto.getName().trim());
		}
		business.setLogoUrl(normalize(dto.getLogoUrl()));
		business.setBannerUrl(normalize(dto.getBannerUrl()));
		business.setAddress(normalize(dto.getAddress()));
		business.setInstagramUrl(normalize(dto.getInstagramUrl()));
		businessRepository.save(business);

		// Reemplazo en bloque: se borran las franjas actuales y se insertan
		// las del payload. Un día ausente queda borrado.
		businessHoursRepository.deleteByBusiness_Id(business.getId());
		businessHoursRepository.flush();

		for (BusinessHoursDTO hoursDTO : hours) {
			BusinessHours entity = new BusinessHours();
			entity.setBusiness(business);
			entity.setDayOfWeek(hoursDTO.getDayOfWeek());
			entity.setPosition(0);
			if (Boolean.TRUE.equals(hoursDTO.getClosed())) {
				entity.setClosed(true);
			} else {
				entity.setClosed(false);
				entity.setOpensAt(hoursDTO.getOpensAt());
				entity.setClosesAt(hoursDTO.getClosesAt());
			}
			businessHoursRepository.save(entity);
		}

		BusinessProfileDTO result = new BusinessProfileDTO();
		result.setName(business.getName());
		result.setLogoUrl(business.getLogoUrl());
		result.setBannerUrl(business.getBannerUrl());
		result.setAddress(business.getAddress());
		result.setInstagramUrl(business.getInstagramUrl());
		result.setHours(toHoursDTOs(business.getId()));

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

	/** Devuelve la lista validada (nunca null); {@code closed: true} limpia los horarios. */
	private List<BusinessHoursDTO> validateHours(List<BusinessHoursDTO> hours) {

		if (hours == null) {
			return List.of();
		}

		Set<Integer> seenDays = new HashSet<>();

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

			if (!Boolean.TRUE.equals(hoursDTO.getClosed())) {
				if (hoursDTO.getOpensAt() == null) {
					throw new BusinessException("opensAt es obligatorio cuando el día está abierto",
							ErrorType.VALIDATION);
				}
				if (hoursDTO.getClosesAt() == null) {
					throw new BusinessException("closesAt es obligatorio cuando el día está abierto",
							ErrorType.VALIDATION);
				}
				if (hoursDTO.getOpensAt().equals(hoursDTO.getClosesAt())) {
					throw new BusinessException("opensAt y closesAt no pueden ser iguales", ErrorType.VALIDATION);
				}
			}
		}

		return hours;
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

	private List<BusinessHoursDTO> toHoursDTOs(Long businessId) {

		return businessHoursRepository.findByBusiness_IdOrderByDayOfWeekAscPositionAsc(businessId)
				.stream()
				.map(BusinessProfileServiceImpl::toHoursDTO)
				.toList();
	}

	private static BusinessHoursDTO toHoursDTO(BusinessHours hours) {

		BusinessHoursDTO dto = new BusinessHoursDTO();
		dto.setDayOfWeek(hours.getDayOfWeek());
		dto.setOpensAt(hours.getOpensAt());
		dto.setClosesAt(hours.getClosesAt());
		dto.setClosed(hours.isClosed());
		return dto;
	}

}
