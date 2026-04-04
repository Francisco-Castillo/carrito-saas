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
	
	/**
	 * Obtiene el promedio histórico de pedidos diarios del negocio autenticado.
	 *
	 * <p>
	 * Calculado sobre los últimos 7 días a partir de datos agregados en
	 * {@code daily_metrics}. Este valor representa el comportamiento base
	 * del negocio y se utiliza para comparar contra predicciones actuales.
	 * </p>
	 *
	 * <p>
	 * <b>Nota:</b> si no existen datos históricos suficientes, se retorna 0.
	 * </p>
	 *
	 * @return promedio de pedidos diarios del negocio
	 */
	Double getAverageOrders();

}
