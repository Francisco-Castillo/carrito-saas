package com.carrito.saas.service.interfaces;

import java.time.LocalDateTime;

/**
 * Estado de apertura de un negocio — o de una sucursal suya (multi-sucursal,
 * odd/tasks/multi-sucursal.md, S3/T4) —, resuelto con las mismas reglas que la
 * portada del cliente aplica (frontend/src/pages/menu/openStatus.ts): turnos
 * ordenados por día, un día sin filas es cerrado, {@code closesAt < opensAt}
 * cruza la medianoche, excepciones por fecha (la exacta gana a la anual) y el
 * derrame de ayer para la madrugada de hoy.
 *
 * <p>El horario es una regla de negocio real, no decoración: la creación de
 * pedidos públicos la consulta antes de persistir, con la sucursal del
 * pedido.</p>
 */
public interface IBusinessOpenService {

	/**
	 * @param businessId negocio a evaluar
	 * @param branchId   sucursal a evaluar (multi-sucursal, S3/T4): con valor,
	 *                   rigen los turnos/excepciones de esa sucursal más los
	 *                   legacy a nivel negocio (branch NULL, heredados); NULL = negocio
	 *                   sin sucursales — vista a nivel negocio, compatibilidad
	 * @param now        instante a evaluar (server-local, la convención "today"
	 *                   del código); el caller aporta el reloj para que los
	 *                   tests fijen el tiempo
	 * @return true si el local está abierto en ese instante
	 */
	boolean isOpen(Long businessId, Long branchId, LocalDateTime now);
}
