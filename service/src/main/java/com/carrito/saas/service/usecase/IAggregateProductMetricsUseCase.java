package com.carrito.saas.service.usecase;


/**
 * Caso de uso batch encargado de generar predicciones para todos los negocios.
 *
 * <p>Este caso de uso es utilizado por procesos automáticos (scheduler).</p>
 */
public interface IAggregateProductMetricsUseCase {
	
	/**
     * Ejecuta la generación de predicciones para todos los negocios registrados.
     */
	void execute();

}
