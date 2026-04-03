package com.carrito.saas.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.projection.DailyAverageProjection;
import com.carrito.saas.repository.projection.HourlyProjection;
import com.carrito.saas.repository.projection.ProductProjection;

@Repository
public interface PredictionRepository extends JpaRepository<Order, Long> {
	// Promedio últimos días
	@Query(value = """
			    SELECT AVG(cnt) as avgOrders
			    FROM (
			        SELECT DATE(o.created_at) as day, COUNT(*) as cnt
			        FROM orders o
			        WHERE o.business_id = :businessId
			        GROUP BY day
			        ORDER BY day DESC
			        LIMIT 7
			    ) sub
			""", nativeQuery = true)
	DailyAverageProjection getDailyAverage(Long businessId);

	// Tendencia
	@Query(value = """
			    SELECT (last7 - prev7)
			    FROM (
			        SELECT COUNT(*) as last7
			        FROM orders
			        WHERE business_id = :businessId
			          AND created_at >= CURRENT_DATE - INTERVAL '7 days'
			    ) a,
			    (
			        SELECT COUNT(*) as prev7
			        FROM orders
			        WHERE business_id = :businessId
			          AND created_at BETWEEN CURRENT_DATE - INTERVAL '14 days'
			          AND CURRENT_DATE - INTERVAL '7 days'
			    ) b
			""", nativeQuery = true)
	Double getTrend(Long businessId);

	// Hora pico
	@Query(value = """
			    SELECT EXTRACT(HOUR FROM created_at) as hour, COUNT(*) as orders
			    FROM orders
			    WHERE business_id = :businessId
			      AND created_at >= CURRENT_DATE - INTERVAL '14 days'
			    GROUP BY hour
			    ORDER BY orders DESC
			    LIMIT 1
			""", nativeQuery = true)
	HourlyProjection getPeakHour(Long businessId);

	//  Distribución horaria
	@Query(value = """
			    SELECT EXTRACT(HOUR FROM created_at) as hour, COUNT(*) as orders
			    FROM orders
			    WHERE business_id = :businessId
			      AND created_at >= CURRENT_DATE - INTERVAL '14 days'
			    GROUP BY hour
			    ORDER BY hour
			""", nativeQuery = true)
	List<HourlyProjection> getHourlyDistribution(Long businessId);

	// Top productos
	@Query(value = """
			    SELECT oi.product_id as productId, SUM(oi.quantity) as quantity
			    FROM order_items oi
			    JOIN orders o ON o.id = oi.order_id
			    WHERE o.business_id = :businessId
			      AND o.created_at >= CURRENT_DATE - INTERVAL '7 days'
			    GROUP BY oi.product_id
			    ORDER BY quantity DESC
			    LIMIT 5
			""", nativeQuery = true)
	List<ProductProjection> getTopProducts(Long businessId);

	// 🍔 Top 1
	@Query(value = """
			    SELECT oi.product_id as productId, SUM(oi.quantity) as quantity
			    FROM order_items oi
			    JOIN orders o ON o.id = oi.order_id
			    WHERE o.business_id = :businessId
			    GROUP BY oi.product_id
			    ORDER BY quantity DESC
			    LIMIT 1
			""", nativeQuery = true)
	ProductProjection getTopProduct(Long businessId);
}
