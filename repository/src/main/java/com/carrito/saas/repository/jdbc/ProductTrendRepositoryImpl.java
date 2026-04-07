package com.carrito.saas.repository.jdbc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProductTrendRepositoryImpl implements ProductTrendRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

    public Map<Long, Double> getTrends(Long businessId, List<Long> productIds) {

        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }

        String sql = """
            SELECT product_id,
                   SUM(CASE 
                        WHEN date >= CURRENT_DATE - INTERVAL '7 days' 
                        THEN quantity_sold ELSE 0 END) AS current_week,
                   SUM(CASE 
                        WHEN date >= CURRENT_DATE - INTERVAL '14 days'
                         AND date < CURRENT_DATE - INTERVAL '7 days'
                        THEN quantity_sold ELSE 0 END) AS prev_week
            FROM product_daily_sales
            WHERE business_id = :businessId
              AND product_id IN (:productIds)
            GROUP BY product_id
        """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("businessId", businessId)
                .addValue("productIds", productIds);

        return jdbcTemplate.query(sql, params, rs -> {
            Map<Long, Double> trends = new HashMap<>();

            while (rs.next()) {
                long productId = rs.getLong("product_id");
                double current = rs.getDouble("current_week");
                double prev = rs.getDouble("prev_week");

                double trend;

                if (prev == 0) {
                    trend = current > 0 ? 100.0 : 0.0;
                } else {
                    trend = ((current - prev) / prev) * 100.0;
                }

                trends.put(productId, trend);
            }

            return trends;
        });
    }

}
