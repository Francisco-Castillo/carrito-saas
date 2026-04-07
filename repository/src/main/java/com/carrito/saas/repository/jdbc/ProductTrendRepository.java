package com.carrito.saas.repository.jdbc;

import java.util.List;
import java.util.Map;

public interface ProductTrendRepository {
	
	Map<Long, Double> getTrends(Long businessId, List<Long> productIds);

}
