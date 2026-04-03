package com.carrito.saas.service.interfaces;

/**
 * Caso de uso encargado de generar predicciones para un único negocio.
 *
 * <p>Este caso de uso es utilizado tanto por procesos batch como por ejecuciones internas.</p>
 */
public interface IGeneratePredictionService {
	 /**
     * Ejecuta la generación de predicciones para un negocio específico.
     *
     * @param businessId identificador del negocio
     */
	 void execute(Long businessId);

}
