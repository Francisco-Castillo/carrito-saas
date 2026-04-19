package com.carrito.saas.repository.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.ProductTrend;
import com.carrito.saas.repository.enums.TrendDirection;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProductTrendRepositoryImpl implements ProductTrendRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	@Override
	public Map<Long, ProductTrend> findMapByProductIds(Long businessId, List<Long> productIds) {
		String sql = """
				    SELECT *
				    FROM product_trends
				    WHERE business_id = :businessId
				    AND product_id IN (:productIds)
				""";

		return jdbcTemplate.query(sql, Map.of("businessId", businessId, "productIds", productIds), rs -> {

			Map<Long, ProductTrend> map = new HashMap<>(productIds.size());

			while (rs.next()) {
				ProductTrend t = mapRow(rs, 0);
				map.put(t.getProductId(), t);
			}

			return map;
		});
	}

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

		MapSqlParameterSource params = new MapSqlParameterSource().addValue("businessId", businessId)
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

	@Override
	public List<ProductTrend> findTopUp(Long businessId, int limit) {
		
		String sql = """
			    SELECT 
			        pt.product_id,
			        pt.business_id,
			        pt.trend_percentage,
			        pt.trend_direction,
			        pt.current_week_sales,
			        pt.previous_week_sales,
			        p.name AS product_name
			    FROM product_trends pt
			    JOIN products p ON p.id = pt.product_id
			    WHERE pt.business_id = :businessId
			      AND pt.trend_direction = 'UP'
			    ORDER BY pt.trend_percentage DESC
			    LIMIT :limit
			""";
		
		return jdbcTemplate.query(sql, Map.of("businessId", businessId, "limit", limit), this::mapRow);
	}

	@Override
	public List<ProductTrend> findTopDown(Long businessId, int limit) {
		
		String sql = """
			    SELECT 
			        pt.product_id,
			        pt.business_id,
			        pt.trend_percentage,
			        pt.trend_direction,
			        pt.current_week_sales,
			        pt.previous_week_sales,
			        pt.updated_at,
			        p.name AS product_name
			    FROM product_trends pt
			    JOIN products p ON p.id = pt.product_id
			    WHERE pt.business_id = :businessId
			      AND pt.trend_direction = 'DOWN'
			    ORDER BY pt.trend_percentage ASC
			    LIMIT :limit
			""";
		
		return jdbcTemplate.query(sql, Map.of("businessId", businessId, "limit", limit), this::mapRow);
	}

	private ProductTrend mapRow(ResultSet rs, int rowNum) throws SQLException {
		ProductTrend t = new ProductTrend();
		t.setProductId(rs.getLong("product_id"));
		t.setBusinessId(rs.getLong("business_id"));
		t.setTrendPercentage(rs.getDouble("trend_percentage"));
		t.setCurrentWeekSales(rs.getInt("current_week_sales"));
		t.setPreviousWeekSales(rs.getInt("previous_week_sales"));
		t.setTrendDirection(TrendDirection.valueOf(rs.getString("trend_direction")));
		t.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
		t.setProductName(rs.getString("product_name")); 
		return t;
	}

	@Override
	public void update() {
		String PRODUCT_TRENDS_UPSERT_QUERY = """
				WITH sales AS (
				    SELECT
				        product_id,
				        business_id,

				        SUM(CASE
				            WHEN date >= CURRENT_DATE - INTERVAL '7 days'
				            THEN quantity_sold ELSE 0
				        END) AS current_week,

				        SUM(CASE
				            WHEN date < CURRENT_DATE - INTERVAL '7 days'
				             AND date >= CURRENT_DATE - INTERVAL '14 days'
				            THEN quantity_sold ELSE 0
				        END) AS previous_week

				    FROM product_daily_sales
				    WHERE date >= CURRENT_DATE - INTERVAL '14 days'
				    GROUP BY product_id, business_id
				)

				INSERT INTO product_trends (
				    product_id,
				    business_id,
				    trend_percentage,
				    current_week_sales,
				    previous_week_sales,
				    trend_direction,
				    updated_at
				)

				SELECT
				    product_id,
				    business_id,

				    CASE
				        WHEN previous_week = 0 AND current_week > 0 THEN 100
				        WHEN previous_week = 0 AND current_week = 0 THEN 0
				        ELSE ((current_week - previous_week) * 100.0 / previous_week)
				    END AS trend_percentage,

				    current_week,
				    previous_week,

				    CASE
				        WHEN current_week > previous_week THEN 'UP'
				        WHEN current_week < previous_week THEN 'DOWN'
				        ELSE 'STABLE'
				    END AS trend_direction,

				    NOW()

				FROM sales

				ON CONFLICT (product_id, business_id)
				DO UPDATE SET
				    trend_percentage = EXCLUDED.trend_percentage,
				    current_week_sales = EXCLUDED.current_week_sales,
				    previous_week_sales = EXCLUDED.previous_week_sales,
				    trend_direction = EXCLUDED.trend_direction,
				    updated_at = NOW();
				""";
		jdbcTemplate.update(PRODUCT_TRENDS_UPSERT_QUERY, new MapSqlParameterSource());

	}

}
