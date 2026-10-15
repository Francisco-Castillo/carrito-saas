package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.ComboCreateDTO;
import com.carrito.saas.dto.ComboDTO;

public interface IComboService {
	
	/**
	 * Crea un combo para el negocio del JWT. La categoría y todos los
	 * productos referenciados se resuelven con finders acotados al
	 * businessId del token: un id ajeno o inexistente lanza
	 * {@code BusinessException(NOT_FOUND)} → 404, y un request sin
	 * categoría o sin productos lanza {@code VALIDATION} → 400.
	 */
	ComboDTO crearCombo(ComboCreateDTO combo);

	/**
	 * Actualiza un combo del negocio del JWT: sobrescribe nombre (trimmeado),
	 * precio y categoría, y REEMPLAZA la lista de items. La validación de
	 * forma (nombre requerido, precio no negativo, categoría y productos
	 * presentes) corre ANTES de cualquier lookup o mutación; la categoría y
	 * todos los productos del payload se resuelven con finders acotados al
	 * businessId del token: un id ajeno o inexistente lanza
	 * {@code BusinessException(NOT_FOUND)} → 404, y un combo ajeno también es
	 * 404, nunca una actualización silenciosa.
	 */
	ComboDTO actualizarCombo(Long id, ComboCreateDTO combo);

	/**
	 * Listado de combos del negocio autenticado (anclado al businessId del
	 * JWT), con sus items cargados.
	 *
	 * @return listado de combos propios.
	 */
	List<ComboDTO> findAllByBusinessId();

}
