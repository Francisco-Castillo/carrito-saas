package com.carrito.saas.repository.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PredictionWriteRepositoryImpl implements PredictionWriteRepository{
	
	private final JdbcTemplate jdbc;

	@Override
	public void replaceDailyMetric(Long businessId, int orders, int peakHour, Long topProductId) {
		jdbc.update("""
	            DELETE FROM daily_metrics 
	            WHERE business_id=? AND date=CURRENT_DATE
	        """, businessId);

	        jdbc.update("""
	            INSERT INTO daily_metrics(business_id, date, total_orders, peak_hour, top_product_id)
	            VALUES (?, CURRENT_DATE, ?, ?, ?)
	        """, businessId, orders, peakHour, topProductId);
		
	}

	@Override
	public void replaceHourly(Long businessId) {
		 jdbc.update("""
		            DELETE FROM hourly_predictions 
		            WHERE business_id=? AND date=CURRENT_DATE
		        """, businessId);

		        jdbc.update("""
		            INSERT INTO hourly_predictions(business_id, date, hour, predicted_orders)
		            SELECT ?, CURRENT_DATE, hour, COUNT(*)
		            FROM (
		                SELECT EXTRACT(HOUR FROM created_at)::int as hour
		                FROM orders
		                WHERE business_id = ?
		                  AND created_at >= CURRENT_DATE - INTERVAL '14 days'
		            ) t
		            GROUP BY hour
		        """, businessId, businessId);
		
	}

	@Override
	public void replaceProducts(Long businessId) {
		  jdbc.update("""
		            DELETE FROM product_predictions 
		            WHERE business_id=? AND date=CURRENT_DATE
		        """, businessId);

		        jdbc.update("""
		            INSERT INTO product_predictions(business_id, date, product_id, predicted_quantity)
		            SELECT ?, CURRENT_DATE, oi.product_id, SUM(oi.quantity)
		            FROM order_items oi
		            JOIN orders o ON o.id = oi.order_id
		            WHERE o.business_id = ?
		              AND o.created_at >= CURRENT_DATE - INTERVAL '7 days'
		            GROUP BY oi.product_id
		        """, businessId, businessId);
		
	}

}
