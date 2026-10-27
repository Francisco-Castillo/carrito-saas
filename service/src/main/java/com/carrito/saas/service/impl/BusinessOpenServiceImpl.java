package com.carrito.saas.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.carrito.saas.repository.entity.BusinessDateException;
import com.carrito.saas.repository.entity.BusinessHours;
import com.carrito.saas.repository.jpa.BusinessDateExceptionRepository;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.service.interfaces.IBusinessOpenService;

/**
 * Resolución del estado abierto/cerrado de un negocio — el espejo Java de
 * frontend/src/pages/menu/openStatus.ts (la única fuente de verdad de las
 * reglas, con su documentación completa ahí):
 *
 * <ul>
 * <li>Cada día lleva turnos ORDENADOS por {@code position}; un día sin filas
 * es cerrado.</li>
 * <li>Un turno cumple {@code opensAt <= minuto < closesAt} (apertura
 * inclusiva, cierre exclusivo); {@code closesAt < opensAt} cruza la
 * medianoche y por regla del backend sólo el ÚLTIMO turno del día puede
 * hacerlo — aquí se tolera cualquier turno cruzado, la validación de guardado
 * es la que garantiza la regla.</li>
 * <li>La madrugada de hoy la cubre el turno de AYER que cruzó la medianoche
 * ({@code minuto < closesAt} de ayer).</li>
 * <li>Las excepciones por fecha ({@code business_date_exception}) reemplazan
 * el template semanal: la fecha EXACTA gana a la ANUAL; la anual matchea por
 * mes/día en cualquier año; la fila marcadora (times NULL) es fecha
 * cerrada.</li>
 * </ul>
 *
 * <p>El instante entra por parámetro: el caller aporta su reloj (server-local,
 * la convención "today" del código) y los tests fijan el tiempo.</p>
 *
 * <p>Multi-sucursal (odd/tasks/multi-sucursal.md, S3/T4): la evaluación es por
 * sucursal. Con {@code branchId} las queries traen los turnos/excepciones de
 * ESA sucursal más los legacy a nivel negocio (branch NULL, heredados por las
 * sucursales que no definieron los suyos — incluida la excepción exacta de la
 * fecha, que sigue ganando al template). Con {@code branchId} NULL (negocio
 * sin sucursales) es la vista a nivel negocio entera, compatibilidad con el
 * comportamiento previo.</p>
 */
@Service
public class BusinessOpenServiceImpl implements IBusinessOpenService {

	private final BusinessHoursRepository businessHoursRepository;
	private final BusinessDateExceptionRepository businessDateExceptionRepository;

	public BusinessOpenServiceImpl(BusinessHoursRepository businessHoursRepository,
			BusinessDateExceptionRepository businessDateExceptionRepository) {
		this.businessHoursRepository = businessHoursRepository;
		this.businessDateExceptionRepository = businessDateExceptionRepository;
	}

	@Override
	public boolean isOpen(Long businessId, Long branchId, LocalDateTime now) {
		int minuteOfDay = now.getHour() * 60 + now.getMinute();
		LocalDate today = now.toLocalDate();
		LocalDate yesterday = today.minusDays(1);

		List<BusinessHours> weekly = businessHoursRepository
				.findByBusinessIdAndOptionalBranch(businessId, branchId);
		List<BusinessDateException> exceptions = businessDateExceptionRepository
				.findByBusinessIdAndOptionalBranch(businessId, branchId);

		if (covers(effectiveShifts(weekly, exceptions, today), minuteOfDay, false)) {
			return true;
		}
		// La madrugada de hoy puede estar abierta por el turno de AYER que
		// cruzó la medianoche — con las excepciones de AYER, no las de hoy.
		return covers(effectiveShifts(weekly, exceptions, yesterday), minuteOfDay, true);
	}

	/**
	 * Turnos efectivos de una fecha: la excepción exacta manda, luego la
	 * anual (mes/día), y si no hay ninguna, el template semanal del día ISO.
	 */
	private List<Shift> effectiveShifts(List<BusinessHours> weekly,
			List<BusinessDateException> exceptions, LocalDate date) {

		List<BusinessDateException> exact = exceptions.stream()
				.filter(e -> !e.isYearly() && date.equals(e.getDate()))
				.toList();
		if (!exact.isEmpty()) {
			return exact.stream().map(BusinessOpenServiceImpl::toShift).toList();
		}

		List<BusinessDateException> yearly = exceptions.stream()
				.filter(e -> e.isYearly()
						&& e.getDate().getMonth() == date.getMonth()
						&& e.getDate().getDayOfMonth() == date.getDayOfMonth())
				.toList();
		if (!yearly.isEmpty()) {
			return yearly.stream().map(BusinessOpenServiceImpl::toShift).toList();
		}

		int isoDay = date.getDayOfWeek().getValue();
		return weekly.stream()
				.filter(h -> h.getDayOfWeek() == isoDay)
				.map(BusinessOpenServiceImpl::toShift)
				.toList();
	}

	/**
	 * ¿Algún turno cubre el minuto? Con {@code spilloverOnly} sólo cuentan los
	 * turnos que cruzan la medianoche, y sólo su porción del día siguiente.
	 */
	private boolean covers(List<Shift> shifts, int minuteOfDay, boolean spilloverOnly) {
		for (Shift shift : shifts) {
			if (shift.opensAt() == null || shift.closesAt() == null) {
				continue; // fila marcadora de fecha cerrada: no aporta turnos
			}
			int opens = toMinutes(shift.opensAt());
			int closes = toMinutes(shift.closesAt());
			if (closes > opens) {
				// Turno del mismo día: apertura inclusiva, cierre exclusivo.
				if (!spilloverOnly && minuteOfDay >= opens && minuteOfDay < closes) {
					return true;
				}
			} else {
				// Turno que cruza la medianoche: hoy desde opens, mañana hasta closes.
				if (!spilloverOnly && minuteOfDay >= opens) {
					return true;
				}
				if (spilloverOnly && minuteOfDay < closes) {
					return true;
				}
			}
		}
		return false;
	}

	private static Shift toShift(BusinessHours row) {
		return new Shift(row.getOpensAt(), row.getClosesAt());
	}

	private static Shift toShift(BusinessDateException row) {
		return new Shift(row.getOpensAt(), row.getClosesAt());
	}

	private static int toMinutes(LocalTime time) {
		return time.getHour() * 60 + time.getMinute();
	}

	private record Shift(LocalTime opensAt, LocalTime closesAt) {
	}
}
