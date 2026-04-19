package com.carrito.saas.repository.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.ProductPrediction;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProductPredictionRepositoryImpl implements ProductPredictionRepository {

	private final NamedParameterJdbcTemplate jdbc;

	@Override
	public Map<Long, ProductPrediction> findMapByProductIds(Long businessId, List<Long> productIds) {

		if (productIds == null || productIds.isEmpty()) {
			return Collections.emptyMap();
		}

		String sql = """
			    SELECT 
			        p.product_id,
			        p.business_id,

			        CASE 
			            WHEN t.current_week_sales = 0 THEN 0
			            ELSE ((p.predicted_quantity - t.current_week_sales) * 100.0 / t.current_week_sales)
			        END AS predicted_growth,

			        p.date

			    FROM product_predictions p
			    JOIN product_trends t 
			        ON p.product_id = t.product_id 
			       AND p.business_id = t.business_id

			    WHERE p.business_id = :businessId
			      AND p.product_id = ANY(:productIds)
			""";

		MapSqlParameterSource params = new MapSqlParameterSource().addValue("businessId", businessId)
				.addValue("productIds", productIds.toArray(new Long[0]));

		return jdbc.query(sql, params, rs -> {

			Map<Long, ProductPrediction> map = new HashMap<>(productIds.size());

			while (rs.next()) {
				ProductPrediction p = mapRow(rs);
				map.put(p.getProductId(), p);
			}

			return map;
		});
	}

	private ProductPrediction mapRow(ResultSet rs) throws SQLException {

		ProductPrediction p = new ProductPrediction();

		p.setProductId(rs.getLong("product_id"));
		p.setBusinessId(rs.getLong("business_id"));
		p.setPredictedGrowth(rs.getDouble("predicted_growth"));

		Timestamp ts = rs.getTimestamp("date");
		if (ts != null) {
			p.setPredictionDate(ts.toLocalDateTime());
		}

		return p;
	}

}
