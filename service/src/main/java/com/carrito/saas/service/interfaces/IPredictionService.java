package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.HourlyDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.dto.TodayPredictionDTO;

public interface IPredictionService {

	/**
     * Obtiene la predicción del día para el negocio del usuario autenticado.
     *
     * <p>Este método es seguro para APIs multi-tenant ya que el businessId
     * se obtiene del contexto de seguridad y no del cliente.</p>
     *
     * @return DTO con la predicción del día
     */
	TodayPredictionDTO getToday();
	
	/**
     * Obtiene la predicción del día para un negocio específico.
     *
     * <p>Este método está destinado exclusivamente a procesos internos como jobs batch.
     * No debe ser expuesto directamente en endpoints públicos.</p>
     *
     * @param businessId identificador del negocio
     * @return DTO con la predicción del día
     */
	TodayPredictionDTO getTodayForBusiness(Long businessId);

	List<HourlyDTO> getHourly();
	
	List<HourlyDTO> getHourlyForBusiness(Long businessId);

	List<ProductDTO> getProducts();
	
	List<ProductDTO> getProductsForBusiness(Long businessId);

}
