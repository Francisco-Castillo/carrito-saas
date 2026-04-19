package com.carrito.saas.repository.jdbc;

import java.util.List;
import java.util.Map;

import com.carrito.saas.repository.entity.ProductTrend;

public interface ProductTrendRepository {

	// Map<Long, Double> getTrends(Long businessId, List<Long> productIds);

	 Map<Long, ProductTrend> findMapByProductIds(Long businessId, List<Long> productIds);

	List<ProductTrend> findTopUp(Long businessId, int limit);

	List<ProductTrend> findTopDown(Long businessId, int limit);
	
	void update();

}
