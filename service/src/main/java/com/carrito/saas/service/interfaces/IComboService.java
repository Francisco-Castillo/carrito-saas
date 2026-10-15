package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.ComboCreateDTO;
import com.carrito.saas.dto.ComboDTO;

public interface IComboService {
	
	ComboDTO crearCombo(ComboCreateDTO combo);

	/**
	 * Listado de combos del negocio autenticado (anclado al businessId del
	 * JWT), con sus items cargados.
	 *
	 * @return listado de combos propios.
	 */
	List<ComboDTO> findAllByBusinessId();

}
