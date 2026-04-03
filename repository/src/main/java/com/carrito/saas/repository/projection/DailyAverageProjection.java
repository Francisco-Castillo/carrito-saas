package com.carrito.saas.repository.projection;

public interface DailyAverageProjection {
	/**
	 * Promedio de los ultimos dias.
	 */
	Double getAvgOrders();

}
