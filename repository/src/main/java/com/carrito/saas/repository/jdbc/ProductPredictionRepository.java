package com.carrito.saas.repository.jdbc;

import java.util.List;
import java.util.Map;

import com.carrito.saas.repository.entity.ProductPrediction;

public interface ProductPredictionRepository {
	
	Map<Long, ProductPrediction> findMapByProductIds(Long businessId, List<Long> productIds);

}
