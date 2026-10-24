package com.carrito.saas.repository.jdbc;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProductMetricsRepositoryImpl implements ProductMetricsRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	@Override
	public void aggregateRange(LocalDate startDate, LocalDate endDate) {
		String sql = """
	            INSERT INTO product_daily_sales (business_id, product_id, date, quantity_sold, revenue)
	            SELECT
	                o.business_id,
	                oi.product_id,
	                DATE(o.created_at),
	                SUM(oi.quantity),
	                SUM(oi.subtotal)
	            FROM orders o
	            JOIN order_items oi ON oi.order_id = o.id
	            WHERE o.created_at >= :startDate
	              AND o.created_at < CAST(:endDate AS date) + INTERVAL '1 day'
	              -- Los roots de combo no tienen product_id (el root existe para
	              -- el ticket de cocina): meterlos en el GROUP BY genera un grupo
	              -- con NULL que la tabla rechaza (NOT NULL) y tumba el job
	              -- entero. El revenue del combo entra igual por sus componentes,
	              -- que sí llevan product_id.
	              AND oi.product_id IS NOT NULL
	            GROUP BY o.business_id, oi.product_id, DATE(o.created_at)
	            ON CONFLICT (business_id, product_id, date)
	            DO UPDATE SET
	                quantity_sold = EXCLUDED.quantity_sold,
	                revenue = EXCLUDED.revenue;
	        """;

	        jdbcTemplate.update(sql, Map.of(
	                "startDate", Date.valueOf(startDate),
	                "endDate", Date.valueOf(endDate)));
	}

}
