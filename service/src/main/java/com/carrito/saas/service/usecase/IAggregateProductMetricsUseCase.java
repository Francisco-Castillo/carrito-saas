package com.carrito.saas.service.usecase;


/**
 * Caso de uso batch encargado de agregar las ventas por día de cada producto
 * a {@code product_daily_sales}.
 *
 * <p>Este caso de uso es utilizado por procesos automáticos (scheduler) y por
 * la cadena de analítica: {@code product_trends} se calcula a partir de esta
 * tabla.</p>
 */
public interface IAggregateProductMetricsUseCase {

	/**
     * Ejecuta la agregación de ventas diarias para la ventana configurada.
     * Es idempotente: re-ejecutarla no duplica ni infla filas.
     */
	void execute();

}
