package com.carrito.saas.repository.jdbc;

import java.time.LocalDate;

public interface ProductMetricsRepository {

	/**
	 * Agrega las ventas de {@code orders}/{@code order_items} a
	 * {@code product_daily_sales} para los pedidos creados entre
	 * {@code startDate} (inclusive) y {@code endDate} (inclusive), por día
	 * natural. Idempotente: el upsert ON CONFLICT reemplaza los totales.
	 */
	void aggregateRange(LocalDate startDate, LocalDate endDate);

}
