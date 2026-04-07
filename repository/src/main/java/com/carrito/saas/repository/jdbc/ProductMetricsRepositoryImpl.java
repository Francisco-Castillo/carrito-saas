package com.carrito.saas.repository.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProductMetricsRepositoryImpl implements ProductMetricsRepository {
	
	 private final JdbcTemplate jdbcTemplate;

	@Override
	public void aggregateToday() {
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
	            WHERE o.created_at >= CURRENT_DATE
	            GROUP BY o.business_id, oi.product_id, DATE(o.created_at)
	            ON CONFLICT (business_id, product_id, date)
	            DO UPDATE SET
	                quantity_sold = EXCLUDED.quantity_sold,
	                revenue = EXCLUDED.revenue;
	        """;

	        jdbcTemplate.update(sql);
		
	}

}
